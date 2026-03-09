package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.crypto.WorkerKey;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a registered worker key (#31).
 */
public record WorkerKeyDto(
        UUID id,
        String workerType,
        String workerProfile,
        String publicKeyBase64,
        Instant registeredAt,
        Instant lastSeenAt,
        boolean disabled
) {
    public static WorkerKeyDto from(WorkerKey entity) {
        return new WorkerKeyDto(
                entity.getId(),
                entity.getWorkerType(),
                entity.getWorkerProfile(),
                entity.getPublicKeyBase64(),
                entity.getRegisteredAt(),
                entity.getLastSeenAt(),
                entity.isDisabled()
        );
    }
}
