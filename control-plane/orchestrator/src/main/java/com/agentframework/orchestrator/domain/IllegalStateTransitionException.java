package com.agentframework.orchestrator.domain;

/**
 * Thrown when an illegal state transition is attempted on a Plan or PlanItem.
 * For example, transitioning from DONE back to WAITING.
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String entityType, Object id, Enum<?> from, Enum<?> to) {
        super("%s %s cannot transition from %s to %s".formatted(entityType, id, from, to));
    }
}
