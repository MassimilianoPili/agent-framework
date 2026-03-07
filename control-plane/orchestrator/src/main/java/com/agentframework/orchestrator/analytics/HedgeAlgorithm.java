package com.agentframework.orchestrator.analytics;

/**
 * Hedge (Multiplicative Weights Update) algorithm for online expert selection.
 *
 * <p>Update rule: {@code wᵢ(t+1) = wᵢ(t) × exp(-η × lossᵢ(t))}, then renormalize.
 * Learning rate: {@code η = √(ln(K) / T)} where K = number of experts, T = horizon.
 * Regret bound: {@code O(√(T × ln(K)))}.</p>
 *
 * <p>This achieves the minimax-optimal regret rate for the experts problem.
 * Weights are maintained as a probability distribution (sum to 1).</p>
 *
 * @see <a href="https://doi.org/10.1006/game.1997.0541">
 *     Freund &amp; Schapire (1997), A Decision-Theoretic Generalization of
 *     On-Line Learning, J. Computer and System Sciences 55(1)</a>
 */
public final class HedgeAlgorithm {

    /** Minimum weight to prevent numerical underflow. */
    static final double MIN_WEIGHT = 1e-10;

    private HedgeAlgorithm() {}

    /**
     * Optimal learning rate for the hedge algorithm.
     *
     * <p>{@code η = √(ln(K) / T)}, which minimizes the regret bound.</p>
     *
     * @param numExperts number of experts K
     * @param horizon    planning horizon T (total rounds)
     * @return learning rate η
     */
    static double learningRate(int numExperts, int horizon) {
        if (numExperts <= 1) return 0.1;
        if (horizon <= 0) return Math.sqrt(Math.log(numExperts));
        return Math.sqrt(Math.log(numExperts) / horizon);
    }

    /**
     * Multiplicative weights update.
     *
     * <p>For each expert i: {@code w'ᵢ = wᵢ × exp(-η × lossᵢ)}, then renormalize
     * so all weights sum to 1. Weights are clamped to {@link #MIN_WEIGHT} to
     * prevent numerical death.</p>
     *
     * @param weights current weight distribution (must sum to ~1)
     * @param losses  loss vector (one per expert, typically in [0, 1])
     * @param eta     learning rate
     * @return updated weight distribution (new array, sums to 1)
     */
    static double[] update(double[] weights, double[] losses, double eta) {
        if (weights.length != losses.length) {
            throw new IllegalArgumentException("weights and losses must have same length");
        }
        double[] updated = new double[weights.length];
        double sum = 0.0;

        for (int i = 0; i < weights.length; i++) {
            updated[i] = Math.max(MIN_WEIGHT, weights[i] * Math.exp(-eta * losses[i]));
            sum += updated[i];
        }

        // Renormalize
        if (sum > 0) {
            for (int i = 0; i < updated.length; i++) {
                updated[i] /= sum;
            }
        }
        return updated;
    }

    /**
     * Creates uniform initial weights.
     *
     * @param n number of experts
     * @return array of [1/n, 1/n, ...]
     */
    static double[] uniformWeights(int n) {
        double[] w = new double[n];
        double val = 1.0 / n;
        for (int i = 0; i < n; i++) w[i] = val;
        return w;
    }

    /**
     * Selects the expert with the highest weight (greedy exploitation).
     *
     * @param weights current weight distribution
     * @return index of the expert with maximum weight
     */
    static int selectExpert(double[] weights) {
        int best = 0;
        for (int i = 1; i < weights.length; i++) {
            if (weights[i] > weights[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Theoretical regret bound: {@code √(T × ln(K))}.
     *
     * <p>After T rounds with K experts, the cumulative regret of the hedge
     * algorithm against the best expert in hindsight is at most this value.</p>
     *
     * @param numExperts number of experts K
     * @param horizon    number of rounds T
     * @return regret upper bound
     */
    static double regretBound(int numExperts, int horizon) {
        if (numExperts <= 1 || horizon <= 0) return 0.0;
        return Math.sqrt(horizon * Math.log(numExperts));
    }

    /**
     * Serializable state for Redis persistence.
     *
     * @param experts expert identifiers (e.g. worker profile names)
     * @param weights current weight distribution
     * @param round   current round number
     * @param eta     learning rate in use
     */
    public record HedgeState(String[] experts, double[] weights, int round, double eta) {}
}
