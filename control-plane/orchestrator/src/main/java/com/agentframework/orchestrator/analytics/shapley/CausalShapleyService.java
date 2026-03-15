package com.agentframework.orchestrator.analytics.shapley;

import com.agentframework.orchestrator.analytics.CausalDag;
import com.agentframework.orchestrator.analytics.ShapleyDagService;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Causal Shapley value computation using Pearl's do-calculus on the task DAG.
 *
 * <p>Unlike {@link ShapleyDagService} which computes <b>correlational</b> Shapley values
 * (how much reward is associated with each task's presence in a coalition), this service
 * computes <b>causal</b> Shapley values: what is each task's true causal contribution
 * to the plan's success?</p>
 *
 * <h3>Key difference</h3>
 * <ul>
 *   <li><b>Correlational</b> (ShapleyDagService): v(S) = Σ rewards of tasks in S whose
 *       predecessors are also in S. A CONTEXT_MANAGER task gets credit because downstream
 *       tasks can't contribute without it — but this confounds structure with causation.</li>
 *   <li><b>Causal</b> (this service): v_do(S) uses interventional probabilities P(Y|do(X∈S))
 *       via backdoor adjustment. A CONTEXT_MANAGER gets credit only if its presence
 *       <i>causally</i> increases downstream reward, controlling for confounders.</li>
 * </ul>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Build a causal DAG from the plan's dependency structure</li>
 *   <li>Collect observational data from completed tasks (features: duration, tokens, ELO, etc.)</li>
 *   <li>For each Monte Carlo permutation:
 *     <ol>
 *       <li>Add tasks to coalition one by one</li>
 *       <li>Compute interventional coalition value using do-calculus</li>
 *       <li>Record marginal contribution of each task</li>
 *     </ol>
 *   </li>
 *   <li>Average marginal contributions = causal Shapley values</li>
 * </ol>
 *
 * @see CausalDag#interventionalProbability
 * @see ShapleyDagService
 * @see <a href="https://doi.org/10.1017/CBO9780511803161">
 *     Pearl (2009), Causality: Models, Reasoning, and Inference</a>
 * @see <a href="https://proceedings.mlr.press/v119/heskes20a.html">
 *     Heskes et al. (2020) — Causal Shapley Values</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.causal-shapley", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(CausalShapleyConfig.class)
public class CausalShapleyService {

    private static final Logger log = LoggerFactory.getLogger(CausalShapleyService.class);

    private final ShapleyDagService shapleyDagService;
    private final CausalShapleyConfig config;

    public CausalShapleyService(ShapleyDagService shapleyDagService,
                                 CausalShapleyConfig config) {
        this.shapleyDagService = shapleyDagService;
        this.config = config;
    }

    /**
     * Causal Shapley attribution result for a single task.
     *
     * @param taskKey              unique task identifier
     * @param causalShapleyValue   the causal Shapley value (interventional contribution)
     * @param correlationalValue   the standard (correlational) Shapley value for comparison
     * @param marginalContribution average marginal contribution across all permutations
     * @param interactionEffects   map of other taskKey → interaction effect magnitude
     */
    public record Attribution(
            String taskKey,
            double causalShapleyValue,
            double correlationalValue,
            double marginalContribution,
            Map<String, Double> interactionEffects
    ) {}

    /**
     * Computes causal Shapley values for all completed tasks in a plan.
     *
     * @param plan the plan (items must be loaded with dependencies)
     * @return map of taskKey → causal Attribution, or empty map if insufficient data
     */
    public Map<String, Attribution> computeForPlan(Plan plan) {
        List<PlanItem> doneItems = plan.getItems().stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE)
                .toList();

        if (doneItems.isEmpty()) {
            log.debug("No DONE items in plan {} — skipping causal Shapley", plan.getId());
            return Map.of();
        }

        // Get correlational Shapley for comparison
        Map<String, Double> correlational = shapleyDagService.computeForPlan(plan);

        // Build causal DAG from plan dependency structure
        CausalDag causalDag = buildPlanCausalDag(doneItems);

        // Collect observational data from task outcomes
        Map<String, double[]> observationalData = collectObservationalData(doneItems);

        // Compute causal Shapley via Monte Carlo permutations
        Map<String, Double> causalValues = monteCarloInterventionalShapley(
                doneItems, causalDag, observationalData);

        // Compute interaction effects
        Map<String, Map<String, Double>> interactions = computeInteractions(
                doneItems, causalDag, observationalData);

        // Assemble attribution results
        Map<String, Attribution> attributions = new LinkedHashMap<>();
        for (PlanItem item : doneItems) {
            String taskKey = item.getTaskKey();
            attributions.put(taskKey, new Attribution(
                    taskKey,
                    causalValues.getOrDefault(taskKey, 0.0),
                    correlational.getOrDefault(taskKey, 0.0),
                    causalValues.getOrDefault(taskKey, 0.0),
                    interactions.getOrDefault(taskKey, Map.of())
            ));
        }

        log.info("Causal Shapley computed for plan {}: {} tasks, "
                        + "correlational_sum={}, causal_sum={}",
                plan.getId(), doneItems.size(),
                String.format("%.4f", correlational.values().stream().mapToDouble(v -> v).sum()),
                String.format("%.4f", causalValues.values().stream().mapToDouble(v -> v).sum()));

        return attributions;
    }

    // ── Monte Carlo Interventional Shapley ───────────────────────────────────

    /**
     * Monte Carlo approximation of causal Shapley values using do-calculus interventions.
     *
     * <p>For each permutation, tasks are added to a coalition one by one.
     * The coalition value uses interventional probability: P(success|do(coalition_present))
     * instead of correlational P(success|coalition_present).</p>
     */
    Map<String, Double> monteCarloInterventionalShapley(
            List<PlanItem> items, CausalDag dag, Map<String, double[]> data) {

        Map<String, Double> phi = new LinkedHashMap<>();
        for (PlanItem item : items) {
            phi.put(item.getTaskKey(), 0.0);
        }

        List<String> keys = new ArrayList<>(phi.keySet());
        Random rng = new Random(42); // deterministic for reproducibility
        int samples = Math.min(config.maxCoalitions(), factorial(keys.size()));

        for (int k = 0; k < samples; k++) {
            Collections.shuffle(keys, rng);
            Set<String> coalition = new LinkedHashSet<>();
            double prevValue = interventionalCoalitionValue(coalition, items, dag, data);

            for (String taskKey : keys) {
                coalition.add(taskKey);
                double newValue = interventionalCoalitionValue(coalition, items, dag, data);
                double marginal = newValue - prevValue;
                phi.merge(taskKey, marginal / samples, Double::sum);
                prevValue = newValue;
            }
        }

        return phi;
    }

    /**
     * Computes the interventional value of a coalition using do-calculus.
     *
     * <p>For each task in the coalition, we compute P(task_success=1 | do(coalition_present=1))
     * using the CausalDag's backdoor adjustment. This is the expected reward of the coalition
     * under causal intervention, not mere conditioning.</p>
     */
    double interventionalCoalitionValue(Set<String> coalition, List<PlanItem> items,
                                         CausalDag dag, Map<String, double[]> data) {
        if (coalition.isEmpty()) return 0.0;
        if (data.isEmpty() || !data.containsKey("task_success")) return fallbackCoalitionValue(coalition, items);

        double totalValue = 0.0;

        for (PlanItem item : items) {
            if (!coalition.contains(item.getTaskKey())) continue;

            // Check DAG constraints: all predecessors must be in coalition
            boolean allDepsPresent = item.getDependsOn().stream()
                    .filter(dep -> items.stream().anyMatch(i -> i.getTaskKey().equals(dep)))
                    .allMatch(coalition::contains);

            if (!allDepsPresent) continue;

            // Use do-calculus for causal effect estimate
            double reward = rewardOf(item);
            if (reward > 0 && data.containsKey("context_quality")) {
                // Interventional: P(success | do(context_quality = observed_value))
                double causalBoost = dag.interventionalProbability(
                        "context_quality", "task_success", 1.0, data);
                totalValue += reward * causalBoost;
            } else {
                totalValue += reward;
            }
        }

        return totalValue;
    }

    /**
     * Fallback coalition value when insufficient observational data exists.
     * Uses the structural Shapley approach (same as ShapleyDagService).
     */
    private double fallbackCoalitionValue(Set<String> coalition, List<PlanItem> items) {
        double total = 0.0;
        for (PlanItem item : items) {
            if (!coalition.contains(item.getTaskKey())) continue;
            boolean allDepsPresent = item.getDependsOn().stream()
                    .filter(dep -> items.stream().anyMatch(i -> i.getTaskKey().equals(dep)))
                    .allMatch(coalition::contains);
            if (allDepsPresent) {
                total += rewardOf(item);
            }
        }
        return total;
    }

    // ── Interaction effects ──────────────────────────────────────────────────

    /**
     * Computes pairwise interaction effects between tasks.
     *
     * <p>Interaction effect of (i,j) = v(S∪{i,j}) - v(S∪{i}) - v(S∪{j}) + v(S)
     * averaged over random coalitions S. Positive = synergy, negative = redundancy.</p>
     */
    Map<String, Map<String, Double>> computeInteractions(
            List<PlanItem> items, CausalDag dag, Map<String, double[]> data) {

        Map<String, Map<String, Double>> interactions = new LinkedHashMap<>();
        List<String> keys = items.stream().map(PlanItem::getTaskKey).toList();

        // Only compute for small plans to avoid O(n²) explosion
        if (keys.size() > 20) {
            log.debug("Skipping interaction effects: {} tasks (> 20 threshold)", keys.size());
            return interactions;
        }

        Random rng = new Random(123);
        int interactionSamples = Math.min(100, config.maxCoalitions() / 10);

        for (int i = 0; i < keys.size(); i++) {
            Map<String, Double> taskInteractions = new LinkedHashMap<>();
            for (int j = i + 1; j < keys.size(); j++) {
                double interactionSum = 0.0;

                for (int s = 0; s < interactionSamples; s++) {
                    // Random coalition S (excluding i and j)
                    Set<String> baseCoalition = new LinkedHashSet<>();
                    for (int k = 0; k < keys.size(); k++) {
                        if (k != i && k != j && rng.nextBoolean()) {
                            baseCoalition.add(keys.get(k));
                        }
                    }

                    Set<String> sI = new LinkedHashSet<>(baseCoalition);
                    sI.add(keys.get(i));
                    Set<String> sJ = new LinkedHashSet<>(baseCoalition);
                    sJ.add(keys.get(j));
                    Set<String> sIJ = new LinkedHashSet<>(baseCoalition);
                    sIJ.add(keys.get(i));
                    sIJ.add(keys.get(j));

                    double vS = interventionalCoalitionValue(baseCoalition, items, dag, data);
                    double vSI = interventionalCoalitionValue(sI, items, dag, data);
                    double vSJ = interventionalCoalitionValue(sJ, items, dag, data);
                    double vSIJ = interventionalCoalitionValue(sIJ, items, dag, data);

                    interactionSum += (vSIJ - vSI - vSJ + vS);
                }

                double avgInteraction = interactionSum / interactionSamples;
                if (Math.abs(avgInteraction) > 0.001) {
                    taskInteractions.put(keys.get(j), avgInteraction);
                }
            }
            if (!taskInteractions.isEmpty()) {
                interactions.put(keys.get(i), taskInteractions);
            }
        }

        return interactions;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a CausalDag from the plan's dependency structure.
     *
     * <p>Maps task dependencies into causal nodes. Each task becomes a node,
     * edges encode direct causal relationships (predecessor→successor).</p>
     */
    CausalDag buildPlanCausalDag(List<PlanItem> items) {
        List<String> nodes = new ArrayList<>();
        nodes.add("context_quality");
        nodes.add("task_success");

        Map<String, List<String>> edges = new LinkedHashMap<>();

        for (PlanItem item : items) {
            String key = item.getTaskKey();
            if (!nodes.contains(key)) {
                nodes.add(key);
            }
            // Dependencies are causal parents
            List<String> children = new ArrayList<>();
            children.add("task_success"); // every task causally affects overall success
            edges.put(key, children);
        }

        // Context quality causally affects task success
        edges.put("context_quality", List.of("task_success"));

        return new CausalDag(nodes, edges);
    }

    /**
     * Collects observational data from completed task outcomes.
     */
    Map<String, double[]> collectObservationalData(List<PlanItem> items) {
        int n = items.size();
        if (n == 0) return Map.of();

        double[] success = new double[n];
        double[] contextQuality = new double[n];

        for (int i = 0; i < n; i++) {
            PlanItem item = items.get(i);
            success[i] = item.getStatus() == ItemStatus.DONE ? 1.0 : 0.0;
            // Approximate context quality from reward
            double reward = rewardOf(item);
            contextQuality[i] = reward;
        }

        Map<String, double[]> data = new LinkedHashMap<>();
        data.put("task_success", success);
        data.put("context_quality", contextQuality);
        return data;
    }

    private double rewardOf(PlanItem item) {
        return item.getAggregatedReward() != null
                ? item.getAggregatedReward().doubleValue()
                : 0.0;
    }

    private int factorial(int n) {
        // Capped to avoid overflow
        if (n > 12) return Integer.MAX_VALUE;
        int result = 1;
        for (int i = 2; i <= n; i++) result *= i;
        return result;
    }
}
