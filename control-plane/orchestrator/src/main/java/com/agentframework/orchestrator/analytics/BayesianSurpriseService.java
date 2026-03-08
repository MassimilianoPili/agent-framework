package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Detects serendipitous task outcomes using Bayesian Surprise (Itti &amp; Baldi, 2009).
 *
 * <p>Bayesian Surprise is defined as the KL divergence between the posterior and prior
 * belief distribution after observing evidence. A high KL divergence indicates that the
 * observation was unexpected — either a pleasant (positive) or unpleasant (negative) surprise.</p>
 *
 * <p>Model: Gaussian conjugate pair with known likelihood variance σ²_obs.
 * Prior: N(μ₀=0.5, σ₀²=1.0). Likelihood: N(x | μ, σ²_obs=0.25).
 * Posterior: N(μ_post, σ²_post) via precision-weighted update.</p>
 *
 * <pre>
 *   KL(N(μ₁,σ₁²) ∥ N(μ₀,σ₀²)) = log(σ₀/σ₁) + (σ₁² + (μ₁−μ₀)²) / (2σ₀²) − ½
 * </pre>
 */
@Service
@ConditionalOnProperty(prefix = "bayesian-surprise", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BayesianSurpriseService {

    private static final Logger log = LoggerFactory.getLogger(BayesianSurpriseService.class);

    /** Prior mean — neutral 50% expectation. */
    static final double PRIOR_MU  = 0.5;
    /** Prior variance — uninformative. */
    static final double PRIOR_VAR = 1.0;
    /** Likelihood variance: assumed observation noise. */
    private static final double OBS_VAR  = 0.25;
    /** Minimum posterior variance floor to avoid log(0). */
    private static final double MIN_VAR  = 1e-4;
    /** z-score threshold for classifying surprise categories. */
    static final double SURPRISE_Z = 1.0;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${bayesian-surprise.max-samples:500}")
    private int maxSamples;

    public BayesianSurpriseService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes the Bayesian surprise for the given worker type.
     *
     * @param workerType the worker type to analyse
     * @return surprise report, or {@code null} if no data exists
     * @throws IllegalArgumentException if workerType is blank
     */
    public BayesianSurpriseReport analyse(String workerType) {
        if (workerType == null || workerType.isBlank()) {
            throw new IllegalArgumentException("workerType must not be blank");
        }

        List<Object[]> rows = taskOutcomeRepository.findRewardTimeseriesByWorkerType(workerType, maxSamples);
        if (rows.isEmpty()) return null;

        int    n          = rows.size();
        double sumRewards = 0.0;
        for (Object[] row : rows) {
            sumRewards += ((Number) row[1]).doubleValue();
        }
        double sampleMean = sumRewards / n;

        // Precision-weighted Bayesian update
        double priorPrecision      = 1.0 / PRIOR_VAR;
        double likelihoodPrecision = (double) n / OBS_VAR;
        double postPrecision       = priorPrecision + likelihoodPrecision;
        double postVar             = Math.max(1.0 / postPrecision, MIN_VAR);
        double postMu              = (priorPrecision * PRIOR_MU + likelihoodPrecision * sampleMean) / postPrecision;

        // KL divergence: KL(N(μ₁,σ₁²) ∥ N(μ₀,σ₀²))
        double sigma0 = Math.sqrt(PRIOR_VAR);
        double sigma1 = Math.sqrt(postVar);
        double kl     = Math.log(sigma0 / sigma1)
                      + (postVar + Math.pow(postMu - PRIOR_MU, 2)) / (2.0 * PRIOR_VAR)
                      - 0.5;

        double zScore = (postMu - PRIOR_MU) / sigma0;

        SurpriseCategory category;
        if      (zScore >=  SURPRISE_Z) category = SurpriseCategory.POSITIVE_SURPRISE;
        else if (zScore <= -SURPRISE_Z) category = SurpriseCategory.NEGATIVE_SURPRISE;
        else                            category = SurpriseCategory.EXPECTED;

        log.debug("BayesianSurprise: workerType={} n={} postMu={} KL={} category={}",
                workerType, n, postMu, kl, category);

        return new BayesianSurpriseReport(
                workerType, PRIOR_MU, PRIOR_VAR,
                postMu, postVar, n,
                kl, zScore, category
        );
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /** Surprise classification relative to prior expectations. */
    public enum SurpriseCategory { POSITIVE_SURPRISE, EXPECTED, NEGATIVE_SURPRISE }

    /**
     * Bayesian surprise report.
     *
     * @param workerType        analysed worker type
     * @param priorMean         prior mean μ₀
     * @param priorVariance     prior variance σ₀²
     * @param posteriorMean     posterior mean μ_post
     * @param posteriorVariance posterior variance σ²_post
     * @param observations      number of reward observations used
     * @param klDivergence      KL(posterior ∥ prior) — magnitude of surprise
     * @param zScore            standardised distance from prior mean
     * @param surpriseCategory  POSITIVE_SURPRISE / EXPECTED / NEGATIVE_SURPRISE
     */
    public record BayesianSurpriseReport(
            String workerType,
            double priorMean,
            double priorVariance,
            double posteriorMean,
            double posteriorVariance,
            int observations,
            double klDivergence,
            double zScore,
            SurpriseCategory surpriseCategory
    ) {}
}
