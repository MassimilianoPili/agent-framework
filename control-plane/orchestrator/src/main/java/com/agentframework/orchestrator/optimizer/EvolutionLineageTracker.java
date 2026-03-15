package com.agentframework.orchestrator.optimizer;

import com.agentframework.orchestrator.optimizer.PromptEvolutionEngine.MutationStrategy;
import com.agentframework.orchestrator.optimizer.PromptEvolutionEngine.PromptVariant;
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
 * Tracks evolutionary lineage of prompt variants for rollback and analysis.
 *
 * <p>Persists the full ancestry tree: parent→child relationships, mutation
 * operators used, and fitness scores at each generation. This enables:</p>
 * <ul>
 *   <li>Rollback to last-known-good variant on regression</li>
 *   <li>Analysis of which mutation operators are most effective</li>
 *   <li>Visualization of evolutionary trees</li>
 * </ul>
 *
 * <p>Unique contribution vs. Promptbreeder: tracks ALL variants (not just best),
 * inspired by ADAS (Hu et al., 2024) which showed archival of all variants
 * enables better diversity analysis.</p>
 */
@Service
@ConditionalOnProperty(prefix = "self-improving", name = "enabled", havingValue = "true", matchIfMissing = false)
public class EvolutionLineageTracker {

    private static final Logger log = LoggerFactory.getLogger(EvolutionLineageTracker.class);

    private final JdbcTemplate jdbcTemplate;

    public EvolutionLineageTracker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Persists a prompt variant to the lineage store.
     *
     * @param variant the variant to persist
     */
    public void track(PromptVariant variant) {
        try {
            jdbcTemplate.update("""
                INSERT INTO prompt_variants (id, parent_id, worker_type, prompt_content, generation, fitness_score, mutation_strategy)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET fitness_score = EXCLUDED.fitness_score
                """,
                    variant.id().toString(),
                    variant.parentId() != null ? variant.parentId().toString() : null,
                    variant.workerType(), variant.promptContent(),
                    variant.generation(), variant.fitnessScore(),
                    variant.mutationUsed() != null ? variant.mutationUsed().name() : null);
        } catch (Exception e) {
            log.debug("Failed to track variant lineage: {}", e.getMessage());
        }
    }

    /**
     * Finds the last-known-good variant for a worker type.
     *
     * <p>Returns the highest-fitness variant that was promoted via canary.</p>
     *
     * @param workerType worker type
     * @return best promoted variant, or null if none
     */
    @Nullable
    public PromptVariant findLastKnownGood(String workerType) {
        try {
            List<PromptVariant> results = jdbcTemplate.query("""
                SELECT pv.id, pv.parent_id, pv.worker_type, pv.prompt_content,
                       pv.generation, pv.fitness_score, pv.mutation_strategy
                FROM prompt_variants pv
                JOIN canary_results cr ON cr.variant_id = pv.id
                WHERE pv.worker_type = ? AND cr.promoted = true
                ORDER BY pv.fitness_score DESC
                LIMIT 1
                """,
                    (rs, rowNum) -> new PromptVariant(
                            UUID.fromString(rs.getString("id")),
                            parseUUID(rs.getString("parent_id")),
                            rs.getString("worker_type"),
                            rs.getString("prompt_content"),
                            rs.getInt("generation"),
                            rs.getDouble("fitness_score"),
                            parseMutation(rs.getString("mutation_strategy")),
                            null),
                    workerType);

            return results.isEmpty() ? null : results.getFirst();
        } catch (Exception e) {
            log.debug("Failed to find last known good: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the ancestry chain for a variant (child → parent → grandparent → ...).
     *
     * @param variantId starting variant
     * @param maxDepth  max ancestry depth
     * @return ordered list from child to root
     */
    public List<PromptVariant> getAncestry(UUID variantId, int maxDepth) {
        try {
            // Recursive CTE for ancestry traversal
            return jdbcTemplate.query("""
                WITH RECURSIVE ancestry AS (
                    SELECT id, parent_id, worker_type, prompt_content, generation,
                           fitness_score, mutation_strategy, 1 as depth
                    FROM prompt_variants WHERE id = ?::uuid
                    UNION ALL
                    SELECT pv.id, pv.parent_id, pv.worker_type, pv.prompt_content,
                           pv.generation, pv.fitness_score, pv.mutation_strategy, a.depth + 1
                    FROM prompt_variants pv
                    JOIN ancestry a ON pv.id = a.parent_id
                    WHERE a.depth < ?
                )
                SELECT id, parent_id, worker_type, prompt_content, generation,
                       fitness_score, mutation_strategy
                FROM ancestry
                ORDER BY depth
                """,
                    (rs, rowNum) -> new PromptVariant(
                            UUID.fromString(rs.getString("id")),
                            parseUUID(rs.getString("parent_id")),
                            rs.getString("worker_type"),
                            rs.getString("prompt_content"),
                            rs.getInt("generation"),
                            rs.getDouble("fitness_score"),
                            parseMutation(rs.getString("mutation_strategy")),
                            null),
                    variantId.toString(), maxDepth);
        } catch (Exception e) {
            log.debug("Failed to get ancestry: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns mutation operator effectiveness statistics.
     *
     * @param workerType worker type filter
     * @return map of mutation strategy → average fitness delta
     */
    public Map<String, Double> mutationEffectiveness(String workerType) {
        try {
            var rows = jdbcTemplate.queryForList("""
                SELECT mutation_strategy, AVG(fitness_score) as avg_fitness
                FROM prompt_variants
                WHERE worker_type = ? AND mutation_strategy IS NOT NULL
                GROUP BY mutation_strategy
                """, workerType);

            Map<String, Double> result = new java.util.LinkedHashMap<>();
            for (var row : rows) {
                result.put((String) row.get("mutation_strategy"),
                        ((Number) row.get("avg_fitness")).doubleValue());
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // --- Helpers ---

    @Nullable
    private static UUID parseUUID(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    @Nullable
    private static MutationStrategy parseMutation(String s) {
        if (s == null || s.isBlank()) return null;
        try { return MutationStrategy.valueOf(s); } catch (Exception e) { return null; }
    }
}
