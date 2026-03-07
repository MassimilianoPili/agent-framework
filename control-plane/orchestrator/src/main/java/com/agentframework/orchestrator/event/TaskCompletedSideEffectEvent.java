package com.agentframework.orchestrator.event;

import com.agentframework.orchestrator.messaging.dto.AgentResult;

import java.util.UUID;

/**
 * Published inside onTaskCompleted() after the critical path (transition, save, dispatch)
 * has executed. Consumed by {@code TaskCompletedEventHandler} in an AFTER_COMMIT phase
 * to run non-critical side-effects (reward computation, GP update, serendipity, etc.)
 * outside the main transaction.
 *
 * <p>Carries the itemId (for re-loading the entity in a new transaction) and the
 * full AgentResult (immutable record, safe to hold across transaction boundaries).</p>
 */
public record TaskCompletedSideEffectEvent(
        UUID itemId,
        AgentResult result
) {}
