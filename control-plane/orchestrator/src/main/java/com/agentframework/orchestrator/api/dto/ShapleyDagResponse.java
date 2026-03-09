package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.domain.PlanItem;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for DAG-aware Shapley value attribution (#40).
 *
 * @param planId               the plan UUID
 * @param grandCoalitionValue  v(N) — sum of all aggregatedReward
 * @param taskCount            number of DONE tasks in the computation
 * @param sampleCount          Monte Carlo samples used
 * @param efficiencyCheck      true if Σφᵢ ≈ v(N) within tolerance
 * @param tasks                per-task Shapley attribution
 */
public record ShapleyDagResponse(
        UUID planId,
        double grandCoalitionValue,
        int taskCount,
        int sampleCount,
        boolean efficiencyCheck,
        List<TaskShapley> tasks
) {

    /**
     * Per-task Shapley attribution detail.
     *
     * @param taskKey          task identifier (e.g. "BE-001")
     * @param workerType       worker type name
     * @param workerProfile    worker profile (nullable)
     * @param aggregatedReward direct reward from RewardComputationService
     * @param shapleyValue     DAG-aware Shapley value (φᵢ)
     * @param shapleyFraction  φᵢ / v(N) — proportion of total value attributed
     */
    public record TaskShapley(
            String taskKey,
            String workerType,
            String workerProfile,
            double aggregatedReward,
            double shapleyValue,
            double shapleyFraction
    ) {
        /** Factory from PlanItem with precomputed Shapley values. */
        public static TaskShapley from(PlanItem item, double shapleyValue,
                                       double grandCoalitionValue) {
            double reward = item.getAggregatedReward() != null
                    ? item.getAggregatedReward().doubleValue() : 0.0;
            double fraction = grandCoalitionValue > 0
                    ? shapleyValue / grandCoalitionValue : 0.0;
            return new TaskShapley(
                    item.getTaskKey(),
                    item.getWorkerType().name(),
                    item.getWorkerProfile(),
                    reward,
                    shapleyValue,
                    fraction
            );
        }
    }

    /** Builds a response from plan items and computed Shapley values. */
    public static ShapleyDagResponse from(UUID planId, List<PlanItem> doneItems,
                                           Map<String, Double> shapleyValues,
                                           int sampleCount) {
        double gcv = doneItems.stream()
                .mapToDouble(i -> i.getAggregatedReward() != null
                        ? i.getAggregatedReward().doubleValue() : 0.0)
                .sum();

        double shapleySum = shapleyValues.values().stream()
                .mapToDouble(Double::doubleValue).sum();
        boolean efficient = Math.abs(shapleySum - gcv) < 1e-6;

        List<TaskShapley> tasks = doneItems.stream()
                .map(item -> TaskShapley.from(item,
                        shapleyValues.getOrDefault(item.getTaskKey(), 0.0), gcv))
                .toList();

        return new ShapleyDagResponse(planId, gcv, doneItems.size(),
                sampleCount, efficient, tasks);
    }
}
