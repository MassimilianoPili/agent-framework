package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.domain.DispatchAttempt;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for DispatchAttempt — avoids exposing the JPA entity directly from the API layer.
 */
public record DispatchAttemptResponse(
    UUID id,
    int attemptNumber,
    Instant dispatchedAt,
    Instant completedAt,
    boolean success,
    String failureReason,
    Long durationMs
) {
    public static DispatchAttemptResponse from(DispatchAttempt a) {
        return new DispatchAttemptResponse(
            a.getId(),
            a.getAttemptNumber(),
            a.getDispatchedAt(),
            a.getCompletedAt(),
            a.isSuccess(),
            a.getFailureReason(),
            a.getDurationMs()
        );
    }
}
