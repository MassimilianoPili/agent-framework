package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.budget.TokenLedgerService;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DAG-aware Shapley value computation for fair reward attribution (#40).
 *
 * <p>Unlike {@link ShapleyValueService} which operates at the worker-profile level
 * with an additive coalition value function, this service computes Shapley values
 * at the <b>task level</b> respecting the dependency DAG. A task contributes to
 * a coalition only if all its predecessors are present.</p>
 *
 * <p>This creates meaningful attribution for infrastructure workers (CONTEXT_MANAGER,
 * HOOK_MANAGER, etc.) which enable downstream domain workers but receive no direct
 * {@code aggregatedReward}.</p>
 *
 * <h3>Algorithm</h3>
 * <p>Monte Carlo sampling of random permutations with a DAG-constrained coalition
 * value function. The DAG constraints are encoded in v(S): a task contributes
 * only if all its predecessors are in the coalition. This gives infrastructure
 * workers (enablers with reward=0) credit for unlocking downstream rewards —
 * in permutations where the enabler appears after its successor, the successor
 * contributes nothing until the enabler "unlocks" it.</p>
 *
 * @see ShapleyValue for the mathematical foundation
 * @see ShapleyValueService for profile-level attribution (coexists)
 */
@Service
public class ShapleyDagService {

    private static final Logger log = LoggerFactory.getLogger(ShapleyDagService.class);

    private static final double EFFICIENCY_TOLERANCE = 1e-6;

    private final PlanItemRepository planItemRepository;
    private final TokenLedgerService tokenLedgerService;

    @Value("${shapley.dag.monte-carlo-samples:1000}")
    private int monteCarloSamples;

    public ShapleyDagService(PlanItemRepository planItemRepository,
                             TokenLedgerService tokenLedgerService) {
        this.planItemRepository = planItemRepository;
        this.tokenLedgerService = tokenLedgerService;
    }

    /**
     * Computes DAG-aware Shapley values for all completed items in a plan,
     * persists the values, and records Shapley credits in the token ledger.
     *
     * @param plan the plan (must have items loaded with dependencies)
     * @return Shapley values per task key, or empty map if no DONE items
     */
    @Transactional
    public Map<String, Double> computeForPlan(Plan plan) {
        List<PlanItem> doneItems = plan.getItems().stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE)
                .toList();

        if (doneItems.isEmpty()) {
            log.debug("No DONE items in plan {} — skipping Shapley computation", plan.getId());
            return Map.of();
        }

        // Compute Shapley values via Monte Carlo
        Map<String, Double> shapleyValues = monteCarloShapleyDag(doneItems, monteCarloSamples);

        // Verify efficiency axiom
        double grandCoalitionValue = doneItems.stream()
                .mapToDouble(i -> rewardOf(i))
                .sum();
        double shapleySum = shapleyValues.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean efficient = Math.abs(shapleySum - grandCoalitionValue) < EFFICIENCY_TOLERANCE;

        if (!efficient) {
            log.warn("Shapley efficiency check failed for plan {}: Σφ={} vs v(N)={} (diff={})",
                    plan.getId(), shapleySum, grandCoalitionValue,
                    Math.abs(shapleySum - grandCoalitionValue));
        }

        // Persist Shapley values on PlanItems
        for (PlanItem item : doneItems) {
            Double sv = shapleyValues.get(item.getTaskKey());
            if (sv != null) {
                item.setShapleyValue(sv);
            }
        }
        planItemRepository.saveAll(doneItems);

        // Record Shapley credits in TokenLedger for infrastructure workers
        long planTotalTokens = computePlanTotalTokens(doneItems);
        if (planTotalTokens > 0) {
            recordShapleyCredits(plan.getId(), doneItems, shapleyValues, planTotalTokens);
        }

        log.info("Shapley DAG computed for plan {}: {} tasks, v(N)={}, efficient={}, samples={}",
                plan.getId(), doneItems.size(),
                String.format("%.4f", grandCoalitionValue), efficient, monteCarloSamples);

        return shapleyValues;
    }

    /**
     * Returns the grand coalition value for a plan (sum of all rewards).
     */
    public double grandCoalitionValue(List<PlanItem> items) {
        return items.stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE)
                .mapToDouble(this::rewardOf)
                .sum();
    }

    // ── Monte Carlo DAG Shapley ─────────────────────────────────────────────

    /**
     * Monte Carlo approximation of Shapley values respecting DAG constraints.
     *
     * <p>For each sample, generates a <b>random permutation</b> (Fisher-Yates shuffle)
     * and computes marginal contributions using the DAG-constrained coalition value
     * function. Unlike topological-only orderings, unrestricted permutations allow
     * enabler tasks (with reward=0) to receive positive Shapley values: in permutations
     * where the enabler appears after its successor, the successor can't contribute
     * (predecessors missing), and the enabler "unlocks" its reward upon joining.</p>
     *
     * @param items   DONE plan items
     * @param samples number of Monte Carlo samples
     * @return taskKey → Shapley value
     */
    Map<String, Double> monteCarloShapleyDag(List<PlanItem> items, int samples) {
        Map<String, Double> phi = new LinkedHashMap<>();
        for (PlanItem item : items) {
            phi.put(item.getTaskKey(), 0.0);
        }

        Map<String, Set<String>> predecessors = buildPredecessorsMap(items);
        Map<String, Double> rewards = buildRewardsMap(items);
        Set<String> taskKeys = phi.keySet();
        Random rng = new Random();

        // Build a mutable list of task keys for shuffling
        List<String> keys = new ArrayList<>(phi.keySet());

        for (int k = 0; k < samples; k++) {
            // Fisher-Yates shuffle — all permutations, not just topological
            Collections.shuffle(keys, rng);
            Set<String> coalition = new HashSet<>();
            double prevValue = 0.0;

            for (String taskKey : keys) {
                coalition.add(taskKey);
                double newValue = dagCoalitionValue(coalition, predecessors, rewards, taskKeys);
                double marginal = newValue - prevValue;
                phi.merge(taskKey, marginal / samples, Double::sum);
                prevValue = newValue;
            }
        }

        return phi;
    }

    /**
     * Coalition value function respecting DAG constraints.
     *
     * <p>A task contributes its reward only if ALL its predecessors (that exist
     * in the DAG of completed tasks) are present in the coalition.</p>
     *
     * @param coalition    current coalition of task keys
     * @param predecessors DAG predecessor map
     * @param rewards      reward per task key
     * @param validTasks   set of task keys in the DAG (filters out external deps)
     * @return total coalition value
     */
    double dagCoalitionValue(Set<String> coalition, Map<String, Set<String>> predecessors,
                             Map<String, Double> rewards, Set<String> validTasks) {
        double total = 0.0;
        for (String task : coalition) {
            Set<String> deps = predecessors.getOrDefault(task, Set.of());
            // Only check predecessors that are part of this DAG (filter external deps)
            boolean allDepsPresent = deps.stream()
                    .filter(validTasks::contains)
                    .allMatch(coalition::contains);
            if (allDepsPresent) {
                total += rewards.getOrDefault(task, 0.0);
            }
        }
        return total;
    }

    /**
     * Generates a random topological ordering using Kahn's algorithm with
     * random selection among nodes at zero in-degree.
     *
     * <p>Retained for DAG validation and potential future use. The Shapley
     * computation itself uses unrestricted permutations for proper enabler
     * credit attribution.</p>
     */
    List<String> randomTopologicalOrder(List<PlanItem> items,
                                        Map<String, Set<String>> predecessors,
                                        Random rng) {
        Set<String> validTasks = items.stream()
                .map(PlanItem::getTaskKey)
                .collect(Collectors.toSet());

        // Compute in-degree considering only internal dependencies
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Set<String>> successors = new HashMap<>();
        for (PlanItem item : items) {
            String key = item.getTaskKey();
            int deg = 0;
            for (String dep : predecessors.getOrDefault(key, Set.of())) {
                if (validTasks.contains(dep)) {
                    deg++;
                    successors.computeIfAbsent(dep, k -> new HashSet<>()).add(key);
                }
            }
            inDegree.put(key, deg);
        }

        // Kahn's with random selection
        List<String> ready = new ArrayList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>(items.size());
        while (!ready.isEmpty()) {
            int idx = rng.nextInt(ready.size());
            String chosen = ready.remove(idx);
            order.add(chosen);
            for (String succ : successors.getOrDefault(chosen, Set.of())) {
                int newDeg = inDegree.merge(succ, -1, Integer::sum);
                if (newDeg == 0) {
                    ready.add(succ);
                }
            }
        }

        return order;
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    Map<String, Set<String>> buildPredecessorsMap(List<PlanItem> items) {
        Map<String, Set<String>> preds = new HashMap<>();
        for (PlanItem item : items) {
            preds.put(item.getTaskKey(), new HashSet<>(item.getDependsOn()));
        }
        return preds;
    }

    Map<String, Double> buildRewardsMap(List<PlanItem> items) {
        Map<String, Double> rewards = new HashMap<>();
        for (PlanItem item : items) {
            rewards.put(item.getTaskKey(), rewardOf(item));
        }
        return rewards;
    }

    private double rewardOf(PlanItem item) {
        return item.getAggregatedReward() != null
                ? item.getAggregatedReward().doubleValue()
                : 0.0;
    }

    /**
     * Computes total tokens consumed across all DONE items in the plan.
     * Used to convert Shapley fractions into token-equivalent credits.
     */
    private long computePlanTotalTokens(List<PlanItem> items) {
        // Sum tokens from the token ledger debits for this plan would be ideal,
        // but we approximate from item results to avoid circular dependency.
        // The debit sum is accessible via TokenLedgerService.sumDebits() but
        // we use plan items directly here for simplicity.
        return items.stream()
                .mapToLong(item -> {
                    if (item.getResult() == null) return 0;
                    try {
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        var root = mapper.readTree(item.getResult());
                        long v = root.path("provenance").path("tokenUsage")
                                .path("totalTokens").asLong(-1);
                        if (v >= 0) return v;
                        v = root.path("provenance").path("totalTokens").asLong(-1);
                        if (v >= 0) return v;
                        v = root.path("tokenUsage").path("totalTokens").asLong(-1);
                        return Math.max(v, 0);
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .sum();
    }

    /**
     * Records Shapley-derived credits in the token ledger for infrastructure workers
     * that wouldn't normally earn credits under the standard #33 credit system.
     */
    private void recordShapleyCredits(UUID planId, List<PlanItem> items,
                                      Map<String, Double> shapleyValues,
                                      long planTotalTokens) {
        for (PlanItem item : items) {
            Double sv = shapleyValues.get(item.getTaskKey());
            if (sv == null || sv <= 0) continue;

            // Only record Shapley credit for workers NOT already credit-eligible in #33
            if (TokenLedgerService.isCreditEligible(item.getWorkerType().name())) continue;

            tokenLedgerService.creditShapley(
                    planId, item.getId(), item.getTaskKey(),
                    item.getWorkerType().name(), sv, planTotalTokens);
        }
    }
}
