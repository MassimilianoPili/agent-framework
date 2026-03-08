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
 * Computes PAC-Bayes convergence bounds for the Gaussian Process worker-selection model.
 *
 * <p>PAC-Bayes theory (McAllester 1999) bounds the generalisation error of a learned
 * hypothesis (here: the GP posterior over worker performance) relative to a prior.
 * The main inequality used is the simplified McAllester–Seeger bound:
 * <pre>
 *   P(L(h) > L̂(h) + ε)  ≤  2 · exp(−n · ε² / 2)
 * </pre>
 * where:
 * <ul>
 *   <li>L(h) = true generalisation error of the GP posterior</li>
 *   <li>L̂(h) = empirical error on the n observed task outcomes</li>
 *   <li>ε   = desired tolerance (e.g. 0.05)</li>
 *   <li>n   = number of task outcomes (training samples)</li>
 * </ul>
 *
 * <p>From this bound, the minimum sample size n_min to achieve an (ε, δ)-PAC guarantee
 * (i.e., confidence ≥ 1 − δ) is:
 * <pre>
 *   n_min = ⌈ 2 · ln(2/δ) / ε² ⌉
 * </pre>
 *
 * <p>The KL divergence term measures how far the posterior is from the prior,
 * normalised by sample count. A high KL/n indicates overfitting risk.
 * KL(Q ∥ P) is approximated from GP hyperparameters as (σ_posterior / σ_prior)² − 1
 * − ln(σ_posterior/σ_prior) + (μ_posterior − μ_prior)² / (2·σ_prior²).
 *
 * @see <a href="https://doi.org/10.1145/307400.307422">
 *     McAllester (1999), Some PAC-Bayesian Theorems</a>
 * @see <a href="https://doi.org/10.1006/jcss.2001.1795">
 *     Seeger (2002), PAC-Bayesian Generalisation Error Bounds for Gaussian Process Classification</a>
 */
@Service
@ConditionalOnProperty(prefix = "pac-bayes", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PACBayesService {

    private static final Logger log = LoggerFactory.getLogger(PACBayesService.class);

    /** GP prior mean (reward baseline before any data). */
    private static final double PRIOR_MU    = 0.5;
    /** GP prior standard deviation. */
    private static final double PRIOR_SIGMA = 0.3;

    /** Number of sample-size checkpoints in the convergence curve. */
    static final int CURVE_STEPS = 20;

    @Value("${pac-bayes.default-epsilon:0.05}")
    private double defaultEpsilon;

    @Value("${pac-bayes.default-delta:0.05}")
    private double defaultDelta;

    private final TaskOutcomeRepository taskOutcomeRepository;

    public PACBayesService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes the PAC-Bayes convergence report for a given worker type.
     *
     * @param workerType worker type identifier (e.g. {@code "be-java"})
     * @param epsilon    desired generalisation error tolerance (e.g. 0.05)
     * @param delta      acceptable failure probability (e.g. 0.05 → 95 % confidence)
     * @return convergence report, or {@code null} if no outcomes exist
     */
    public PACBayesReport compute(String workerType, double epsilon, double delta) {
        if (epsilon <= 0 || epsilon >= 1) throw new IllegalArgumentException("epsilon must be in (0,1)");
        if (delta  <= 0 || delta  >= 1) throw new IllegalArgumentException("delta must be in (0,1)");

        // Load reward timeseries to get n and estimate posterior parameters
        List<Object[]> rows = taskOutcomeRepository.findRewardTimeseriesByWorkerType(
                workerType, 1000);

        int n = rows.size();

        if (n == 0) {
            log.debug("PACBayesService: no outcomes for workerType={}", workerType);
            return null;
        }

        // Estimate posterior mean and std from observed rewards
        double sumR = 0, sumR2 = 0;
        for (Object[] row : rows) {
            double reward = row[1] instanceof Number num ? num.doubleValue() : 0.0;
            sumR  += reward;
            sumR2 += reward * reward;
        }
        double posteriorMu    = sumR / n;
        double variance       = Math.max(1e-6, sumR2 / n - posteriorMu * posteriorMu);
        double posteriorSigma = Math.sqrt(variance);

        // KL(Q ∥ P) for Gaussians: 0.5 * [(σ_Q/σ_P)² - 1 - ln(σ_Q²/σ_P²) + (μ_Q-μ_P)²/σ_P²]
        double sigmaRatio = posteriorSigma / PRIOR_SIGMA;
        double klDivergence = 0.5 * (sigmaRatio * sigmaRatio - 1
                - 2 * Math.log(sigmaRatio)
                + Math.pow(posteriorMu - PRIOR_MU, 2) / (PRIOR_SIGMA * PRIOR_SIGMA));
        klDivergence = Math.max(0.0, klDivergence);

        // Minimum samples for (ε, δ)-PAC guarantee: n_min = ⌈2 ln(2/δ) / ε²⌉
        int requiredSamples = (int) Math.ceil(2.0 * Math.log(2.0 / delta) / (epsilon * epsilon));

        // Confidence bound at current n: ε_n = sqrt(2 ln(2/δ) / n)
        double confidenceBound = Math.sqrt(2.0 * Math.log(2.0 / delta) / n);

        boolean convergenceReached = n >= requiredSamples;

        // Convergence curve: (n_i, ε_i) pairs showing how bound tightens with more samples
        List<double[]> convergenceCurve = new ArrayList<>(CURVE_STEPS);
        for (int i = 1; i <= CURVE_STEPS; i++) {
            int ni = Math.max(1, (int) Math.ceil((double) requiredSamples * i / CURVE_STEPS));
            double ei = Math.sqrt(2.0 * Math.log(2.0 / delta) / ni);
            convergenceCurve.add(new double[]{ni, ei});
        }

        log.debug("PACBayesService: workerType={}, n={}, required={}, convergence={}, kl={:.4f}, bound={:.4f}",
                workerType, n, requiredSamples, convergenceReached, klDivergence, confidenceBound);

        return new PACBayesReport(
                workerType,
                n,
                requiredSamples,
                convergenceReached,
                klDivergence,
                confidenceBound,
                convergenceCurve
        );
    }

    /**
     * Convenience overload using the configured default ε and δ values.
     *
     * @param workerType worker type identifier
     * @return convergence report using {@code default-epsilon} and {@code default-delta}
     */
    public PACBayesReport compute(String workerType) {
        return compute(workerType, defaultEpsilon, defaultDelta);
    }

    /**
     * PAC-Bayes convergence report for a worker type's GP model.
     *
     * @param workerType        worker type analysed
     * @param currentSamples    number of task outcomes currently available
     * @param requiredSamples   minimum samples for the (ε, δ)-PAC guarantee
     * @param convergenceReached whether {@code currentSamples ≥ requiredSamples}
     * @param klDivergence      KL(posterior ∥ prior) — higher means more overfit risk
     * @param confidenceBound   current generalisation error bound ε_n
     * @param convergenceCurve  list of (n_i, ε_i) pairs showing convergence trajectory
     */
    public record PACBayesReport(
            String workerType,
            int currentSamples,
            int requiredSamples,
            boolean convergenceReached,
            double klDivergence,
            double confidenceBound,
            List<double[]> convergenceCurve
    ) {}
}
