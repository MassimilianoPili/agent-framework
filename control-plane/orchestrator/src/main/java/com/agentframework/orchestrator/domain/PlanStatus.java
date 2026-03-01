package com.agentframework.orchestrator.domain;

import java.util.Set;

/**
 * Status of a Plan with codified legal transitions.
 *
 * <pre>
 * PENDING → RUNNING
 * RUNNING → COMPLETED, FAILED, PAUSED
 * PAUSED  → RUNNING  (resume — re-enters the dispatch loop)
 * COMPLETED → RUNNING  (ralph-loop — quality gate triggers re-dispatch)
 * FAILED → RUNNING  (retry — reopens the plan)
 * </pre>
 */
public enum PlanStatus {

    PENDING {
        @Override public Set<PlanStatus> allowedTransitions() {
            return Set.of(RUNNING);
        }
    },
    RUNNING {
        @Override public Set<PlanStatus> allowedTransitions() {
            return Set.of(COMPLETED, FAILED, PAUSED);
        }
    },
    PAUSED {
        @Override public Set<PlanStatus> allowedTransitions() {
            return Set.of(RUNNING);
        }
    },
    COMPLETED {
        @Override public Set<PlanStatus> allowedTransitions() {
            // RUNNING is allowed for the ralph-loop: quality gate failure
            // reopens the plan to re-dispatch implicated items.
            return Set.of(RUNNING);
        }
    },
    FAILED {
        @Override public Set<PlanStatus> allowedTransitions() {
            return Set.of(RUNNING);
        }
    };

    public abstract Set<PlanStatus> allowedTransitions();

    public boolean canTransitionTo(PlanStatus target) {
        return allowedTransitions().contains(target);
    }
}
