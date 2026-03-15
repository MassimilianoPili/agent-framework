package com.agentframework.orchestrator.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores and retrieves prompt variants scored by archetype and worker type.
 *
 * <p>Each prompt variant is associated with a plan archetype and worker type.
 * Variants accumulate PRM (Process Reward Model) scores over usage, and only
 * variants with sufficient usage count ({@code minUsageCount}) are promoted
 * for active use in new plans.</p>
 *
 * <p>This implements the prompt refinement channel of cross-plan knowledge
 * transfer: successful prompt formulations are reused across similar plans.</p>
 */
@Service
@ConditionalOnProperty(prefix = "cross-plan-knowledge", name = "enabled", havingValue = "true", matchIfMissing = false)
public class PromptRefinementStore {

    private static final Logger log = LoggerFactory.getLogger(PromptRefinementStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final CrossPlanKnowledgeConfig config;

    public PromptRefinementStore(JdbcTemplate jdbcTemplate, CrossPlanKnowledgeConfig config) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
    }

    /**
     * Records a prompt outcome, updating the running average PRM score.
     *
     * @param archetype    plan archetype identifier
     * @param workerType   worker type (BE, FE, REVIEW, etc.)
     * @param promptVariant the prompt text used
     * @param prmScore     PRM score for this execution [0.0, 1.0]
     */
    public void recordOutcome(String archetype, String workerType, String promptVariant, double prmScore) {
        try {
            // Upsert: update running average if exists, insert if new
            int updated = jdbcTemplate.update("""
                UPDATE prompt_refinements
                SET prm_score_avg = (prm_score_avg * usage_count + ?) / (usage_count + 1),
                    usage_count = usage_count + 1
                WHERE archetype = ? AND worker_type = ? AND prompt_variant = ?
                """, prmScore, archetype, workerType, promptVariant);

            if (updated == 0) {
                jdbcTemplate.update("""
                    INSERT INTO prompt_refinements (id, archetype, worker_type, prompt_variant, prm_score_avg, usage_count)
                    VALUES (?::uuid, ?, ?, ?, ?, 1)
                    """, UUID.randomUUID().toString(), archetype, workerType, promptVariant, prmScore);
            }

            log.debug("Recorded prompt outcome: archetype={}, workerType={}, score={}", archetype, workerType, prmScore);
        } catch (Exception e) {
            log.warn("Failed to record prompt outcome: {}", e.getMessage());
        }
    }

    /**
     * Retrieves the best prompt variant for a given archetype and worker type.
     *
     * <p>Only returns variants that meet the minimum usage count threshold,
     * ensuring statistical reliability before promotion.</p>
     *
     * @param archetype  plan archetype identifier
     * @param workerType worker type
     * @return best prompt variant, or empty if none qualifies
     */
    public Optional<RankedPrompt> getBestPrompt(String archetype, String workerType) {
        int minUsage = config.promptRefinement().minUsageCount();
        try {
            List<RankedPrompt> results = jdbcTemplate.query("""
                SELECT prompt_variant, prm_score_avg, usage_count
                FROM prompt_refinements
                WHERE archetype = ? AND worker_type = ? AND usage_count >= ?
                ORDER BY prm_score_avg DESC
                LIMIT 1
                """,
                    (rs, rowNum) -> new RankedPrompt(
                            rs.getString("prompt_variant"),
                            rs.getDouble("prm_score_avg"),
                            rs.getInt("usage_count")),
                    archetype, workerType, minUsage);

            return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
        } catch (Exception e) {
            log.debug("Failed to retrieve best prompt: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Retrieves top-K prompt variants for a given archetype and worker type.
     *
     * @param archetype  plan archetype identifier
     * @param workerType worker type
     * @param topK       max results
     * @return ranked list of prompt variants
     */
    public List<RankedPrompt> getTopPrompts(String archetype, String workerType, int topK) {
        int minUsage = config.promptRefinement().minUsageCount();
        try {
            return jdbcTemplate.query("""
                SELECT prompt_variant, prm_score_avg, usage_count
                FROM prompt_refinements
                WHERE archetype = ? AND worker_type = ? AND usage_count >= ?
                ORDER BY prm_score_avg DESC
                LIMIT ?
                """,
                    (rs, rowNum) -> new RankedPrompt(
                            rs.getString("prompt_variant"),
                            rs.getDouble("prm_score_avg"),
                            rs.getInt("usage_count")),
                    archetype, workerType, minUsage, topK);
        } catch (Exception e) {
            log.debug("Failed to retrieve top prompts: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns store statistics for monitoring.
     */
    public Map<String, Object> stats() {
        try {
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM prompt_refinements", Integer.class);
            Integer promoted = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM prompt_refinements WHERE usage_count >= ?",
                    Integer.class, config.promptRefinement().minUsageCount());
            return Map.of("totalVariants", total != null ? total : 0,
                    "promotedVariants", promoted != null ? promoted : 0);
        } catch (Exception e) {
            return Map.of("totalVariants", 0, "promotedVariants", 0);
        }
    }

    /**
     * A prompt variant with its performance ranking.
     *
     * @param promptVariant the prompt text
     * @param prmScoreAvg   average PRM score
     * @param usageCount    number of times used
     */
    public record RankedPrompt(String promptVariant, double prmScoreAvg, int usageCount) {}
}
