package com.agentframework.orchestrator.messaging.dto;

import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.common.policy.HookPolicy;

import java.util.List;
import java.util.UUID;

public record AgentTask(
    UUID planId,
    UUID itemId,
    String taskKey,
    String title,
    String description,
    WorkerType workerType,
    String workerProfile,
    String specSnippet,
    String contextJson,
    Integer attemptNumber,      // dispatch metadata (null if unknown)
    UUID dispatchAttemptId,     // dispatch metadata (null if unknown)
    UUID traceId,               // dispatch metadata (null if unknown)
    String dispatchedAt,        // ISO-8601, dispatch metadata (null if unknown)
    HookPolicy policy,          // task-level hook policy set by HOOK_MANAGER (null = use static config)
    String councilContext,      // JSON CouncilReport from pre-planning session (null if council disabled)
    List<String> dynamicOwnsPaths, // project-path-resolved ownsPaths (merged with static in worker, null = none)
    List<String> toolHints        // planner-suggested MCP tool names (null = no suggestion, use worker default)
) {}
