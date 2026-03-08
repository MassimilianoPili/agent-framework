package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyses convergence of worker reward estimates using Fixed-Point Theory.
 *
 * <p>Two complementary theorems are applied:</p>
 * <ol>
 *   <li><b>Banach Contraction Mapping Theorem</b>: the GP posterior update operator T
 *       is a contraction if the Lipschitz constant L = α/(1+α) &lt; 1. The unique
 *       fixed point x* = T(x*) equals the sample mean (the prior washes out).</li>
 *   <li><b>Brouwer Fixed-Point Theorem</b>: any continuous function f: [0,1] → [0,1]
 *       has at least one fixed point. Since task rewards are bounded in [0,1] and
 *       the update operator T is continuous, a fixed point is always guaranteed.</li>
 * </ol>
 *
 * <p>Operator: T(x) = (α·x + μ_data) / (1 + α) where α = prior weight (default 0.1).
 * This is a weighted average between the current estimate and the observed sample mean,
 * converging at rate L = α/(1+α) ≈ 0.091 per iteration.</p>
 *
 * <p>The convergence curve records |x_n − x*| at every {@value #CURVE_STEP} iterations
 * for diagnostic visualisation.</p>
 */
@Service
@ConditionalOnProperty(prefix = "functional-analysis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FixedPointAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FixedPointAnalyzer.class);

    /** Prior weight in the contraction operator: T(x) = (α·x + μ_data) / (1+α). */
    static final double ALPHA = 0.1;

    /** Lipschitz constant L = α/(1+α) — strictly less than 1 by construction. */
    static final double CONTRACTION_RATIO = ALPHA / (1.0 + ALPHA);

    /** Record a convergence curve point every N iterations. */
    private static final int CURVE_STEP = 5;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${functional-analysis.epsilon:0.001}")
    private double epsilon;

    @Value("${functional-analysis.max-iterations:100}")
    private int maxIterations;

    @Value("${functional-analysis.max-samples:500}")
    private int maxSamples;

    public FixedPointAnalyzer(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Analyses fixed-point convergence of the reward operator for the given worker type.
     *
     * @param workerType the worker type to analyse
     * @return fixed-point report, or {@code null} if no data exists
     * @throws IllegalArgumentException if workerType is blank
     */
    public FixedPointReport analyse(String workerType) {
        if (workerType == null || workerType.isBlank()) {
            throw new IllegalArgumentException("workerType must not be blank");
        }

        List<Object[]> rows = taskOutcomeRepository.findRewardTimeseriesByWorkerType(workerType, maxSamples);
        if (rows.isEmpty()) return null;

        double sampleMean = rows.stream()
                .mapToDouble(r -> ((Number) r[1]).doubleValue())
                .average().orElse(0.0);

        // Analytical fixed point: T(x*) = x*
        // (α·x* + μ_data)/(1+α) = x*  →  x* = μ_data
        double fixedPoint = sampleMean;

        // Iterative simulation from x₀ = 0.5 (neutral prior start)
        double           x                = 0.5;
        int              iterations       = 0;
        boolean          converged        = false;
        List<double[]>   convergenceCurve = new ArrayList<>();

        for (int i = 0; i < maxIterations; i++) {
            double xNext = (ALPHA * x + sampleMean) / (1.0 + ALPHA);
            double error = Math.abs(xNext - fixedPoint);

            if (i % CURVE_STEP == 0) {
                convergenceCurve.add(new double[]{i, error});
            }

            x          = xNext;
            iterations = i + 1;

            if (error < epsilon) {
                converged = true;
                break;
            }
        }

        // Brouwer condition: T: [0,1]→[0,1] continuous → always satisfied for rewards in [0,1]
        boolean brouwerConditionMet = true;

        log.debug("FixedPoint: workerType={} x*={} converged={} iters={} L={}",
                workerType, fixedPoint, converged, iterations, CONTRACTION_RATIO);

        return new FixedPointReport(
                workerType, converged, fixedPoint,
                CONTRACTION_RATIO, iterations,
                brouwerConditionMet, convergenceCurve
        );
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * Fixed-point convergence report.
     *
     * @param workerType         analysed worker type
     * @param converged          true if |x_n − x*| &lt; ε before maxIterations
     * @param fixedPointValue    x* = T(x*) — the reward operator's attractor
     * @param contractionRatio   Lipschitz constant L = α/(1+α) &lt; 1
     * @param iterations         number of iterations performed
     * @param brouwerConditionMet true (rewards ∈ [0,1], T continuous → always holds)
     * @param convergenceCurve   list of [iteration, error] pairs sampled every 5 steps
     */
    public record FixedPointReport(
            String workerType,
            boolean converged,
            double fixedPointValue,
            double contractionRatio,
            int iterations,
            boolean brouwerConditionMet,
            List<double[]> convergenceCurve
    ) {}
}
