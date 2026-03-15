package com.agentframework.orchestrator.lifecycle;

import java.util.Set;

/**
 * Status of a Project with codified legal transitions.
 *
 * <p>Follows the same state machine pattern as {@code PlanStatus}.</p>
 *
 * <pre>
 * PLANNING     → ACTIVE, CANCELLED
 * ACTIVE       → STABILIZING, CANCELLED
 * STABILIZING  → RELEASED, ACTIVE (if stabilization fails), CANCELLED
 * RELEASED     → (terminal)
 * CANCELLED    → (terminal)
 * </pre>
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li><b>PLANNING</b>: epics being decomposed, plans not yet started</li>
 *   <li><b>ACTIVE</b>: plans executing via saga sequencer</li>
 *   <li><b>STABILIZING</b>: all forward plans complete, running stabilization checks</li>
 *   <li><b>RELEASED</b>: release assembled, notes generated — terminal</li>
 *   <li><b>CANCELLED</b>: explicitly cancelled — terminal</li>
 * </ul>
 */
public enum ProjectStatus {

    PLANNING {
        @Override public Set<ProjectStatus> allowedTransitions() {
            return Set.of(ACTIVE, CANCELLED);
        }
    },
    ACTIVE {
        @Override public Set<ProjectStatus> allowedTransitions() {
            return Set.of(STABILIZING, CANCELLED);
        }
    },
    STABILIZING {
        @Override public Set<ProjectStatus> allowedTransitions() {
            return Set.of(RELEASED, ACTIVE, CANCELLED);
        }
    },
    /** Terminal: project released successfully. */
    RELEASED {
        @Override public Set<ProjectStatus> allowedTransitions() {
            return Set.of();
        }
    },
    /** Terminal: project explicitly cancelled. */
    CANCELLED {
        @Override public Set<ProjectStatus> allowedTransitions() {
            return Set.of();
        }
    };

    public abstract Set<ProjectStatus> allowedTransitions();

    public boolean canTransitionTo(ProjectStatus target) {
        return allowedTransitions().contains(target);
    }
}
