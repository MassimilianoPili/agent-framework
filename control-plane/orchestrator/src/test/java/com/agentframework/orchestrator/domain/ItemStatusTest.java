package com.agentframework.orchestrator.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ItemStatus} transition rules.
 * Verifies the state machine graph, including the TO_DISPATCH operator override path.
 */
class ItemStatusTest {

    // ── TO_DISPATCH transitions ─────────────────────────────────────────────

    @Test
    void toDispatch_canTransitionTo_dispatched() {
        assertThat(ItemStatus.TO_DISPATCH.canTransitionTo(ItemStatus.DISPATCHED)).isTrue();
    }

    @Test
    void toDispatch_cannotTransitionTo_waiting() {
        assertThat(ItemStatus.TO_DISPATCH.canTransitionTo(ItemStatus.WAITING)).isFalse();
    }

    @Test
    void toDispatch_cannotTransitionTo_failed() {
        assertThat(ItemStatus.TO_DISPATCH.canTransitionTo(ItemStatus.FAILED)).isFalse();
    }

    @Test
    void toDispatch_cannotTransitionTo_done() {
        assertThat(ItemStatus.TO_DISPATCH.canTransitionTo(ItemStatus.DONE)).isFalse();
    }

    // ── Transitions INTO TO_DISPATCH ────────────────────────────────────────

    @Test
    void failed_canTransitionTo_toDispatch() {
        assertThat(ItemStatus.FAILED.canTransitionTo(ItemStatus.TO_DISPATCH)).isTrue();
    }

    @Test
    void done_canTransitionTo_toDispatch() {
        assertThat(ItemStatus.DONE.canTransitionTo(ItemStatus.TO_DISPATCH)).isTrue();
    }

    @Test
    void waiting_cannotTransitionTo_toDispatch() {
        assertThat(ItemStatus.WAITING.canTransitionTo(ItemStatus.TO_DISPATCH)).isFalse();
    }

    @Test
    void dispatched_cannotTransitionTo_toDispatch() {
        assertThat(ItemStatus.DISPATCHED.canTransitionTo(ItemStatus.TO_DISPATCH)).isFalse();
    }

    @Test
    void running_cannotTransitionTo_toDispatch() {
        assertThat(ItemStatus.RUNNING.canTransitionTo(ItemStatus.TO_DISPATCH)).isFalse();
    }

    @Test
    void awaitingApproval_cannotTransitionTo_toDispatch() {
        assertThat(ItemStatus.AWAITING_APPROVAL.canTransitionTo(ItemStatus.TO_DISPATCH)).isFalse();
    }

    // ── WAITING → DONE (operator skip) ──────────────────────────────────────

    @Test
    void waiting_canTransitionTo_done() {
        assertThat(ItemStatus.WAITING.canTransitionTo(ItemStatus.DONE)).isTrue();
    }

    // ── Existing transitions still work ─────────────────────────────────────

    @Test
    void failed_canStillTransitionTo_waiting() {
        assertThat(ItemStatus.FAILED.canTransitionTo(ItemStatus.WAITING)).isTrue();
    }

    @Test
    void done_canStillTransitionTo_waiting() {
        assertThat(ItemStatus.DONE.canTransitionTo(ItemStatus.WAITING)).isTrue();
    }

    @Test
    void toDispatch_allowedTransitions_containsOnlyDispatched() {
        assertThat(ItemStatus.TO_DISPATCH.allowedTransitions())
            .containsExactly(ItemStatus.DISPATCHED);
    }
}
