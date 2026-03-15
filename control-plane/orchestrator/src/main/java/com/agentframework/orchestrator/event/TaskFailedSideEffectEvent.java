package com.agentframework.orchestrator.event;

import com.agentframework.orchestrator.messaging.dto.AgentResult;

import java.util.UUID;

/**
 * Published inside onTaskCompleted() when a task fails.
 * Consumed by {@code TaskFailedEventHandler} in AFTER_COMMIT phase
 * to run failure-specific side-effects (MAST classification, recovery routing, etc.).
 */
public record TaskFailedSideEffectEvent(
        UUID itemId,
        AgentResult result
) {}
