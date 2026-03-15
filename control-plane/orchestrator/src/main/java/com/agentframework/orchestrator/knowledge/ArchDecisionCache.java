package com.agentframework.orchestrator.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Caches architectural decisions from Council sessions (#90) with outcome scores.
 *
 * <p>Stores ADR-like decisions: context → decision → rationale → outcome.
 * When a new plan faces a similar architectural choice, the cache provides
 * historical decisions with their outcomes, enabling informed decision-making.</p>
 *
 * <p>Uses a two-tier cache: in-memory LRU for hot decisions + DB persistence
 * for the full history. The outcome score is updated after plan completion,
 * creating a feedback loop between Council recommendations and actual results.</p>
 */
@Service
@ConditionalOnProperty(prefix = "cross-plan-knowledge", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ArchDecisionCache {

    private static final Logger log = LoggerFactory.getLogger(ArchDecisionCache.class);
    private static final int LRU_CAPACITY = 200;

    private final JdbcTemplate jdbcTemplate;

    /** In-memory LRU cache for fast retrieval of recent decisions. */
    private final LinkedHashMap<String, ArchDecision> lruCache;

    public ArchDecisionCache(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.lruCache = new LinkedHashMap<>(LRU_CAPACITY, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ArchDecision> eldest) {
                return size() > LRU_CAPACITY;
            }
        };
    }

    /**
     * Caches an architectural decision from a Council session.
     *
     * @param decision the decision to cache
     */
    public void cacheDecision(ArchDecision decision) {
        try {
            jdbcTemplate.update("""
                INSERT INTO arch_decisions (id, context, decision, rationale, outcome_score, plan_id, council_session_id)
                VALUES (?::uuid, ?, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                    decision.id().toString(), decision.context(), decision.decision(),
                    decision.rationale(), decision.outcomeScore(),
                    decision.planId() != null ? decision.planId().toString() : null,
                    decision.councilSessionId() != null ? decision.councilSessionId().toString() : null);

            synchronized (lruCache) {
                lruCache.put(decision.context(), decision);
            }

            log.debug("Cached architectural decision: {}", decision.context());
        } catch (Exception e) {
            log.warn("Failed to cache architectural decision: {}", e.getMessage());
        }
    }

    /**
     * Retrieves relevant architectural decisions by context keyword matching.
     *
     * <p>Searches both the LRU cache and DB for decisions matching the
     * architectural context. Results are ranked by outcome score.</p>
     *
     * @param architectureContext keywords describing the architectural choice
     * @param maxResults          maximum decisions to return
     * @return ranked list of relevant decisions
     */
    public List<ArchDecision> retrieveDecisions(String architectureContext, int maxResults) {
        if (architectureContext == null || architectureContext.isBlank()) return List.of();

        try {
            // Use PostgreSQL full-text search on context + decision
            String tsQuery = architectureContext.replaceAll("\\s+", " & ");
            return jdbcTemplate.query("""
                SELECT id, context, decision, rationale, outcome_score, plan_id, council_session_id
                FROM arch_decisions
                WHERE to_tsvector('english', context || ' ' || decision) @@ to_tsquery('english', ?)
                ORDER BY outcome_score DESC NULLS LAST
                LIMIT ?
                """,
                    (rs, rowNum) -> new ArchDecision(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("context"),
                            rs.getString("decision"),
                            rs.getString("rationale"),
                            rs.getDouble("outcome_score"),
                            parseUUID(rs.getString("plan_id")),
                            parseUUID(rs.getString("council_session_id"))),
                    tsQuery, maxResults);
        } catch (Exception e) {
            log.debug("Failed to retrieve architectural decisions: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Updates the outcome score of a decision after plan completion.
     *
     * @param decisionId   the decision UUID
     * @param outcomeScore observed outcome [0.0, 1.0]
     */
    public void updateOutcome(UUID decisionId, double outcomeScore) {
        try {
            jdbcTemplate.update("""
                UPDATE arch_decisions SET outcome_score = ? WHERE id = ?::uuid
                """, outcomeScore, decisionId.toString());
        } catch (Exception e) {
            log.debug("Failed to update decision outcome: {}", e.getMessage());
        }
    }

    /**
     * Returns cache statistics.
     */
    public Map<String, Object> stats() {
        try {
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM arch_decisions", Integer.class);
            synchronized (lruCache) {
                return Map.of("totalDecisions", total != null ? total : 0,
                        "lruCacheSize", lruCache.size());
            }
        } catch (Exception e) {
            return Map.of("totalDecisions", 0, "lruCacheSize", 0);
        }
    }

    @Nullable
    private static UUID parseUUID(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    /**
     * An architectural decision record.
     *
     * @param id                unique identifier
     * @param context           architectural context / problem description
     * @param decision          the chosen approach
     * @param rationale         why this approach was chosen
     * @param outcomeScore      observed outcome [0.0, 1.0] (0 if not yet evaluated)
     * @param planId            originating plan UUID
     * @param councilSessionId  Council session that produced this decision
     */
    public record ArchDecision(
            UUID id,
            String context,
            String decision,
            String rationale,
            double outcomeScore,
            @Nullable UUID planId,
            @Nullable UUID councilSessionId
    ) {}
}
