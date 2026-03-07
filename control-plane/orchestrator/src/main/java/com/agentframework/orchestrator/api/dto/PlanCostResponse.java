package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Cost breakdown for a plan: total tokens and estimated USD cost, with per-task detail (#26L1).
 */
public record PlanCostResponse(
    UUID planId,
    long totalInputTokens,
    long totalOutputTokens,
    BigDecimal totalEstimatedCostUsd,
    List<TaskCost> tasks
) {

    public record TaskCost(
        String taskKey,
        String workerType,
        Long inputTokens,
        Long outputTokens,
        BigDecimal estimatedCostUsd
    ) {}

    public static PlanCostResponse from(Plan plan) {
        long totalIn = 0;
        long totalOut = 0;
        BigDecimal totalCost = BigDecimal.ZERO;

        List<TaskCost> taskCosts = new java.util.ArrayList<>();
        for (PlanItem item : plan.getItems()) {
            long itemIn = item.getInputTokens() != null ? item.getInputTokens() : 0;
            long itemOut = item.getOutputTokens() != null ? item.getOutputTokens() : 0;
            totalIn += itemIn;
            totalOut += itemOut;
            if (item.getEstimatedCostUsd() != null) {
                totalCost = totalCost.add(item.getEstimatedCostUsd());
            }
            taskCosts.add(new TaskCost(
                    item.getTaskKey(),
                    item.getWorkerType().name(),
                    item.getInputTokens(),
                    item.getOutputTokens(),
                    item.getEstimatedCostUsd()));
        }

        return new PlanCostResponse(plan.getId(), totalIn, totalOut, totalCost, taskCosts);
    }
}
