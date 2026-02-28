package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.domain.PlanSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for PlanSnapshot listing — omits the large planData blob to keep responses lightweight.
 */
public record PlanSnapshotResponse(
    UUID id,
    UUID planId,
    String label,
    Instant createdAt
) {
    public static PlanSnapshotResponse from(PlanSnapshot s) {
        return new PlanSnapshotResponse(
            s.getId(),
            s.getPlan().getId(),
            s.getLabel(),
            s.getCreatedAt()
        );
    }
}
