package com.agentframework.orchestrator.analytics;

import java.util.*;

/**
 * Shapley value computation for fair credit attribution in cooperative games.
 *
 * <p>The Shapley value is the unique allocation rule satisfying four axioms:
 * efficiency (values sum to grand coalition value), symmetry (equal players
 * get equal values), null player (zero-contribution players get zero), and
 * additivity (values decompose across games).</p>
 *
 * <p>Formula: φ_i = Σ_{S ⊆ N\{i}} [|S|!(|N|-|S|-1)!/|N|!] × [v(S∪{i}) - v(S)]</p>
 *
 * <p>In the agent framework context, each worker profile that contributed to a
 * plan is a "player", and v(S) measures the total reward produced by the
 * coalition S of workers.</p>
 *
 * @see <a href="https://doi.org/10.1515/9781400881970-018">
 *     Shapley (1953), A Value for n-Person Games,
 *     Contributions to the Theory of Games II, Annals of Mathematics Studies 28</a>
 */
public final class ShapleyValue {

    /** Maximum number of players for exact computation (2^20 ≈ 1M subsets). */
    static final int MAX_EXACT_PLAYERS = 20;

    private ShapleyValue() {}

    /**
     * Coalition value function: maps a set of players to a real-valued payoff.
     *
     * <p>Contract: {@code v(∅) = 0} (empty coalition produces nothing).</p>
     */
    @FunctionalInterface
    public interface CoalitionValueFunction {
        double value(Set<Integer> coalition);
    }

    /**
     * Exact Shapley value computation via enumeration of all subsets.
     *
     * <p>For each player i, iterates over all subsets S ⊆ N\{i} and accumulates
     * the weighted marginal contributions.</p>
     *
     * <p>Complexity: O(n × 2^n). Feasible for n ≤ {@link #MAX_EXACT_PLAYERS}.</p>
     *
     * @param n number of players
     * @param v coalition value function
     * @return Shapley values, one per player
     * @throws IllegalArgumentException if n &gt; {@link #MAX_EXACT_PLAYERS}
     */
    static double[] shapleyValue(int n, CoalitionValueFunction v) {
        if (n <= 0) return new double[0];
        if (n > MAX_EXACT_PLAYERS) {
            throw new IllegalArgumentException(
                    "Exact Shapley requires n <= " + MAX_EXACT_PLAYERS + ", got " + n);
        }

        double[] phi = new double[n];

        // Pre-compute weights: w(|S|) = |S|! × (n-|S|-1)! / n!
        // Computed as 1 / (n × C(n-1, |S|)) to avoid factorial overflow
        double[] weights = new double[n];
        for (int s = 0; s < n; s++) {
            weights[s] = 1.0 / (n * binomial(n - 1, s));
        }

        // Enumerate all subsets of N\{i} using bitmask
        for (int i = 0; i < n; i++) {
            // Iterate over all subsets of N\{i}
            // We use a bitmask over n-1 positions (excluding player i)
            int[] others = new int[n - 1];
            int idx = 0;
            for (int j = 0; j < n; j++) {
                if (j != i) others[idx++] = j;
            }

            int numSubsets = 1 << (n - 1);
            for (int mask = 0; mask < numSubsets; mask++) {
                Set<Integer> coalition = new HashSet<>();
                for (int bit = 0; bit < n - 1; bit++) {
                    if ((mask & (1 << bit)) != 0) {
                        coalition.add(others[bit]);
                    }
                }

                double mc = marginalContribution(i, coalition, v);
                phi[i] += weights[coalition.size()] * mc;
            }
        }

        return phi;
    }

    /**
     * Marginal contribution of a player to a coalition.
     *
     * <p>{@code mc_i(S) = v(S ∪ {i}) - v(S)}</p>
     *
     * @param player    player index
     * @param coalition the coalition S (must not contain player)
     * @param v         coalition value function
     * @return marginal contribution
     */
    static double marginalContribution(int player, Set<Integer> coalition,
                                        CoalitionValueFunction v) {
        double vWithout = v.value(coalition);
        Set<Integer> withPlayer = new HashSet<>(coalition);
        withPlayer.add(player);
        double vWith = v.value(withPlayer);
        return vWith - vWithout;
    }

    /**
     * Monte Carlo approximation of Shapley values via random permutation sampling.
     *
     * <p>Algorithm: for each sample, generate a random permutation π of players.
     * For each player i at position k in π, compute the marginal contribution
     * of i to the set of players preceding it: {π(0), ..., π(k-1)}.</p>
     *
     * <p>Complexity: O(numSamples × n²) — each permutation requires n coalition
     * value computations.</p>
     *
     * @param n          number of players
     * @param v          coalition value function
     * @param numSamples number of random permutations to sample
     * @param seed       random seed for reproducibility
     * @return approximate Shapley values
     */
    static double[] monteCarloShapley(int n, CoalitionValueFunction v,
                                       int numSamples, long seed) {
        if (n <= 0) return new double[0];

        double[] phi = new double[n];
        Random rng = new Random(seed);
        int[] permutation = new int[n];

        for (int sample = 0; sample < numSamples; sample++) {
            // Generate random permutation (Fisher-Yates shuffle)
            for (int i = 0; i < n; i++) permutation[i] = i;
            for (int i = n - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = permutation[i];
                permutation[i] = permutation[j];
                permutation[j] = tmp;
            }

            // Compute marginal contributions along the permutation
            Set<Integer> coalition = new HashSet<>();
            for (int k = 0; k < n; k++) {
                int player = permutation[k];
                double mc = marginalContribution(player, coalition, v);
                phi[player] += mc;
                coalition.add(player);
            }
        }

        // Average over samples
        for (int i = 0; i < n; i++) {
            phi[i] /= numSamples;
        }

        return phi;
    }

    /**
     * Banzhaf power index (alternative to Shapley).
     *
     * <p>{@code β_i = (1/2^(n-1)) × Σ_{S ⊆ N\{i}} [v(S∪{i}) - v(S)]}</p>
     *
     * <p>Unlike Shapley, Banzhaf does NOT weight by coalition size and is NOT
     * guaranteed to sum to v(N). The raw Banzhaf index measures the average
     * marginal contribution across all possible coalitions equally.</p>
     *
     * @param n number of players
     * @param v coalition value function
     * @return Banzhaf indices, one per player
     */
    static double[] banzhafIndex(int n, CoalitionValueFunction v) {
        if (n <= 0) return new double[0];
        if (n > MAX_EXACT_PLAYERS) {
            throw new IllegalArgumentException(
                    "Exact Banzhaf requires n <= " + MAX_EXACT_PLAYERS + ", got " + n);
        }

        double[] beta = new double[n];
        double normFactor = 1.0 / (1 << (n - 1)); // 1/2^(n-1)

        for (int i = 0; i < n; i++) {
            int[] others = new int[n - 1];
            int idx = 0;
            for (int j = 0; j < n; j++) {
                if (j != i) others[idx++] = j;
            }

            int numSubsets = 1 << (n - 1);
            double sum = 0.0;
            for (int mask = 0; mask < numSubsets; mask++) {
                Set<Integer> coalition = new HashSet<>();
                for (int bit = 0; bit < n - 1; bit++) {
                    if ((mask & (1 << bit)) != 0) {
                        coalition.add(others[bit]);
                    }
                }
                sum += marginalContribution(i, coalition, v);
            }

            beta[i] = normFactor * sum;
        }

        return beta;
    }

    /**
     * Verifies the efficiency axiom: Shapley values sum to the grand coalition value.
     *
     * @param shapleyValues       computed Shapley values
     * @param grandCoalitionValue v(N)
     * @param tolerance           tolerance for floating-point comparison
     * @return true if |Σφ - v(N)| &lt; tolerance
     */
    static boolean verifyEfficiency(double[] shapleyValues, double grandCoalitionValue,
                                     double tolerance) {
        double sum = 0.0;
        for (double phi : shapleyValues) {
            sum += phi;
        }
        return Math.abs(sum - grandCoalitionValue) < tolerance;
    }

    /**
     * Binomial coefficient C(n, k) computed iteratively to avoid overflow.
     *
     * @param n total elements
     * @param k elements to choose
     * @return C(n, k) as a double
     */
    private static double binomial(int n, int k) {
        if (k < 0 || k > n) return 0.0;
        if (k == 0 || k == n) return 1.0;
        // Use symmetry: C(n,k) = C(n, n-k)
        if (k > n - k) k = n - k;
        double result = 1.0;
        for (int i = 0; i < k; i++) {
            result = result * (n - i) / (i + 1);
        }
        return result;
    }

    /**
     * Complete Shapley attribution report.
     *
     * @param playerNames        names of players
     * @param shapleyValues      φ_i for each player (sums to grandCoalitionValue)
     * @param banzhafValues      β_i for each player (alternative index)
     * @param grandCoalitionValue v(N) — total value of the full coalition
     * @param efficiencyCheck    true if Shapley values satisfy the efficiency axiom
     * @param exact              true if computed exactly, false if Monte Carlo approximation
     */
    public record ShapleyReport(
            String[] playerNames,
            double[] shapleyValues,
            double[] banzhafValues,
            double grandCoalitionValue,
            boolean efficiencyCheck,
            boolean exact
    ) {}
}
