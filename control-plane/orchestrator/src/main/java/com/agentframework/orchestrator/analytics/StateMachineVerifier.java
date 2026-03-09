package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.ItemStatus;

import java.util.*;

/**
 * Exhaustive model checker for the {@link ItemStatus} state machine (#38).
 *
 * <p>Performs BFS over the product state space {@code (ItemStatus, retryCount, ralphLoopCount)}
 * and verifies 6 temporal properties (P1-P6) at compile-time via JUnit tests.
 * The state space is small (~96 states with default bounds) and fully tractable.</p>
 *
 * <h3>Properties verified:</h3>
 * <ul>
 *   <li><b>P1</b>: □(DISPATCHED → ◇(DONE ∨ FAILED ∨ CANCELLED)) — no deadlock from DISPATCHED</li>
 *   <li><b>P2</b>: □(retryCount ≤ maxRetries) — bounded retry</li>
 *   <li><b>P3</b>: □(ralphLoopCount ≤ maxRalphLoops) — bounded quality loops</li>
 *   <li><b>P4</b>: CANCELLED has no outgoing transitions — terminal state</li>
 *   <li><b>P5</b>: □¬(CANCELLED → ◇ DISPATCHED) — CANCELLED never reaches DISPATCHED</li>
 *   <li><b>P6</b>: □(AWAITING_APPROVAL → ◇(WAITING ∨ FAILED)) — approval always resolves</li>
 * </ul>
 *
 * <p>Not a Spring {@code @Service} — used exclusively in compile-time verification tests.</p>
 */
public class StateMachineVerifier {

    /**
     * A state in the product state space: item status + bounded counters.
     */
    public record State(ItemStatus status, int retryCount, int ralphLoopCount) {}

    private final int maxRetries;
    private final int maxRalphLoops;

    public StateMachineVerifier(int maxRetries, int maxRalphLoops) {
        this.maxRetries = maxRetries;
        this.maxRalphLoops = maxRalphLoops;
    }

    /**
     * Runs exhaustive model checking: BFS from initial state, then verifies P1-P6.
     *
     * @return verification result with per-property outcomes and counterexamples
     */
    public StateMachineVerificationResult verify() {
        Set<State> reachable = exploreReachable();

        List<StateMachineVerificationResult.PropertyResult> results = List.of(
                checkP1(reachable),
                checkP2(reachable),
                checkP3(reachable),
                checkP4(),
                checkP5(reachable),
                checkP6(reachable)
        );

        return StateMachineVerificationResult.of(results, reachable.size());
    }

    /**
     * Returns all states reachable from the initial state (WAITING, 0, 0) via BFS.
     */
    public Set<State> exploreReachable() {
        State initial = new State(ItemStatus.WAITING, 0, 0);
        Set<State> visited = new LinkedHashSet<>();
        Deque<State> queue = new ArrayDeque<>();

        visited.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State current = queue.poll();
            for (State successor : successors(current)) {
                if (visited.add(successor)) {
                    queue.add(successor);
                }
            }
        }

        return Collections.unmodifiableSet(visited);
    }

    /**
     * Computes successor states for a given state, applying counter logic:
     * <ul>
     *   <li>FAILED → WAITING: retryCount++ (blocked if retryCount ≥ maxRetries)</li>
     *   <li>DONE → WAITING: ralphLoopCount++ (blocked if ralphLoopCount ≥ maxRalphLoops)</li>
     *   <li>All other transitions: counters unchanged</li>
     * </ul>
     */
    List<State> successors(State state) {
        List<State> result = new ArrayList<>();

        for (ItemStatus target : state.status().allowedTransitions()) {
            if (state.status() == ItemStatus.FAILED && target == ItemStatus.WAITING) {
                // Retry: increment retryCount, blocked if at max
                if (state.retryCount() < maxRetries) {
                    result.add(new State(target, state.retryCount() + 1, state.ralphLoopCount()));
                }
            } else if (state.status() == ItemStatus.DONE && target == ItemStatus.WAITING) {
                // Ralph-loop: increment ralphLoopCount, blocked if at max
                if (state.ralphLoopCount() < maxRalphLoops) {
                    result.add(new State(target, state.retryCount(), state.ralphLoopCount() + 1));
                }
            } else {
                result.add(new State(target, state.retryCount(), state.ralphLoopCount()));
            }
        }

        return result;
    }

    // ── Property checks ────────────────────────────────────────────────────────

    /**
     * P1: □(DISPATCHED → ◇(DONE ∨ FAILED ∨ CANCELLED))
     * From every reachable DISPATCHED state, a terminal state is reachable.
     */
    private StateMachineVerificationResult.PropertyResult checkP1(Set<State> reachable) {
        String desc = "□(DISPATCHED → ◇(DONE ∨ FAILED ∨ CANCELLED)) — no deadlock from DISPATCHED";

        for (State state : reachable) {
            if (state.status() == ItemStatus.DISPATCHED) {
                Set<State> forward = forwardReachable(state);
                boolean canTerminate = forward.stream().anyMatch(s -> isTerminalStatus(s.status()));
                if (!canTerminate) {
                    return StateMachineVerificationResult.PropertyResult.violated("P1", desc,
                            "Deadlock from " + state + ": no path to terminal state");
                }
            }
        }

        return StateMachineVerificationResult.PropertyResult.satisfied("P1", desc);
    }

    /**
     * P2: □(retryCount ≤ maxRetries)
     * No reachable state has retryCount exceeding maxRetries.
     */
    private StateMachineVerificationResult.PropertyResult checkP2(Set<State> reachable) {
        String desc = "□(retryCount ≤ maxRetries) — bounded retry";

        for (State state : reachable) {
            if (state.retryCount() > maxRetries) {
                return StateMachineVerificationResult.PropertyResult.violated("P2", desc,
                        "State " + state + " has retryCount=" + state.retryCount()
                                + " > maxRetries=" + maxRetries);
            }
        }

        return StateMachineVerificationResult.PropertyResult.satisfied("P2", desc);
    }

    /**
     * P3: □(ralphLoopCount ≤ maxRalphLoops)
     * No reachable state has ralphLoopCount exceeding maxRalphLoops.
     */
    private StateMachineVerificationResult.PropertyResult checkP3(Set<State> reachable) {
        String desc = "□(ralphLoopCount ≤ maxRalphLoops) — bounded quality loops";

        for (State state : reachable) {
            if (state.ralphLoopCount() > maxRalphLoops) {
                return StateMachineVerificationResult.PropertyResult.violated("P3", desc,
                        "State " + state + " has ralphLoopCount=" + state.ralphLoopCount()
                                + " > maxRalphLoops=" + maxRalphLoops);
            }
        }

        return StateMachineVerificationResult.PropertyResult.satisfied("P3", desc);
    }

    /**
     * P4: CANCELLED has no outgoing transitions (structural check on enum).
     */
    private StateMachineVerificationResult.PropertyResult checkP4() {
        String desc = "CANCELLED has no outgoing transitions — terminal state";

        Set<ItemStatus> transitions = ItemStatus.CANCELLED.allowedTransitions();
        if (!transitions.isEmpty()) {
            return StateMachineVerificationResult.PropertyResult.violated("P4", desc,
                    "CANCELLED has outgoing transitions: " + transitions);
        }

        return StateMachineVerificationResult.PropertyResult.satisfied("P4", desc);
    }

    /**
     * P5: □¬(CANCELLED → ◇ DISPATCHED)
     * From any reachable CANCELLED state, DISPATCHED is unreachable.
     */
    private StateMachineVerificationResult.PropertyResult checkP5(Set<State> reachable) {
        String desc = "□¬(CANCELLED → ◇ DISPATCHED) — CANCELLED never reaches DISPATCHED";

        for (State state : reachable) {
            if (state.status() == ItemStatus.CANCELLED) {
                Set<State> forward = forwardReachable(state);
                boolean reachesDispatched = forward.stream()
                        .anyMatch(s -> s.status() == ItemStatus.DISPATCHED);
                if (reachesDispatched) {
                    return StateMachineVerificationResult.PropertyResult.violated("P5", desc,
                            "DISPATCHED reachable from " + state);
                }
            }
        }

        return StateMachineVerificationResult.PropertyResult.satisfied("P5", desc);
    }

    /**
     * P6: □(AWAITING_APPROVAL → ◇(WAITING ∨ FAILED))
     * From every reachable AWAITING_APPROVAL state, WAITING or FAILED is reachable.
     */
    private StateMachineVerificationResult.PropertyResult checkP6(Set<State> reachable) {
        String desc = "□(AWAITING_APPROVAL → ◇(WAITING ∨ FAILED)) — approval always resolves";

        for (State state : reachable) {
            if (state.status() == ItemStatus.AWAITING_APPROVAL) {
                Set<State> forward = forwardReachable(state);
                boolean canResolve = forward.stream().anyMatch(s ->
                        s.status() == ItemStatus.WAITING || s.status() == ItemStatus.FAILED);
                if (!canResolve) {
                    return StateMachineVerificationResult.PropertyResult.violated("P6", desc,
                            "No path to WAITING or FAILED from " + state);
                }
            }
        }

        return StateMachineVerificationResult.PropertyResult.satisfied("P6", desc);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * BFS from a given state, returning all states reachable from it (inclusive).
     */
    private Set<State> forwardReachable(State from) {
        Set<State> visited = new HashSet<>();
        Deque<State> queue = new ArrayDeque<>();

        visited.add(from);
        queue.add(from);

        while (!queue.isEmpty()) {
            State current = queue.poll();
            for (State successor : successors(current)) {
                if (visited.add(successor)) {
                    queue.add(successor);
                }
            }
        }

        return visited;
    }

    /**
     * Returns true if the status is a terminal state (DONE, FAILED, or CANCELLED).
     * DONE and FAILED have outgoing edges (retry/ralph-loop) but are considered
     * "terminable" — they represent completed/failed work, even if bounded loops
     * may re-queue them.
     */
    private static boolean isTerminalStatus(ItemStatus status) {
        return status == ItemStatus.DONE
                || status == ItemStatus.FAILED
                || status == ItemStatus.CANCELLED;
    }
}
