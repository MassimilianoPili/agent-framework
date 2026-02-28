package com.agentframework.orchestrator.messaging.dto;

import java.util.List;

/**
 * Structured execution provenance received from a worker result.
 * Mirrors worker-sdk Provenance record (same field order for JSON deserialization).
 */
public record Provenance(
    String workerType,
    String workerProfile,
    Integer attemptNumber,
    String dispatchAttemptId,   // UUID string
    String dispatchedAt,        // ISO-8601
    String completedAt,         // ISO-8601
    String traceId,             // UUID string
    String model,               // AI model identifier (null if unknown)
    List<String> toolsUsed,     // null if no tools called
    String promptHash,          // SHA-256 hex of system prompt
    String skillsHash,          // SHA-256 hex of concatenated skill content
    TokenUsage tokenUsage       // null if not captured
) {
    public record TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens) {}
}
