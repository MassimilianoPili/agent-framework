package com.agentframework.orchestrator.domain;

import java.util.Set;

/**
 * Status of a PlanItem with codified legal transitions.
 *
 * <p>Transition graph:</p>
 * <pre>
 * WAITING            → DISPATCHED, FAILED, AWAITING_APPROVAL (high-risk tasks)
 * AWAITING_APPROVAL  → WAITING (approved), FAILED (rejected / timeout)
 * DISPATCHED         → RUNNING, DONE, FAILED, WAITING (context retry loop)
 * RUNNING            → DONE, FAILED
 * DONE               → WAITING (ralph-loop), TO_DISPATCH (manual redispatch)
 * FAILED             → WAITING (auto-retry), TO_DISPATCH (manual redispatch)
 * TO_DISPATCH        → DISPATCHED (operator-initiated, bypasses dependency resolution)
 * </pre>
 */
public enum ItemStatus {

    WAITING {
        @Override public Set<ItemStatus> allowedTransitions() {
            return Set.of(DISPATCHED, FAILED, AWAITING_APPROVAL);
        }
    },

    /**
     * The task has been classified as high-risk ({@link com.agentframework.common.policy.RiskLevel#CRITICAL})
     * by the HOOK_MANAGER. Dispatch is suspended until a human approves via
     * {@code POST .../approve} or rejects via {@code POST .../reject}.
     * <ul>
     *   <li>Approved → WAITING (re-enters the dispatch queue)</li>
     *   <li>Rejected → FAILED</li>
     * </ul>
     */
    AWAITING_APPROVAL {
        @Override public Set<ItemStatus> allowedTransitions() {
            return Set.of(WAITING, FAILED);
        }
    },

    DISPATCHED {
        @Override public Set<ItemStatus> allowedTransitions() {
            // WAITING is allowed for the missing_context feedback loop:
            // a dispatched task reports missing context → re-queued after CM resolution.
            return Set.of(RUNNING, DONE, FAILED, WAITING);
        }
    },
    RUNNING {
        @Override public Set<ItemStatus> allowedTransitions() {
            return Set.of(DONE, FAILED);
        }
    },
    DONE {
        @Override public Set<ItemStatus> allowedTransitions() {
            // WAITING: ralph-loop (quality gate failure re-queues with feedback)
            // TO_DISPATCH: manual redispatch (operator override, bypasses dependency resolution)
            return Set.of(WAITING, TO_DISPATCH);
        }
    },
    FAILED {
        @Override public Set<ItemStatus> allowedTransitions() {
            // WAITING: auto-retry with backoff (via AutoRetryScheduler)
            // TO_DISPATCH: manual redispatch (operator override, bypasses dependency resolution)
            return Set.of(WAITING, TO_DISPATCH);
        }
    },

    /**
     * Operator-initiated redispatch. The item will be dispatched directly to a worker
     * without re-checking dependency resolution. Used for manual retry after root-cause
     * fix, or re-running a completed task with different parameters.
     * <p>Reachable from: FAILED, DONE (via {@code POST .../redispatch})</p>
     * <p>Exits to: DISPATCHED (via poller or immediate dispatch)</p>
     */
    TO_DISPATCH {
        @Override public Set<ItemStatus> allowedTransitions() {
            return Set.of(DISPATCHED);
        }
    };

    /** Returns the set of statuses this status can legally transition to. */
    public abstract Set<ItemStatus> allowedTransitions();

    /** Returns true if transitioning to the given target is legal. */
    public boolean canTransitionTo(ItemStatus target) {
        return allowedTransitions().contains(target);
    }
}
