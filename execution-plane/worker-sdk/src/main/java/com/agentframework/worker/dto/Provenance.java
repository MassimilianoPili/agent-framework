package com.agentframework.worker.dto;

import java.util.List;

/**
 * Structured execution provenance for a task result.
 * Supercedes the flat workerType/workerProfile/modelId/promptHash fields in AgentResult.
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
    TokenUsage tokenUsage,      // null if not captured
    String reasoning            // first LLM text block before any tool call (max 2000 chars); null if not captured
) {
    public record TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens) {}
}
