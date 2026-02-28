package com.agentframework.orchestrator.messaging.dto;

import java.util.UUID;

public record AgentResult(
    UUID planId,
    UUID itemId,
    String taskKey,
    boolean success,
    String resultJson,
    String failureReason,
    long durationMs,
    String workerType,      // provenance (flat, kept for backward compat)
    String workerProfile,   // provenance (flat, kept for backward compat)
    String modelId,         // reserved (null)
    String promptHash,      // reserved (null)
    Provenance provenance,  // nested, nullable — supercedes flat fields
    Long tokensUsed         // actual tokens consumed by this task (null if worker doesn't report)
) {}
