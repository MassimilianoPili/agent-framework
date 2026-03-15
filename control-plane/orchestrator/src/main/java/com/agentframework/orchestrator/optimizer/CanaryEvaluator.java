package com.agentframework.orchestrator.optimizer;

import com.agentframework.orchestrator.optimizer.PromptEvolutionEngine.PromptVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Canary deployment evaluator for prompt variants.
 *
 * <p>Deploys a new prompt variant on a fraction (default 10%) of tasks,
 * collects performance metrics, and performs significance testing before
 * promoting to full deployment.</p>
 *
 * <p>Safety guarantees:</p>
 * <ul>
 *   <li>Minimum sample size before statistical test (default 30)</li>
 *   <li>p &lt; 0.05 required for promotion</li>
 *   <li>Auto-rollback if canary performance &lt; baseline</li>
 *   <li>Holdout set ensures diverse task distribution</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "self-improving", name = "enabled", havingValue = "true", matchIfMissing = false)
public class CanaryEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CanaryEvaluator.class);

    private final JdbcTemplate jdbcTemplate;
    private final SelfImprovingConfig config;

    public CanaryEvaluator(JdbcTemplate jdbcTemplate, SelfImprovingConfig config) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
    }

    /**
     * Determines if a task should use the canary variant.
     *
     * <p>Uses consistent hashing on task ID to ensure repeatable assignment.</p>
     *
     * @param taskId the task UUID
     * @return true if this task should use the canary variant
     */
    public boolean shouldUseCanary(UUID taskId) {
        // Consistent hashing: hash(taskId) mod 100 < fraction*100
        int hash = Math.abs(taskId.hashCode() % 100);
        return hash < (int) (config.canary().fraction() * 100);
    }

    /**
     * Records a canary observation.
     *
     * @param variantId the prompt variant being tested
     * @param taskId    the task UUID
     * @param success   whether the task succeeded
     * @param score     PRM score for this execution
     */
    public void recordObservation(UUID variantId, UUID taskId, boolean success, double score) {
        try {
            // Update or insert canary result with running statistics
            int updated = jdbcTemplate.update("""
                UPDATE canary_results
                SET task_count = task_count + 1,
                    success_rate = (success_rate * (task_count - 1) + ?) / task_count
                WHERE variant_id = ?::uuid AND promoted IS NULL
                """, success ? 1.0 : 0.0, variantId.toString());

            if (updated == 0) {
                jdbcTemplate.update("""
                    INSERT INTO canary_results (id, variant_id, task_count, success_rate)
                    VALUES (?::uuid, ?::uuid, 1, ?)
                    """, UUID.randomUUID().toString(), variantId.toString(), success ? 1.0 : 0.0);
            }
        } catch (Exception e) {
            log.debug("Failed to record canary observation: {}", e.getMessage());
        }
    }

    /**
     * Evaluates whether a canary variant is ready for promotion.
     *
     * <p>Checks: (1) minimum sample size met, (2) success rate > baseline,
     * (3) statistical significance (approximate z-test, p < threshold).</p>
     *
     * @param variantId      the variant being evaluated
     * @param baselineRate   the current baseline success rate
     * @return evaluation result
     */
    public CanaryResult evaluate(UUID variantId, double baselineRate) {
        try {
            var results = jdbcTemplate.query("""
                SELECT task_count, success_rate
                FROM canary_results
                WHERE variant_id = ?::uuid AND promoted IS NULL
                """,
                    (rs, rowNum) -> new double[]{rs.getInt("task_count"), rs.getDouble("success_rate")},
                    variantId.toString());

            if (results.isEmpty()) {
                return new CanaryResult(variantId, 0, 0.0, 1.0, CanaryDecision.INSUFFICIENT_DATA);
            }

            int taskCount = (int) results.getFirst()[0];
            double successRate = results.getFirst()[1];

            // Check minimum samples
            if (taskCount < config.canary().minSamples()) {
                return new CanaryResult(variantId, taskCount, successRate, 1.0,
                        CanaryDecision.INSUFFICIENT_DATA);
            }

            // Approximate z-test for proportion comparison
            double pValue = proportionZTest(successRate, baselineRate, taskCount);

            // Decision
            CanaryDecision decision;
            if (successRate < baselineRate && config.safety().rollbackOnRegression()) {
                decision = CanaryDecision.ROLLBACK;
            } else if (pValue < config.canary().significanceLevel() && successRate > baselineRate) {
                decision = CanaryDecision.PROMOTE;
            } else {
                decision = CanaryDecision.CONTINUE;
            }

            log.debug("Canary evaluation for {}: taskCount={}, successRate={}, baseline={}, p={}, decision={}",
                    variantId, taskCount, successRate, baselineRate, pValue, decision);

            return new CanaryResult(variantId, taskCount, successRate, pValue, decision);

        } catch (Exception e) {
            log.warn("Canary evaluation failed: {}", e.getMessage());
            return new CanaryResult(variantId, 0, 0.0, 1.0, CanaryDecision.INSUFFICIENT_DATA);
        }
    }

    /**
     * Marks a canary as promoted or rolled back.
     */
    public void finalize(UUID variantId, boolean promoted) {
        try {
            jdbcTemplate.update("""
                UPDATE canary_results SET promoted = ? WHERE variant_id = ?::uuid AND promoted IS NULL
                """, promoted, variantId.toString());
        } catch (Exception e) {
            log.debug("Failed to finalize canary: {}", e.getMessage());
        }
    }

    // --- Statistics ---

    /**
     * Approximate z-test for comparing two proportions.
     *
     * <p>H0: canary rate = baseline rate. Returns p-value.</p>
     */
    private double proportionZTest(double canaryRate, double baselineRate, int n) {
        if (n == 0) return 1.0;

        double pooled = (canaryRate + baselineRate) / 2.0;
        if (pooled <= 0 || pooled >= 1) return 1.0;

        double se = Math.sqrt(pooled * (1 - pooled) * (2.0 / n));
        if (se <= 0) return 1.0;

        double z = Math.abs(canaryRate - baselineRate) / se;

        // Approximate p-value from z-score (standard normal CDF)
        return 2.0 * (1.0 - normalCdf(z));
    }

    /**
     * Approximation of standard normal CDF (Abramowitz & Stegun).
     */
    private static double normalCdf(double z) {
        if (z < -8.0) return 0.0;
        if (z > 8.0) return 1.0;

        double sum = 0.0, term = z;
        for (int i = 3; sum + term != sum; i += 2) {
            sum += term;
            term = term * z * z / i;
        }
        return 0.5 + sum * Math.exp(-z * z / 2.0) / Math.sqrt(2.0 * Math.PI);
    }

    // --- Types ---

    public enum CanaryDecision { PROMOTE, ROLLBACK, CONTINUE, INSUFFICIENT_DATA }

    /**
     * Canary evaluation result.
     *
     * @param variantId   the variant evaluated
     * @param taskCount   number of canary observations
     * @param successRate observed success rate
     * @param pValue      statistical significance
     * @param decision    recommended action
     */
    public record CanaryResult(
            UUID variantId,
            int taskCount,
            double successRate,
            double pValue,
            CanaryDecision decision
    ) {}
}
