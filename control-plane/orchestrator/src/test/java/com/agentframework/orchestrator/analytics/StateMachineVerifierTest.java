package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.ItemStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compile-time model checking of the {@link ItemStatus} state machine (#38).
 *
 * <p>These tests exhaustively verify P1-P6 properties over the entire reachable
 * state space. A failing test means the state machine has a structural bug
 * (deadlock, unbounded loop, or broken terminal guarantee).</p>
 */
@DisplayName("StateMachineVerifier (#38) — compile-time model checking")
class StateMachineVerifierTest {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_MAX_RALPH_LOOPS = 2;

    private StateMachineVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new StateMachineVerifier(DEFAULT_MAX_RETRIES, DEFAULT_MAX_RALPH_LOOPS);
    }

    // ── Full verification ──────────────────────────────────────────────────────

    @Test
    @DisplayName("All properties P1-P6 are satisfied with default bounds")
    void allProperties_satisfied() {
        StateMachineVerificationResult result = verifier.verify();

        assertThat(result.allSatisfied()).isTrue();
        assertThat(result.properties()).hasSize(6);
        assertThat(result.properties())
                .allMatch(StateMachineVerificationResult.PropertyResult::satisfied,
                        "All properties should be satisfied");
    }

    // ── P1: no deadlock from DISPATCHED ────────────────────────────────────────

    @Test
    @DisplayName("P1: every DISPATCHED state reaches a terminal (DONE, FAILED, or CANCELLED)")
    void noDeadlockFromDispatched_P1() {
        StateMachineVerificationResult result = verifier.verify();

        StateMachineVerificationResult.PropertyResult p1 = findProperty(result, "P1");
        assertThat(p1.satisfied()).isTrue();
        assertThat(p1.description()).contains("DISPATCHED");
    }

    // ── P2: bounded retry ──────────────────────────────────────────────────────

    @Test
    @DisplayName("P2: retryCount never exceeds maxRetries in any reachable state")
    void retryBounded_P2() {
        StateMachineVerificationResult result = verifier.verify();

        StateMachineVerificationResult.PropertyResult p2 = findProperty(result, "P2");
        assertThat(p2.satisfied()).isTrue();

        // Also verify directly: no reachable state has retryCount > maxRetries
        Set<StateMachineVerifier.State> reachable = verifier.exploreReachable();
        assertThat(reachable)
                .noneMatch(s -> s.retryCount() > DEFAULT_MAX_RETRIES);
    }

    // ── P3: bounded ralph-loop ─────────────────────────────────────────────────

    @Test
    @DisplayName("P3: ralphLoopCount never exceeds maxRalphLoops in any reachable state")
    void ralphLoopBounded_P3() {
        StateMachineVerificationResult result = verifier.verify();

        StateMachineVerificationResult.PropertyResult p3 = findProperty(result, "P3");
        assertThat(p3.satisfied()).isTrue();

        Set<StateMachineVerifier.State> reachable = verifier.exploreReachable();
        assertThat(reachable)
                .noneMatch(s -> s.ralphLoopCount() > DEFAULT_MAX_RALPH_LOOPS);
    }

    // ── P4: CANCELLED is terminal ──────────────────────────────────────────────

    @Test
    @DisplayName("P4: CANCELLED has zero outgoing transitions")
    void cancelledIsTerminal_P4() {
        StateMachineVerificationResult result = verifier.verify();

        StateMachineVerificationResult.PropertyResult p4 = findProperty(result, "P4");
        assertThat(p4.satisfied()).isTrue();

        // Structural verification on enum
        assertThat(ItemStatus.CANCELLED.allowedTransitions()).isEmpty();
    }

    // ── P5: CANCELLED never reaches DISPATCHED ─────────────────────────────────

    @Test
    @DisplayName("P5: from CANCELLED, DISPATCHED is unreachable")
    void cancelledNeverReachesDispatched_P5() {
        StateMachineVerificationResult result = verifier.verify();

        StateMachineVerificationResult.PropertyResult p5 = findProperty(result, "P5");
        assertThat(p5.satisfied()).isTrue();
    }

    // ── P6: approval always resolves ───────────────────────────────────────────

    @Test
    @DisplayName("P6: every AWAITING_APPROVAL state reaches WAITING or FAILED")
    void approvalAlwaysResolves_P6() {
        StateMachineVerificationResult result = verifier.verify();

        StateMachineVerificationResult.PropertyResult p6 = findProperty(result, "P6");
        assertThat(p6.satisfied()).isTrue();
    }

    // ── State space metrics ────────────────────────────────────────────────────

    @Test
    @DisplayName("Reachable state count is within expected bounds")
    void reachableStateCount() {
        Set<StateMachineVerifier.State> reachable = verifier.exploreReachable();

        // With 8 statuses × 4 retry levels × 3 ralph levels = 96 max,
        // but not all combinations are reachable from (WAITING, 0, 0).
        // The reachable set should be > 1 (at least WAITING) and ≤ 96.
        assertThat(reachable.size()).isGreaterThan(1);
        assertThat(reachable.size()).isLessThanOrEqualTo(
                ItemStatus.values().length * (DEFAULT_MAX_RETRIES + 1) * (DEFAULT_MAX_RALPH_LOOPS + 1));

        // Initial state must be reachable
        assertThat(reachable).contains(
                new StateMachineVerifier.State(ItemStatus.WAITING, 0, 0));
    }

    // ── @AllowedViolation annotation ───────────────────────────────────────────

    @Test
    @DisplayName("DONE has @AllowedViolation for P4 (ralph-loop)")
    void doneToWaiting_allowedViolation() throws NoSuchFieldException {
        AllowedViolation annotation = ItemStatus.class.getField("DONE")
                .getAnnotation(AllowedViolation.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.property()).isEqualTo("P4");
        assertThat(annotation.reason()).contains("ralph-loop");
        assertThat(annotation.boundedBy()).contains("maxRalphLoops");
    }

    // ── Custom bounds change state space ───────────────────────────────────────

    @Test
    @DisplayName("Different bounds produce different reachable state counts")
    void customBounds_changeStateSpace() {
        StateMachineVerifier small = new StateMachineVerifier(1, 1);
        StateMachineVerifier large = new StateMachineVerifier(5, 4);

        Set<StateMachineVerifier.State> smallReachable = small.exploreReachable();
        Set<StateMachineVerifier.State> largeReachable = large.exploreReachable();

        // Larger bounds → more reachable states (more retry/ralph-loop levels)
        assertThat(largeReachable.size()).isGreaterThan(smallReachable.size());

        // Both should still satisfy all properties
        assertThat(small.verify().allSatisfied()).isTrue();
        assertThat(large.verify().allSatisfied()).isTrue();
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private StateMachineVerificationResult.PropertyResult findProperty(
            StateMachineVerificationResult result, String id) {
        return result.properties().stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property " + id + " not found in result"));
    }
}
