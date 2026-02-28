package com.agentframework.worker.dto;

import com.agentframework.common.policy.HookPolicy;

import java.util.UUID;

/**
 * Mirrors the orchestrator's AgentTask record.
 * Shared JSON contract — same field names for seamless serialization.
 */
public record AgentTask(
    UUID planId,
    UUID itemId,
    String taskKey,
    String title,
    String description,
    String workerType,
    String workerProfile,
    String specSnippet,
    String contextJson,
    Integer attemptNumber,      // dispatch metadata (null if unknown)
    UUID dispatchAttemptId,     // dispatch metadata (null if unknown)
    UUID traceId,               // dispatch metadata (null if unknown)
    String dispatchedAt,        // ISO-8601, dispatch metadata (null if unknown)
    HookPolicy policy           // task-level hook policy set by HOOK_MANAGER (null = use static config)
) {}
