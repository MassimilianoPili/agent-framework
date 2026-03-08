package com.agentframework.worker.dto;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors the orchestrator's AgentResult record.
 * Published to agent-results topic after task execution.
 */
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
    Long tokensUsed,        // actual tokens consumed (from provenance.tokenUsage.total, for budget tracking)
    String conversationLog,                    // G1: full LLM conversation JSON for post-mortem debugging (nullable)
    List<FileModificationEvent> fileModifications  // G3: file ops performed during this task (nullable)
) {}
