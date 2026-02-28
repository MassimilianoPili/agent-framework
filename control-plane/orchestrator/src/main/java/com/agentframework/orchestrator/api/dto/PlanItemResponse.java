package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.domain.PlanItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanItemResponse(
    UUID id,
    int ordinal,
    String taskKey,
    String title,
    String description,
    String workerType,
    String workerProfile,
    String status,
    List<String> dependsOn,
    Instant dispatchedAt,
    Instant completedAt,
    String failureReason,
    /**
     * Structured issue snapshot populated by the TASK_MANAGER worker.
     * Exposed as a raw JSON string; clients may parse it as needed.
     * Null if TASK_MANAGER has not yet run for this item.
     */
    String issueSnapshot,
    /**
     * Bayesian-weighted aggregate of reviewScore (0.50), processScore (0.30), and
     * qualityGateScore (0.20). Range: [-1.0, +1.0]. Null until at least one source available.
     */
    Float aggregatedReward,
    /**
     * JSON breakdown of individual reward source values and their effective weights.
     * Example: {"review":0.8,"process":0.6,"quality_gate":null,"weights":{"review":0.625,...}}
     * Null if no reward has been computed yet.
     */
    String rewardSources
) {

    public static PlanItemResponse from(PlanItem item) {
        return new PlanItemResponse(
            item.getId(),
            item.getOrdinal(),
            item.getTaskKey(),
            item.getTitle(),
            item.getDescription(),
            item.getWorkerType().name(),
            item.getWorkerProfile(),
            item.getStatus().name(),
            item.getDependsOn(),
            item.getDispatchedAt(),
            item.getCompletedAt(),
            item.getFailureReason(),
            item.getIssueSnapshot(),
            item.getAggregatedReward(),
            item.getRewardSources()
        );
    }
}
