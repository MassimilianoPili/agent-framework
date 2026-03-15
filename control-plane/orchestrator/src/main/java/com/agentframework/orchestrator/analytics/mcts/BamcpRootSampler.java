package com.agentframework.orchestrator.analytics.mcts;

import com.agentframework.gp.engine.GaussianProcessEngine;
import com.agentframework.gp.model.GpPosterior;
import com.agentframework.gp.model.GpPrediction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * BAMCP root sampling from the Gaussian Process posterior.
 *
 * <p>Bayes-Adaptive Monte Carlo Planning (Guez et al. 2012) samples root beliefs
 * from the posterior distribution instead of using point estimates. This enables
 * exploration of the full uncertainty space: each MCTS simulation starts from
 * a different posterior sample, naturally balancing exploration and exploitation.</p>
 *
 * <h3>Sampling procedure</h3>
 * <p>For each root sample, we predict the GP posterior (μ, σ²) for the given
 * task embedding, then sample from N(μ, σ²). This gives a distribution of
 * "plausible" reward estimates that the MCTS tree can explore.</p>
 *
 * <p>When the GP posterior is unavailable (cold start), the sampler falls back
 * to a uniform prior centered at 0.5 with high variance.</p>
 *
 * @see <a href="https://proceedings.neurips.cc/paper/2012/hash/35cf8659cfcb13224cbd47863a34fc58-Abstract.html">
 *     Guez, Silver &amp; Dayan (2012) — Efficient Bayes-Adaptive RL via Sample-Based Search</a>
 */
public class BamcpRootSampler {

    private static final Logger log = LoggerFactory.getLogger(BamcpRootSampler.class);

    private static final double COLD_START_MEAN = 0.5;
    private static final double COLD_START_VARIANCE = 0.25;

    private final GaussianProcessEngine gpEngine;

    public BamcpRootSampler(GaussianProcessEngine gpEngine) {
        this.gpEngine = gpEngine;
    }

    /**
     * Generates N reward samples from the GP posterior for the given task embedding.
     *
     * <p>Each sample is drawn from N(μ, σ²) where (μ, σ²) = GP.predict(posterior, embedding).
     * These samples represent plausible reward values under the current model uncertainty.</p>
     *
     * @param posterior      fitted GP posterior (null for cold start)
     * @param taskEmbedding  the task's embedding vector (1024-dim)
     * @param nSamples       number of root samples to generate
     * @return array of sampled reward values, clamped to [0, 1]
     */
    public double[] sampleRoots(GpPosterior posterior, float[] taskEmbedding, int nSamples) {
        double mu;
        double sigma;

        if (posterior == null || taskEmbedding == null) {
            // Cold start: sample from broad prior
            mu = COLD_START_MEAN;
            sigma = Math.sqrt(COLD_START_VARIANCE);
            log.debug("BAMCP cold start: sampling from N({}, {})", mu, sigma);
        } else {
            GpPrediction prediction = gpEngine.predict(posterior, taskEmbedding);
            mu = prediction.mu();
            sigma = prediction.sigma();
            if (sigma < 1e-6) {
                sigma = 0.01; // minimum uncertainty to ensure exploration
            }
        }

        double[] samples = new double[nSamples];
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int i = 0; i < nSamples; i++) {
            // Box-Muller transform for N(mu, sigma^2)
            double u1 = rng.nextDouble();
            double u2 = rng.nextDouble();
            double z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
            double sample = mu + sigma * z;
            // Clamp to [0, 1] since rewards are normalized
            samples[i] = Math.max(0.0, Math.min(1.0, sample));
        }

        return samples;
    }

    /**
     * Computes softmax priors over candidate profiles from GP predictions.
     *
     * <p>Given a set of GP predictions for candidate profiles, converts them
     * to a probability distribution via softmax. These priors are used as
     * the P(s,a) term in the PUCT formula.</p>
     *
     * @param predictions GP predictions for each candidate profile (same order as candidateProfiles)
     * @return softmax probabilities (sum to 1.0)
     */
    public double[] softmaxPriors(GpPrediction[] predictions) {
        if (predictions == null || predictions.length == 0) {
            return new double[0];
        }

        double[] logits = new double[predictions.length];
        double maxLogit = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < predictions.length; i++) {
            logits[i] = predictions[i].mu();
            maxLogit = Math.max(maxLogit, logits[i]);
        }

        // Numerically stable softmax
        double sumExp = 0;
        double[] priors = new double[predictions.length];
        for (int i = 0; i < predictions.length; i++) {
            priors[i] = Math.exp(logits[i] - maxLogit);
            sumExp += priors[i];
        }
        if (sumExp > 0) {
            for (int i = 0; i < priors.length; i++) {
                priors[i] /= sumExp;
            }
        }

        return priors;
    }
}
