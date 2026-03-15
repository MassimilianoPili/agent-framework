package com.agentframework.orchestrator.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Error Pattern Library implementing the Reflexion pattern (Shinn et al., NeurIPS 2023).
 *
 * <p>Stores structured error patterns as contextual triples:
 * {@code (context_embedding, error_description, correction_strategy, confidence)}.
 * Retrieval via pgvector embedding similarity enables zero-shot transfer of
 * error-handling knowledge across plans without parametric updates.</p>
 *
 * <p>Unlike raw trajectory storage, Reflexion uses verbal reinforcement —
 * the correction is a natural language strategy, not code. This generalizes
 * better across different codebases and languages.</p>
 */
@Service
@ConditionalOnProperty(prefix = "cross-plan-knowledge", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ErrorPatternLibrary {

    private static final Logger log = LoggerFactory.getLogger(ErrorPatternLibrary.class);

    private final JdbcTemplate jdbcTemplate;
    private final CrossPlanKnowledgeConfig config;

    public ErrorPatternLibrary(JdbcTemplate jdbcTemplate, CrossPlanKnowledgeConfig config) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
    }

    /**
     * Records an error pattern from a failed task execution.
     *
     * @param pattern the error pattern to store
     */
    public void record(ErrorPattern pattern) {
        try {
            jdbcTemplate.update("""
                INSERT INTO error_patterns (id, context_embedding, error_description, correction, confidence, outcome, plan_id)
                VALUES (?::uuid, ?::vector, ?, ?, ?, ?, ?::uuid)
                """,
                    UUID.randomUUID().toString(),
                    pattern.contextEmbedding() != null ? formatVector(pattern.contextEmbedding()) : null,
                    pattern.errorDescription(),
                    pattern.correction(),
                    pattern.confidence(),
                    pattern.outcome(),
                    pattern.planId() != null ? pattern.planId().toString() : null);

            log.debug("Recorded error pattern: {} (confidence={})", pattern.errorDescription(), pattern.confidence());
        } catch (Exception e) {
            log.warn("Failed to record error pattern: {}", e.getMessage());
        }
    }

    /**
     * Finds relevant error patterns by embedding similarity.
     *
     * <p>Uses pgvector cosine distance to find the most similar error contexts,
     * filtered by minimum confidence threshold.</p>
     *
     * @param contextEmbedding the current task's context embedding (1024 dim)
     * @param topK             maximum patterns to retrieve (0 = use config default)
     * @return ranked list of relevant error patterns
     */
    public List<ErrorPattern> findRelevant(float[] contextEmbedding, int topK) {
        if (contextEmbedding == null) return List.of();

        int limit = topK > 0 ? topK : config.errorPatterns().topkRetrieval();
        double minConfidence = config.errorPatterns().minConfidence();

        try {
            return jdbcTemplate.query("""
                SELECT id, error_description, correction, confidence, outcome, plan_id,
                       1 - (context_embedding <=> ?::vector) as similarity
                FROM error_patterns
                WHERE confidence >= ?
                  AND context_embedding IS NOT NULL
                ORDER BY context_embedding <=> ?::vector
                LIMIT ?
                """,
                    (rs, rowNum) -> new ErrorPattern(
                            null, // embedding not needed in results
                            rs.getString("error_description"),
                            rs.getString("correction"),
                            rs.getDouble("confidence"),
                            rs.getString("outcome"),
                            parseUUID(rs.getString("plan_id"))),
                    formatVector(contextEmbedding), minConfidence,
                    formatVector(contextEmbedding), limit);
        } catch (Exception e) {
            log.warn("Error pattern retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Updates the confidence of an error pattern based on outcome feedback.
     *
     * <p>Exponential moving average: new = old * 0.8 + signal * 0.2.
     * If the correction was applied and the task succeeded, confidence increases;
     * if it failed, confidence decreases.</p>
     *
     * @param patternId the pattern UUID
     * @param succeeded whether applying the correction led to success
     */
    public void updateConfidence(UUID patternId, boolean succeeded) {
        try {
            double signal = succeeded ? 1.0 : 0.0;
            jdbcTemplate.update("""
                UPDATE error_patterns
                SET confidence = confidence * 0.8 + ? * 0.2,
                    outcome = ?
                WHERE id = ?::uuid
                """, signal, succeeded ? "APPLIED_SUCCESS" : "APPLIED_FAILURE", patternId.toString());
        } catch (Exception e) {
            log.debug("Failed to update error pattern confidence: {}", e.getMessage());
        }
    }

    /**
     * Returns library statistics for monitoring.
     */
    public Map<String, Object> stats() {
        try {
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM error_patterns", Integer.class);
            Double avgConfidence = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(AVG(confidence), 0) FROM error_patterns", Double.class);
            return Map.of("totalPatterns", total != null ? total : 0,
                    "avgConfidence", avgConfidence != null ? avgConfidence : 0.0);
        } catch (Exception e) {
            return Map.of("totalPatterns", 0, "avgConfidence", 0.0);
        }
    }

    // --- Helpers ---

    private static String formatVector(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    @Nullable
    private static UUID parseUUID(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    /**
     * Structured error pattern (Reflexion triple + metadata).
     *
     * @param contextEmbedding task context embedding (1024 dim, nullable for retrieval results)
     * @param errorDescription what went wrong
     * @param correction       recommended correction strategy (natural language)
     * @param confidence       pattern confidence [0.0, 1.0]
     * @param outcome          outcome when correction was applied (nullable)
     * @param planId           originating plan UUID (nullable)
     */
    public record ErrorPattern(
            @Nullable float[] contextEmbedding,
            String errorDescription,
            String correction,
            double confidence,
            @Nullable String outcome,
            @Nullable UUID planId
    ) {}
}
