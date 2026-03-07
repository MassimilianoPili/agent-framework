package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ShapleyValue.CoalitionValueFunction;
import com.agentframework.orchestrator.analytics.ShapleyValue.ShapleyReport;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes Shapley value credit attribution for worker profiles in a plan.
 *
 * <p>Loads all completed task outcomes for a plan, identifies the unique
 * worker profiles as "players" in a cooperative game, defines the coalition
 * value function v(S) as the sum of actual rewards for tasks performed by
 * profiles in S, and computes Shapley values for fair credit attribution.</p>
 *
 * <p>Uses exact computation for small games (n ≤ maxExactPlayers) and
 * Monte Carlo approximation for larger ones.</p>
 *
 * @see ShapleyValue
 * @see <a href="https://doi.org/10.1515/9781400881970-018">
 *     Shapley (1953), A Value for n-Person Games</a>
 */
@Service
@ConditionalOnProperty(prefix = "shapley", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShapleyValueService {

    private static final Logger log = LoggerFactory.getLogger(ShapleyValueService.class);

    private static final double EFFICIENCY_TOLERANCE = 1e-6;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${shapley.monte-carlo-samples:10000}")
    private int monteCarloSamples;

    @Value("${shapley.max-exact-players:10}")
    private int maxExactPlayers;

    public ShapleyValueService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes Shapley value attribution for all workers that contributed to a plan.
     *
     * <ol>
     *   <li>Load all completed outcomes for the plan</li>
     *   <li>Extract unique worker profiles as "players"</li>
     *   <li>Define v(S) = sum of actual_reward for tasks done by profiles in S</li>
     *   <li>Compute Shapley values (exact or Monte Carlo)</li>
     *   <li>Compute Banzhaf indices for comparison</li>
     *   <li>Verify efficiency axiom</li>
     * </ol>
     *
     * @param planId plan UUID
     * @return Shapley attribution report, or null if no outcomes exist
     */
    public ShapleyReport computeForPlan(UUID planId) {
        List<Object[]> outcomes = taskOutcomeRepository.findOutcomesByPlanId(planId);

        if (outcomes.isEmpty()) {
            return null;
        }

        // Group rewards by profile
        // Row format: [0]=worker_profile(String), [1]=actual_reward(Number),
        //             [2]=worker_type(String), [3]=task_key(String)
        Map<String, List<Double>> rewardsByProfile = new LinkedHashMap<>();
        for (Object[] row : outcomes) {
            String profile = (String) row[0];
            double reward = ((Number) row[1]).doubleValue();
            rewardsByProfile.computeIfAbsent(profile, k -> new ArrayList<>()).add(reward);
        }

        String[] playerNames = rewardsByProfile.keySet().toArray(new String[0]);
        int n = playerNames.length;

        // Build profile index map: profile name → player index
        Map<String, Integer> profileIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            profileIndex.put(playerNames[i], i);
        }

        // Pre-compute per-player reward sums for the coalition value function
        double[] playerRewards = new double[n];
        for (int i = 0; i < n; i++) {
            playerRewards[i] = rewardsByProfile.get(playerNames[i]).stream()
                    .mapToDouble(Double::doubleValue).sum();
        }

        // Coalition value function: v(S) = sum of actual_reward for tasks by profiles in S
        // This is additive by construction (each task has one worker),
        // so v(S) = sum of playerRewards[i] for i in S
        CoalitionValueFunction v = coalition -> {
            double total = 0.0;
            for (int player : coalition) {
                total += playerRewards[player];
            }
            return total;
        };

        // Compute grand coalition value
        Set<Integer> grandCoalition = new HashSet<>();
        for (int i = 0; i < n; i++) grandCoalition.add(i);
        double grandCoalitionValue = v.value(grandCoalition);

        // Compute Shapley values
        boolean exact;
        double[] shapleyValues;
        if (n <= maxExactPlayers) {
            shapleyValues = ShapleyValue.shapleyValue(n, v);
            exact = true;
        } else {
            shapleyValues = ShapleyValue.monteCarloShapley(
                    n, v, monteCarloSamples, planId.hashCode());
            exact = false;
        }

        // Compute Banzhaf indices
        double[] banzhafValues;
        if (n <= maxExactPlayers) {
            banzhafValues = ShapleyValue.banzhafIndex(n, v);
        } else {
            // Skip Banzhaf for large n (it also requires 2^n enumeration)
            banzhafValues = new double[n];
        }

        // Verify efficiency
        boolean efficiencyCheck = ShapleyValue.verifyEfficiency(
                shapleyValues, grandCoalitionValue, EFFICIENCY_TOLERANCE);

        log.debug("Shapley attribution for plan {}: {} players, v(N)={}, exact={}, efficient={}",
                planId, n, String.format("%.4f", grandCoalitionValue), exact, efficiencyCheck);

        return new ShapleyReport(
                playerNames, shapleyValues, banzhafValues,
                grandCoalitionValue, efficiencyCheck, exact
        );
    }
}
