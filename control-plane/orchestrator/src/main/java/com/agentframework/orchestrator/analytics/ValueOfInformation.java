package com.agentframework.orchestrator.analytics;

import java.util.Arrays;
import java.util.Random;

/**
 * Value of Information (VoI) analysis for exploration vs exploitation decisions.
 *
 * <p>Computes EVPI (Expected Value of Perfect Information) and EVSI (Expected
 * Value of Sample Information) to determine whether gathering additional data
 * about a worker profile is worth the cost.</p>
 *
 * <p>For the normal-normal conjugate case (GP posterior → new observation),
 * EVSI has a closed-form solution involving the posterior predictive standard
 * deviation and the standard normal PDF at zero.</p>
 *
 * @see <a href="https://doi.org/10.2307/j.ctv36zr3d">
 *     Raiffa &amp; Schlaifer (1961), Applied Statistical Decision Theory,
 *     Harvard University Press</a>
 */
public final class ValueOfInformation {

    private ValueOfInformation() {}

    /** Standard normal PDF at zero: φ(0) = 1/√(2π) ≈ 0.3989. */
    private static final double PHI_ZERO = 1.0 / Math.sqrt(2.0 * Math.PI);

    /** Guard threshold for zero variance. */
    private static final double EPSILON = 1e-12;

    /**
     * EVPI (Expected Value of Perfect Information) for a discrete decision problem.
     *
     * <p>EVPI = E_θ[max_a U(a,θ)] - max_a E_θ[U(a,θ)]</p>
     *
     * <p>For a finite set of outcomes with probabilities, this simplifies to
     * the expected regret of the current best action.</p>
     *
     * @param outcomes      possible payoff values for each scenario
     * @param probabilities probability of each scenario (must sum to 1)
     * @return EVPI (non-negative)
     */
    static double evpi(double[] outcomes, double[] probabilities) {
        if (outcomes == null || outcomes.length == 0) return 0.0;
        if (outcomes.length != probabilities.length) {
            throw new IllegalArgumentException("outcomes and probabilities must have same length");
        }

        // max_a E_theta[U(a,theta)] — expected value of the best fixed action
        // For single-action problems: this is just the expected value
        double expectedValue = 0.0;
        for (int i = 0; i < outcomes.length; i++) {
            expectedValue += outcomes[i] * probabilities[i];
        }

        // E_theta[max_a U(a,theta)] — expected value with perfect information
        // With perfect info, we always pick the best outcome
        double evWithPerfectInfo = 0.0;
        for (int i = 0; i < outcomes.length; i++) {
            evWithPerfectInfo += Math.max(0, outcomes[i]) * probabilities[i];
        }

        // EVPI = improvement from knowing the state of nature
        return Math.max(0.0, evWithPerfectInfo - Math.max(0.0, expectedValue));
    }

    /**
     * EVSI (Expected Value of Sample Information) for the normal-normal conjugate case.
     *
     * <p>Prior: N(μ₀, σ₀²), Sample noise: σₑ²<br>
     * Posterior precision: 1/σ₁² = 1/σ₀² + 1/σₑ²<br>
     * EVSI ≈ √σ₁² × φ(0) ≈ 0.3989 × √(1/(1/σ₀² + 1/σₑ²))</p>
     *
     * <p>This is the expected improvement in decision quality from one
     * additional observation.</p>
     *
     * @param priorSigma2       prior variance (GP posterior σ²)
     * @param sampleNoiseSigma2 observation noise variance
     * @return EVSI (non-negative)
     */
    static double evsiNormalNormal(double priorSigma2, double sampleNoiseSigma2) {
        if (priorSigma2 < EPSILON) return 0.0;
        if (sampleNoiseSigma2 < EPSILON) {
            // Perfect observation → EVSI approaches EVPI
            return Math.sqrt(priorSigma2) * PHI_ZERO;
        }

        // Posterior variance: 1/sigma1^2 = 1/sigma0^2 + 1/sigmaE^2
        double posteriorPrecision = 1.0 / priorSigma2 + 1.0 / sampleNoiseSigma2;
        double posteriorSigma2 = 1.0 / posteriorPrecision;

        return Math.sqrt(posteriorSigma2) * PHI_ZERO;
    }

    /**
     * Net Value of Information: EVSI minus the cost of exploration.
     *
     * <p>Positive = exploration is profitable. Negative = exploitation is preferred.</p>
     *
     * @param evsi            expected value of sample information
     * @param explorationCost cost of gathering the additional observation
     * @return net VoI (can be negative)
     */
    static double netVoi(double evsi, double explorationCost) {
        return evsi - explorationCost;
    }

    /**
     * Makes an explore/exploit decision based on VoI analysis.
     *
     * @param priorMu           prior mean (GP mu)
     * @param priorSigma2       prior variance (GP sigma2)
     * @param sampleNoiseSigma2 observation noise variance
     * @param explorationCost   cost of one exploratory dispatch
     * @return exploration decision with justification
     */
    static ExplorationDecision shouldExplore(double priorMu, double priorSigma2,
                                              double sampleNoiseSigma2, double explorationCost) {
        double evsi = evsiNormalNormal(priorSigma2, sampleNoiseSigma2);
        double net = netVoi(evsi, explorationCost);
        boolean explore = net > 0;

        String reason;
        if (explore) {
            reason = String.format("EVSI=%.4f > cost=%.4f: exploration profitable (net=%.4f)",
                    evsi, explorationCost, net);
        } else {
            reason = String.format("EVSI=%.4f <= cost=%.4f: exploitation preferred (net=%.4f)",
                    evsi, explorationCost, net);
        }

        return new ExplorationDecision(explore, evsi, explorationCost, net, reason);
    }

    /**
     * Ranks profiles by net VoI (most valuable to explore first).
     *
     * @param priorSigma2s      prior variances per profile
     * @param sampleNoiseSigma2 observation noise variance (same for all)
     * @param explorationCost   cost of one exploratory dispatch
     * @return indices sorted by net VoI descending
     */
    static int[] rankByVoi(double[] priorSigma2s, double sampleNoiseSigma2,
                            double explorationCost) {
        if (priorSigma2s == null || priorSigma2s.length == 0) return new int[0];

        int n = priorSigma2s.length;
        double[] netVois = new double[n];
        Integer[] indices = new Integer[n];

        for (int i = 0; i < n; i++) {
            double evsi = evsiNormalNormal(priorSigma2s[i], sampleNoiseSigma2);
            netVois[i] = netVoi(evsi, explorationCost);
            indices[i] = i;
        }

        // Sort by net VoI descending
        Arrays.sort(indices, (a, b) -> Double.compare(netVois[b], netVois[a]));

        int[] result = new int[n];
        for (int i = 0; i < n; i++) result[i] = indices[i];
        return result;
    }

    /**
     * EVPI via Monte Carlo sampling for multiple actions with normal payoffs.
     *
     * <p>For each sample: draw θ ~ N(0,1), compute payoff for each action
     * U(a,θ) = μ_a + σ_a × θ, take max over actions.</p>
     *
     * @param mus        mean payoff for each action
     * @param sigmas     standard deviation for each action
     * @param numSamples number of Monte Carlo samples
     * @param seed       random seed for reproducibility
     * @return EVPI estimate
     */
    static double evpiMonteCarlo(double[] mus, double[] sigmas, int numSamples, long seed) {
        if (mus == null || mus.length == 0) return 0.0;

        int k = mus.length;
        Random rng = new Random(seed);

        // Best fixed action: max_a E[U(a)] = max_a mu_a
        double bestFixedEv = Double.NEGATIVE_INFINITY;
        for (double mu : mus) {
            bestFixedEv = Math.max(bestFixedEv, mu);
        }

        // E[max_a U(a,theta)] via Monte Carlo
        double sumMaxPayoff = 0.0;
        for (int s = 0; s < numSamples; s++) {
            double maxPayoff = Double.NEGATIVE_INFINITY;
            double z = rng.nextGaussian();
            for (int a = 0; a < k; a++) {
                double payoff = mus[a] + sigmas[a] * z;
                maxPayoff = Math.max(maxPayoff, payoff);
            }
            sumMaxPayoff += maxPayoff;
        }

        double evWithPerfectInfo = sumMaxPayoff / numSamples;
        return Math.max(0.0, evWithPerfectInfo - bestFixedEv);
    }

    /**
     * Exploration decision result.
     *
     * @param shouldExplore   true if exploration is profitable
     * @param evsi            expected value of sample information
     * @param explorationCost cost of gathering the sample
     * @param netVoi          EVSI - cost (positive = explore, negative = exploit)
     * @param reason          human-readable justification
     */
    public record ExplorationDecision(
            boolean shouldExplore,
            double evsi,
            double explorationCost,
            double netVoi,
            String reason
    ) {}

    /**
     * VoI report for multiple profiles.
     *
     * @param profileNames               names of analyzed profiles
     * @param evsiValues                 EVSI per profile
     * @param netVoiValues               net VoI per profile
     * @param rankingByVoi               indices sorted by VoI descending
     * @param recommendedExplorationTarget index of best profile to explore
     * @param totalExplorationValue       sum of positive net VoI values
     */
    public record VoiReport(
            String[] profileNames,
            double[] evsiValues,
            double[] netVoiValues,
            int[] rankingByVoi,
            int recommendedExplorationTarget,
            double totalExplorationValue
    ) {}
}
