package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Implements Thompson Sampling for Bayesian exploration over worker-type reward distributions.
 *
 * <p>Thompson Sampling (Thompson 1933) is a probability-matching strategy for the
 * multi-armed bandit problem. Instead of adding an explicit exploration bonus (as in UCB),
 * it samples each arm's reward parameter θ from its posterior and selects the arm with the
 * highest sample. Exploration emerges naturally: uncertain arms have wide posteriors and
 * are frequently sampled to be the winner.
 *
 * <p><b>Gaussian conjugate model.</b>
 * For Gaussian rewards with known (estimated) variance σ² and Gaussian prior N(μ₀, σ²₀):
 * <pre>
 *   Posterior after n observations {x₁,…,xₙ}:
 *     precision_post = 1/σ²₀ + n/σ²
 *     μ_post = (μ₀/σ²₀ + Σxᵢ/σ²) / precision_post
 *     σ²_post = 1 / precision_post
 * </pre>
 * Prior: μ₀ = 0.5 (neutral reward), σ²₀ = 1.0 (uninformative).
 * Likelihood variance σ² is estimated from the data (minimum {@value #MIN_VARIANCE}).
 *
 * <p><b>Selection rule.</b>
 * For each candidate worker type, sample θᵢ ~ N(μᵢ_post, σ²ᵢ_post).
 * The worker with argmax θᵢ is selected.
 *
 * <p><b>Relation to PAC-Bayes (#89).</b>
 * Thompson Sampling tells you <em>which arm to pull</em> given current uncertainty.
 * PAC-Bayes bounds tell you <em>when you have enough samples</em> to trust the posterior.
 * Together they define a complete exploration–exploitation protocol.
 *
 * @see <a href="https://doi.org/10.2307/2332286">
 *     Thompson (1933), On the likelihood that one unknown probability exceeds another</a>
 */
@Service
@ConditionalOnProperty(prefix = "thompson-sampling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ThompsonSamplingSelector {

    private static final Logger log = LoggerFactory.getLogger(ThompsonSamplingSelector.class);

    /** Uninformative prior mean (neutral reward midpoint). */
    private static final double PRIOR_MU    = 0.5;
    /** Uninformative prior variance. */
    private static final double PRIOR_VAR   = 1.0;
    /** Minimum variance floor to avoid degenerate posteriors. */
    static final double MIN_VARIANCE = 1e-4;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final RandomGenerator rng;

    @Value("${thompson-sampling.max-samples-per-type:200}")
    private int maxSamplesPerType;

    @Autowired
    public ThompsonSamplingSelector(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.rng = new Random();
    }

    /** Constructor for testing with injected RNG. */
    ThompsonSamplingSelector(TaskOutcomeRepository taskOutcomeRepository, RandomGenerator rng) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.rng = rng;
    }

    /**
     * Runs one Thompson Sampling draw over the provided candidate worker types.
     *
     * @param candidateWorkerTypes list of worker type identifiers to compare (non-empty)
     * @return sampling result including selected worker, all posteriors, and sample values
     * @throws IllegalArgumentException if the candidate list is null or empty
     */
    public ThompsonResult sample(List<String> candidateWorkerTypes) {
        if (candidateWorkerTypes == null || candidateWorkerTypes.isEmpty()) {
            throw new IllegalArgumentException("Candidate worker type list must not be empty");
        }

        // ── Load rewards per worker type ───────────────────────────────────────
        Map<String, List<Double>> rewardsByType = loadRewards(candidateWorkerTypes);

        // ── Compute Gaussian posterior for each worker type ────────────────────
        Map<String, GaussianPosterior> posteriors = new LinkedHashMap<>();
        for (String workerType : candidateWorkerTypes) {
            List<Double> rewards = rewardsByType.getOrDefault(workerType, List.of());
            posteriors.put(workerType, computePosterior(rewards));
        }

        // ── Thompson Sampling: draw θᵢ ~ N(μᵢ, σ²ᵢ) for each, select argmax ─
        Map<String, Double> samples = new LinkedHashMap<>();
        String selectedWorker = null;
        double maxSample = Double.NEGATIVE_INFINITY;

        for (Map.Entry<String, GaussianPosterior> entry : posteriors.entrySet()) {
            GaussianPosterior post = entry.getValue();
            double theta = post.mu() + Math.sqrt(post.variance()) * rng.nextGaussian();
            samples.put(entry.getKey(), theta);
            if (theta > maxSample) {
                maxSample = theta;
                selectedWorker = entry.getKey();
            }
        }

        log.debug("Thompson selected={} samples={}", selectedWorker, samples);
        return new ThompsonResult(selectedWorker, posteriors, samples);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<String, List<Double>> loadRewards(List<String> candidates) {
        // findRewardsByWorkerType returns ALL types — filter to candidates
        List<Object[]> all = taskOutcomeRepository.findRewardsByWorkerType();
        Set<String> candidateSet = new HashSet<>(candidates);
        Map<String, List<Double>> result = new HashMap<>();
        for (Object[] row : all) {
            String wt = (String) row[0];
            if (candidateSet.contains(wt)) {
                double reward = ((Number) row[1]).doubleValue();
                result.computeIfAbsent(wt, k -> new ArrayList<>()).add(reward);
            }
        }
        return result;
    }

    /**
     * Gaussian conjugate posterior update.
     * Prior: N(μ₀=0.5, σ²₀=1.0). Likelihood variance σ² = max(sample_var, MIN_VARIANCE).
     */
    GaussianPosterior computePosterior(List<Double> observations) {
        int n = observations.size();
        if (n == 0) {
            return new GaussianPosterior(PRIOR_MU, PRIOR_VAR, 0);
        }

        double sampleMean = observations.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sampleVar  = observations.stream()
                .mapToDouble(x -> (x - sampleMean) * (x - sampleMean))
                .average().orElse(MIN_VARIANCE);
        double likelihoodVar = Math.max(sampleVar, MIN_VARIANCE);

        double priorPrec     = 1.0 / PRIOR_VAR;
        double likelihoodPrec = (double) n / likelihoodVar;
        double postPrec      = priorPrec + likelihoodPrec;
        double postMu        = (PRIOR_MU * priorPrec + sampleMean * likelihoodPrec) / postPrec;
        double postVar       = 1.0 / postPrec;

        return new GaussianPosterior(postMu, postVar, n);
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────

    /**
     * Gaussian posterior N(μ, σ²) for a single worker type's reward distribution.
     *
     * @param mu           posterior mean
     * @param variance     posterior variance (σ²)
     * @param observations number of reward observations used
     */
    public record GaussianPosterior(double mu, double variance, int observations) {}

    /**
     * Result of one Thompson Sampling draw.
     *
     * @param selectedWorkerType worker type with the highest sampled θ
     * @param posteriors         posterior N(μ, σ²) for each candidate worker type
     * @param sampledValues      the θᵢ sampled from each posterior this round
     */
    public record ThompsonResult(
            String selectedWorkerType,
            Map<String, GaussianPosterior> posteriors,
            Map<String, Double> sampledValues
    ) {}
}
