package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.domain.Plan;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanResponse(
    UUID id,
    String status,
    String spec,
    Instant createdAt,
    Instant completedAt,
    String failureReason,
    List<PlanItemResponse> items
) {

    public static PlanResponse from(Plan plan) {
        List<PlanItemResponse> itemResponses = plan.getItems().stream()
            .map(PlanItemResponse::from)
            .toList();

        return new PlanResponse(
            plan.getId(),
            plan.getStatus().name(),
            plan.getSpec(),
            plan.getCreatedAt(),
            plan.getCompletedAt(),
            plan.getFailureReason(),
            itemResponses
        );
    }
}
