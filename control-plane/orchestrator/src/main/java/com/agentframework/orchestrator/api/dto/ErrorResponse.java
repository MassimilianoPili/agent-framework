package com.agentframework.orchestrator.api.dto;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
    String code,
    String message,
    String traceId,
    Instant timestamp,
    List<String> details
) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null, Instant.now(), List.of());
    }

    public static ErrorResponse of(String code, String message, List<String> details) {
        return new ErrorResponse(code, message, null, Instant.now(), details);
    }
}
