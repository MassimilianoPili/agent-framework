package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes superrationality cooperation gains between worker types.
 *
 * <p>Superrationality (Hofstadter, 1985) holds that rational symmetric agents,
 * unable to communicate, should still cooperate because each can reason:
 * "If I cooperate, my symmetric counterpart will also cooperate."
 * Applied here: worker type A and worker type B "cooperate" when co-present
 * in the same plan, if the presence of B raises A's expected reward (and vice versa).</p>
 *
 * <p>Cooperation gain(A,B) = Δ_A + Δ_B where:
 * <ul>
 *   <li>Δ_A = mean(reward_A | B in plan) − mean(reward_A | B absent)</li>
 *   <li>Δ_B = mean(reward_B | A in plan) − mean(reward_B | A absent)</li>
 * </ul>
 * A pair is called <em>cooperative</em> if cooperation_gain > 0.</p>
 *
 * @see <a href="https://doi.org/10.1126/science.221.4615.1093">Hofstadter (1983), Metamagical Themas</a>
 */
@Service
@ConditionalOnProperty(prefix = "superrationality", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SuperrationalityService {

    private static final Logger log = LoggerFactory.getLogger(SuperrationalityService.class);

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${superrationality.min-overlap:2}")
    private int minOverlap;

    public SuperrationalityService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes cooperation gains for all pairs of worker types observed across all plans.
     *
     * @return report with cooperation gains, cooperative pairs and global gain;
     *         or null if insufficient data
     */
    public SuperrationalityReport compute() {
        // [plan_id_text, worker_type, actual_reward]
        List<Object[]> rows = taskOutcomeRepository.findPlanWorkerRewardSummary();

        if (rows.isEmpty()) {
            log.debug("Superrationality: no outcome data available");
            return null;
        }

        // Group rewards by planId → workerType → List<reward>
        Map<String, Map<String, List<Double>>> byPlan = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String planId    = (String) row[0];
            String wtype     = (String) row[1];
            double reward    = ((Number) row[2]).doubleValue();
            byPlan.computeIfAbsent(planId, k -> new LinkedHashMap<>())
                  .computeIfAbsent(wtype,  k -> new ArrayList<>())
                  .add(reward);
        }

        // Enumerate all worker types seen
        Set<String> allTypes = new LinkedHashSet<>();
        for (Map<String, List<Double>> m : byPlan.values()) {
            allTypes.addAll(m.keySet());
        }
        List<String> typeList = new ArrayList<>(allTypes);

        Map<String, Double> cooperationGains = new LinkedHashMap<>();
        List<String> cooperativePairs = new ArrayList<>();
        double globalGain = 0.0;

        for (int i = 0; i < typeList.size(); i++) {
            for (int j = i + 1; j < typeList.size(); j++) {
                String a = typeList.get(i);
                String b = typeList.get(j);

                // Accumulators: plans where both A and B appear vs. each alone
                double sumABoth = 0, sumBBoth = 0;
                double sumAAlone = 0, sumBAlone = 0;
                int countBoth = 0, countAAlone = 0, countBAlone = 0;

                for (Map<String, List<Double>> types : byPlan.values()) {
                    boolean hasA = types.containsKey(a);
                    boolean hasB = types.containsKey(b);

                    if (hasA && hasB) {
                        sumABoth += avgReward(types.get(a));
                        sumBBoth += avgReward(types.get(b));
                        countBoth++;
                    } else if (hasA) {
                        sumAAlone += avgReward(types.get(a));
                        countAAlone++;
                    } else if (hasB) {
                        sumBAlone += avgReward(types.get(b));
                        countBAlone++;
                    }
                }

                if (countBoth < minOverlap) continue;

                double meanABoth  = sumABoth  / countBoth;
                double meanBBoth  = sumBBoth  / countBoth;
                // Baseline: mean reward when the other type is absent; fall back to co-present mean
                double meanAAlone = countAAlone > 0 ? sumAAlone / countAAlone : meanABoth;
                double meanBAlone = countBAlone > 0 ? sumBAlone / countBAlone : meanBBoth;

                // Cooperation gain = mutual uplift from co-presence
                double gain = (meanABoth - meanAAlone) + (meanBBoth - meanBAlone);
                String pairKey = a + " × " + b;
                cooperationGains.put(pairKey, gain);

                if (gain > 0) {
                    cooperativePairs.add(pairKey);
                    globalGain += gain;
                }
            }
        }

        log.debug("Superrationality: {} pairs analyzed, {} cooperative, globalGain={}",
                  cooperationGains.size(), cooperativePairs.size(),
                  String.format("%.4f", globalGain));

        return new SuperrationalityReport(cooperationGains, cooperativePairs, globalGain);
    }

    private static double avgReward(List<Double> rewards) {
        return rewards.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * Superrationality analysis report.
     *
     * @param cooperationGains  map of "A × B" → cooperation gain (positive = cooperative)
     * @param cooperativePairs  pairs with strictly positive cooperation gain
     * @param globalGain        sum of all positive cooperation gains
     */
    public record SuperrationalityReport(
            Map<String, Double> cooperationGains,
            List<String> cooperativePairs,
            double globalGain
    ) {}
}
