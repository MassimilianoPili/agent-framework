package com.agentframework.orchestrator.knowledge;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Extracts generalizable insights from completed plan executions.
 *
 * <p>Implements the ExpeL pattern (Zhao et al., AAAI 2024): instead of storing
 * raw execution trajectories, extract concise, generalizable insights that can
 * be applied to future plans. This is more token-efficient than trajectory
 * replay and transfers better across different plan structures.</p>
 *
 * <p>Insight categories:</p>
 * <ul>
 *   <li><b>DECOMPOSITION</b>: how the plan was broken into tasks</li>
 *   <li><b>ORDERING</b>: task execution order that worked well</li>
 *   <li><b>RECOVERY</b>: how failures were handled</li>
 *   <li><b>PATTERN</b>: recurring code/architecture patterns</li>
 *   <li><b>ANTI_PATTERN</b>: approaches that failed consistently</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "cross-plan-knowledge", name = "enabled", havingValue = "true", matchIfMissing = false)
public class InsightExtractor {

    private static final Logger log = LoggerFactory.getLogger(InsightExtractor.class);

    private final JdbcTemplate jdbcTemplate;
    private final CrossPlanKnowledgeConfig config;

    public InsightExtractor(JdbcTemplate jdbcTemplate, CrossPlanKnowledgeConfig config) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
    }

    /**
     * Extracts insights from a completed plan.
     *
     * <p>Analyzes the plan's execution trajectory to identify generalizable
     * patterns. Only insights with applicability above the configured threshold
     * are stored.</p>
     *
     * @param plan the completed plan
     * @return list of extracted insights
     */
    public List<PlanInsight> extractInsights(Plan plan) {
        if (plan == null || plan.getItems() == null) return List.of();

        List<PlanInsight> insights = new ArrayList<>();
        double minApplicability = config.insightExtraction().minApplicability();

        // Decomposition insight: analyze task structure
        PlanInsight decomposition = analyzeDecomposition(plan);
        if (decomposition != null && decomposition.applicabilityScore() >= minApplicability) {
            insights.add(decomposition);
        }

        // Ordering insight: detect effective task ordering
        PlanInsight ordering = analyzeOrdering(plan);
        if (ordering != null && ordering.applicabilityScore() >= minApplicability) {
            insights.add(ordering);
        }

        // Recovery insight: analyze failure handling
        PlanInsight recovery = analyzeRecovery(plan);
        if (recovery != null && recovery.applicabilityScore() >= minApplicability) {
            insights.add(recovery);
        }

        // Persist insights
        for (PlanInsight insight : insights) {
            persist(insight);
        }

        log.debug("Extracted {} insights from plan {}", insights.size(), plan.getId());
        return insights;
    }

    /**
     * Retrieves insights relevant to a plan specification.
     *
     * @param category      insight category filter (null = all)
     * @param maxResults    maximum insights to return
     * @return ranked list of insights by applicability
     */
    public List<PlanInsight> findRelevant(@Nullable String category, int maxResults) {
        try {
            if (category != null) {
                return jdbcTemplate.query("""
                    SELECT id, plan_id, insight_text, category, applicability_score
                    FROM plan_insights
                    WHERE category = ? AND applicability_score >= ?
                    ORDER BY applicability_score DESC
                    LIMIT ?
                    """,
                        (rs, rowNum) -> new PlanInsight(
                                UUID.fromString(rs.getString("id")),
                                parseUUID(rs.getString("plan_id")),
                                rs.getString("insight_text"),
                                rs.getString("category"),
                                rs.getDouble("applicability_score")),
                        category, config.insightExtraction().minApplicability(), maxResults);
            }

            return jdbcTemplate.query("""
                SELECT id, plan_id, insight_text, category, applicability_score
                FROM plan_insights
                WHERE applicability_score >= ?
                ORDER BY applicability_score DESC
                LIMIT ?
                """,
                    (rs, rowNum) -> new PlanInsight(
                            UUID.fromString(rs.getString("id")),
                            parseUUID(rs.getString("plan_id")),
                            rs.getString("insight_text"),
                            rs.getString("category"),
                            rs.getDouble("applicability_score")),
                    config.insightExtraction().minApplicability(), maxResults);
        } catch (Exception e) {
            log.debug("Failed to retrieve insights: {}", e.getMessage());
            return List.of();
        }
    }

    // --- Analysis methods ---

    @Nullable
    private PlanInsight analyzeDecomposition(Plan plan) {
        List<PlanItem> items = plan.getItems();
        if (items == null || items.isEmpty()) return null;

        int totalTasks = items.size();
        long completedTasks = items.stream().filter(i -> "COMPLETED".equals(i.getStatus())).count();
        double successRate = totalTasks > 0 ? (double) completedTasks / totalTasks : 0;

        if (successRate < 0.5) return null; // only learn from mostly-successful plans

        // Count distinct worker types used
        long distinctWorkers = items.stream()
                .map(PlanItem::getWorkerType)
                .distinct()
                .count();

        String insight = String.format(
                "Plan with %d tasks across %d worker types achieved %.0f%% completion rate. " +
                "Task decomposition granularity: %d tasks.",
                totalTasks, distinctWorkers, successRate * 100, totalTasks);

        return new PlanInsight(UUID.randomUUID(), plan.getId(), insight,
                "DECOMPOSITION", successRate);
    }

    @Nullable
    private PlanInsight analyzeOrdering(Plan plan) {
        List<PlanItem> items = plan.getItems();
        if (items == null || items.size() < 3) return null;

        // Check if dependency-ordered execution was effective
        long failedItems = items.stream().filter(i -> "FAILED".equals(i.getStatus())).count();
        if (failedItems > items.size() / 2) return null;

        // Detect bottleneck: items that blocked others
        long itemsWithDependencies = items.stream()
                .filter(i -> i.getDependsOn() != null && !i.getDependsOn().isEmpty())
                .count();

        double dependencyRatio = (double) itemsWithDependencies / items.size();
        double applicability = 1.0 - (double) failedItems / items.size();

        String insight = String.format(
                "%.0f%% of tasks had dependencies. Effective ordering: dependency-first execution " +
                "with %.0f%% success rate.",
                dependencyRatio * 100, applicability * 100);

        return new PlanInsight(UUID.randomUUID(), plan.getId(), insight,
                "ORDERING", applicability);
    }

    @Nullable
    private PlanInsight analyzeRecovery(Plan plan) {
        List<PlanItem> items = plan.getItems();
        if (items == null) return null;

        long retriedItems = items.stream().filter(i -> i.getContextRetryCount() > 0).count();
        if (retriedItems == 0) return null;

        long recoveredItems = items.stream()
                .filter(i -> i.getContextRetryCount() > 0 && "COMPLETED".equals(i.getStatus()))
                .count();
        double recoveryRate = retriedItems > 0 ? (double) recoveredItems / retriedItems : 0;

        String insight = String.format(
                "%d tasks required retry, %d recovered (%.0f%% recovery rate). " +
                "Retry was %s for this plan type.",
                retriedItems, recoveredItems, recoveryRate * 100,
                recoveryRate > 0.5 ? "effective" : "mostly ineffective");

        return new PlanInsight(UUID.randomUUID(), plan.getId(), insight,
                "RECOVERY", recoveryRate);
    }

    // --- Persistence ---

    private void persist(PlanInsight insight) {
        try {
            jdbcTemplate.update("""
                INSERT INTO plan_insights (id, plan_id, insight_text, category, applicability_score)
                VALUES (?::uuid, ?::uuid, ?, ?, ?)
                """,
                    insight.id().toString(),
                    insight.planId() != null ? insight.planId().toString() : null,
                    insight.insightText(), insight.category(), insight.applicabilityScore());
        } catch (Exception e) {
            log.debug("Failed to persist insight: {}", e.getMessage());
        }
    }

    @Nullable
    private static UUID parseUUID(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    /**
     * A generalizable insight extracted from plan execution.
     *
     * @param id                 unique identifier
     * @param planId             originating plan
     * @param insightText        natural language insight
     * @param category           DECOMPOSITION, ORDERING, RECOVERY, PATTERN, ANTI_PATTERN
     * @param applicabilityScore estimated generalizability [0.0, 1.0]
     */
    public record PlanInsight(
            UUID id,
            @Nullable UUID planId,
            String insightText,
            String category,
            double applicabilityScore
    ) {}
}
