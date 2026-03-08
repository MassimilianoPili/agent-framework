package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Analyses the ergodic structure of worker token-budget allocation using
 * Ole Peters' Ergodic Economics (2019).
 *
 * <p><b>Why ergodicity matters for budgeting.</b>
 * Classical expected-value maximisation optimises the <em>ensemble average</em>
 * (arithmetic mean reward over many hypothetical parallel worlds). A single
 * agent, however, lives through <em>time</em> — the relevant optimand is the
 * <em>time-average growth rate</em>:
 * <pre>
 *   ensemble average  = E[X]          = arithmetic mean (rewards)
 *   time average      = exp(E[ln X])  = geometric mean  (rewards)
 *   ergodicity gap    = ensemble_avg − time_avg ≥ 0  (by AM–GM inequality)
 * </pre>
 * A larger gap signals that reward variance is compounding negatively over
 * time — even if the expected value looks attractive, the long-run growth rate
 * is penalised.
 *
 * <p><b>Kelly Criterion connection.</b>
 * For Gaussian rewards with mean μ and variance σ², the time-average growth
 * rate is maximised by allocating fraction f* = μ/σ² of the total budget to
 * the worker type (Kelly 1956). Allocating more than f* causes the long-run
 * growth rate to decrease.
 *
 * <p>The ergodicity gap approximates σ²/(2μ) for small relative variance — so
 * high-gap workers should be given smaller proportional budget shares.
 *
 * @see <a href="https://doi.org/10.1038/s41567-019-0732-0">
 *     Ole Peters (2019), The ergodicity problem in economics</a>
 */
@Service
@ConditionalOnProperty(prefix = "ergodic-budget", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ErgodicBudgetAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ErgodicBudgetAnalyzer.class);

    /** Minimum samples required for a meaningful ergodic estimate. */
    private static final int MIN_SAMPLES = 5;

    /** Ergodicity gap threshold above which we classify STRONGLY_NON_ERGODIC. */
    private static final double HIGH_GAP_THRESHOLD = 0.10;

    /** Ergodicity gap threshold below which we classify NEAR_ERGODIC. */
    private static final double LOW_GAP_THRESHOLD  = 0.01;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${ergodic-budget.max-samples:500}")
    private int maxSamples;

    public ErgodicBudgetAnalyzer(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes the ergodic analysis for a given worker type.
     *
     * @param workerType the worker type identifier (e.g. "be-java", "fe-ts")
     * @return ergodic report, or {@code null} when fewer than {@value #MIN_SAMPLES} samples exist
     */
    public ErgodicReport analyze(String workerType) {
        List<Object[]> rows = taskOutcomeRepository.findRewardTimeseriesByWorkerType(workerType, maxSamples);
        if (rows.isEmpty()) return null;

        // Filter to strictly positive rewards (log requires positive domain)
        double[] rewards = rows.stream()
                .mapToDouble(r -> ((Number) r[1]).doubleValue())
                .filter(d -> d > 0.0)
                .toArray();

        if (rewards.length < MIN_SAMPLES) return null;

        // ── Ensemble average (arithmetic mean) ────────────────────────────────
        double ensembleAvg = Arrays.stream(rewards).average().orElse(0.0);

        // ── Time average (geometric mean = exp(E[ln X])) ──────────────────────
        double timeAvg = Math.exp(
                Arrays.stream(rewards).map(Math::log).average().orElse(0.0));

        // ── Ergodicity gap: Δ = ensemble_avg − time_avg ≥ 0 (AM–GM) ──────────
        double ergodicityGap = ensembleAvg - timeAvg;

        // ── Variance and Kelly fraction ────────────────────────────────────────
        double mean = ensembleAvg;
        double variance = Arrays.stream(rewards)
                .map(r -> (r - mean) * (r - mean))
                .average().orElse(0.0);

        // f* = μ / σ² (Kelly criterion for proportional budget allocation)
        // Clamp to [0, 1] since budget fraction must be a valid proportion
        double kellyFraction = variance > 1e-9
                ? Math.min(1.0, Math.max(0.0, mean / variance))
                : 1.0; // zero variance → allocate full budget safely

        // ── Regime classification ──────────────────────────────────────────────
        ErgodicsRegime regime;
        if (ergodicityGap < LOW_GAP_THRESHOLD) {
            regime = ErgodicsRegime.NEAR_ERGODIC;
        } else if (ergodicityGap < HIGH_GAP_THRESHOLD) {
            regime = ErgodicsRegime.MILDLY_NON_ERGODIC;
        } else {
            regime = ErgodicsRegime.STRONGLY_NON_ERGODIC;
        }

        log.debug("Ergodic[{}] n={} ensembleAvg={} timeAvg={} gap={} kelly={} regime={}",
                workerType, rewards.length, ensembleAvg, timeAvg,
                ergodicityGap, kellyFraction, regime);

        return new ErgodicReport(
                workerType, rewards.length,
                ensembleAvg, timeAvg, ergodicityGap,
                kellyFraction, regime,
                kellyFraction /* recommendedBudgetShare = Kelly fraction */
        );
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────

    /**
     * Ergodic analysis result for a worker type.
     *
     * @param workerType             worker type identifier
     * @param sampleCount            number of strictly-positive reward samples used
     * @param ensembleAverage        arithmetic mean of rewards (classical expected value)
     * @param timeAverage            geometric mean of rewards (ergodic / time-average)
     * @param ergodicityGap          ensemble_avg − time_avg; always ≥ 0; larger = more non-ergodic
     * @param kellyFraction          f* = μ/σ²; optimal proportional budget fraction
     * @param regime                 ergodic regime classification
     * @param recommendedBudgetShare fraction of total token budget to assign this worker type
     */
    public record ErgodicReport(
            String workerType,
            int sampleCount,
            double ensembleAverage,
            double timeAverage,
            double ergodicityGap,
            double kellyFraction,
            ErgodicsRegime regime,
            double recommendedBudgetShare
    ) {}

    /**
     * Ergodic regime classification based on ergodicity gap magnitude.
     */
    public enum ErgodicsRegime {
        /** gap &lt; 0.01: time-avg ≈ ensemble-avg; variance is negligible. */
        NEAR_ERGODIC,
        /** 0.01 ≤ gap &lt; 0.10: variance compounds meaningfully; Kelly allocation recommended. */
        MILDLY_NON_ERGODIC,
        /** gap ≥ 0.10: high variance severely penalises long-run growth; reduce allocation. */
        STRONGLY_NON_ERGODIC
    }
}
