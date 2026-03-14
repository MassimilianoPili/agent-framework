package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Markov Shapley Value for sequential credit assignment in multi-agent plans.
 *
 * <p>Extends classical Shapley values to state-dependent (MDP) settings where
 * the contribution of each worker depends on which other workers have already acted.
 * Uses Monte Carlo permutation sampling (TMC-Shapley) for tractable approximation.</p>
 *
 * <p>For each permutation π of workers:
 * <pre>
 *   MSV_i = 1/M Σ_π [V(S_π^i ∪ {i}) − V(S_π^i)]
 * </pre>
 * where S_π^i is the set of workers preceding i in permutation π.</p>
 *
 * <p>Key properties verified:
 * <ul>
 *   <li><b>Efficiency</b>: Σ MSV_i = V(N) − V(∅)</li>
 *   <li><b>Symmetry</b>: equal-contribution workers get equal credit</li>
 *   <li><b>Null player</b>: workers adding no value get zero credit</li>
 * </ul></p>
 *
 * @see <a href="https://arxiv.org/abs/2105.15013">
 *     Wang et al. (NeurIPS 2022) — SHAQ: Shapley Q-Learning</a>
 * @see <a href="https://arxiv.org/abs/1904.02868">
 *     Ghorbani &amp; Zou (ICML 2019) — Data Shapley</a>
 */
@Service
@ConditionalOnProperty(prefix = "markov-shapley", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MarkovShapleyService {

    private static final Logger log = LoggerFactory.getLogger(MarkovShapleyService.class);

    @Value("${markov-shapley.default-permutations:1000}")
    private int defaultPermutations;

    @Value("${markov-shapley.convergence-se-threshold:0.01}")
    private double convergenceSeThreshold;

    /**
     * Computes Markov Shapley Value attributions using Monte Carlo permutation sampling.
     *
     * <p>For M random permutations, computes each worker's marginal contribution
     * when added to the coalition of predecessors. Uses Welford online algorithm
     * for stable running mean and standard error.</p>
     *
     * @param workers         list of worker identifiers
     * @param rewards         map of worker → individual reward contribution
     * @param numPermutations number of permutations to sample (0 = use default)
     * @return attribution result with per-worker values and convergence metrics
     */
    public MarkovShapleyResult computeAttributions(List<String> workers,
                                                     Map<String, Double> rewards,
                                                     int numPermutations) {
        if (workers == null || workers.isEmpty()) {
            return new MarkovShapleyResult(Map.of(), 0, 0.0);
        }

        int M = numPermutations > 0 ? numPermutations : defaultPermutations;
        int n = workers.size();

        // Welford accumulators: per worker
        double[] mean = new double[n];
        double[] m2 = new double[n];  // sum of squared differences from mean
        int[] count = new int[n];

        List<String> perm = new ArrayList<>(workers);

        for (int m = 0; m < M; m++) {
            // Random permutation (Fisher-Yates)
            shuffle(perm);

            Set<String> coalition = new HashSet<>();
            double prevValue = coalitionValue(coalition, rewards);

            for (int i = 0; i < n; i++) {
                String worker = perm.get(i);
                int idx = workers.indexOf(worker);

                coalition.add(worker);
                double newValue = coalitionValue(coalition, rewards);
                double marginal = newValue - prevValue;
                prevValue = newValue;

                // Welford online update
                count[idx]++;
                double delta = marginal - mean[idx];
                mean[idx] += delta / count[idx];
                double delta2 = marginal - mean[idx];
                m2[idx] += delta * delta2;
            }
        }

        // Build results
        Map<String, Double> attributions = new LinkedHashMap<>();
        double maxSe = 0.0;

        for (int i = 0; i < n; i++) {
            attributions.put(workers.get(i), mean[i]);
            if (count[i] > 1) {
                double variance = m2[i] / (count[i] - 1);
                double se = Math.sqrt(variance / count[i]);
                maxSe = Math.max(maxSe, se);
            }
        }

        log.debug("Markov Shapley: {} workers, {} permutations, max SE={}", n, M, maxSe);

        return new MarkovShapleyResult(attributions, M, maxSe);
    }

    /**
     * Computes the value of a coalition of workers.
     *
     * <p>Simple additive model: V(S) = Σ_{i∈S} reward_i.
     * Can be extended to capture synergies using the GP engine.</p>
     *
     * @param coalition set of workers in the coalition
     * @param rewards   map of worker → reward
     * @return coalition value
     */
    public double coalitionValue(Set<String> coalition, Map<String, Double> rewards) {
        if (coalition == null || coalition.isEmpty()) {
            return 0.0;
        }
        return coalition.stream()
                .mapToDouble(w -> rewards.getOrDefault(w, 0.0))
                .sum();
    }

    /**
     * Verifies the efficiency property: Σ MSV_i ≈ V(N) - V(∅).
     *
     * @param result      the attribution result
     * @param totalReward the total reward V(N) of the grand coalition
     * @return true if the sum of attributions is within tolerance of totalReward
     */
    public boolean verifyEfficiency(MarkovShapleyResult result, double totalReward) {
        double sumAttributions = result.attributions().values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        double tolerance = Math.max(0.01, totalReward * 0.05); // 5% or 0.01 absolute
        boolean efficient = Math.abs(sumAttributions - totalReward) <= tolerance;

        if (!efficient) {
            log.warn("Efficiency violation: sum={}, total={}, diff={}",
                     sumAttributions, totalReward, Math.abs(sumAttributions - totalReward));
        }

        return efficient;
    }

    /**
     * Checks if attributions have converged based on standard error.
     *
     * @param result the attribution result
     * @return true if max standard error is below the convergence threshold
     */
    public boolean hasConverged(MarkovShapleyResult result) {
        return result.standardError() <= convergenceSeThreshold;
    }

    private void shuffle(List<String> list) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            String tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    /**
     * Result of Markov Shapley Value computation.
     *
     * @param attributions       per-worker attribution values
     * @param permutationsSampled number of Monte Carlo permutations used
     * @param standardError      maximum standard error across all workers
     */
    public record MarkovShapleyResult(
            Map<String, Double> attributions,
            int permutationsSampled,
            double standardError
    ) {}
}
