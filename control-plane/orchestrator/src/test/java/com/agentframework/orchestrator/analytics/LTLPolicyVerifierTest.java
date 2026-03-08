package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.eventsourcing.PlanEvent;
import com.agentframework.orchestrator.eventsourcing.PlanEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LTLPolicyVerifier}.
 *
 * <p>Covers null planId, empty trace, well-formed trace, S1/S2 safety violations,
 * L1/L2 liveness violations, and overallAdherence calculation.</p>
 */
@ExtendWith(MockitoExtension.class)
class LTLPolicyVerifierTest {

    @Mock private PlanEventRepository planEventRepository;

    private LTLPolicyVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new LTLPolicyVerifier(planEventRepository);
        ReflectionTestUtils.setField(verifier, "maxContextRequests", 3);
    }

    /** Creates a minimal PlanEvent mock. */
    private PlanEvent event(UUID itemId, String eventType) {
        PlanEvent e = mock(PlanEvent.class);
        when(e.getItemId()).thenReturn(itemId);
        when(e.getEventType()).thenReturn(eventType);
        return e;
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("null planId throws NullPointerException")
    void verify_nullPlanId_throws() {
        assertThatThrownBy(() -> verifier.verify(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Empty trace ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("empty trace → S1/S2/L2 satisfied, L1 violated (no completions)")
    void verify_emptyTrace_l1Violated() {
        UUID planId = UUID.randomUUID();
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId))
                .thenReturn(List.of());

        LTLPolicyVerifier.LTLVerificationReport report = verifier.verify(planId);

        assertThat(report.traceLength()).isZero();
        assertThat(report.formulaResults().get("S1_safety_dispatch_terminates")).isTrue();
        assertThat(report.formulaResults().get("S2_safety_order_preserved")).isTrue();
        assertThat(report.formulaResults().get("L1_liveness_at_least_one_completed")).isFalse();
        assertThat(report.formulaResults().get("L2_liveness_context_not_infinite")).isTrue();
        assertThat(report.violations()).hasSize(1);
        assertThat(report.overallAdherence()).isCloseTo(0.75, within(0.01));
    }

    // ── Well-formed trace ─────────────────────────────────────────────────────

    @Test
    @DisplayName("well-formed trace (dispatch → complete) → all 4 formulae satisfied")
    void verify_wellFormedTrace_allSatisfied() {
        UUID planId = UUID.randomUUID();
        UUID item1  = UUID.randomUUID();
        UUID item2  = UUID.randomUUID();

        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(
                event(item1, "TASK_DISPATCHED"),
                event(item1, "TASK_COMPLETED"),
                event(item2, "TASK_DISPATCHED"),
                event(item2, "TASK_COMPLETED")
        ));

        LTLPolicyVerifier.LTLVerificationReport report = verifier.verify(planId);

        assertThat(report.formulaResults()).allSatisfy((k, v) -> assertThat(v).isTrue());
        assertThat(report.violations()).isEmpty();
        assertThat(report.overallAdherence()).isEqualTo(1.0);
    }

    // ── S1 safety violation ───────────────────────────────────────────────────

    @Test
    @DisplayName("S1 violated: dispatched item never terminates")
    void verify_s1Violation_dispatchedNotTerminated() {
        UUID planId  = UUID.randomUUID();
        UUID item    = UUID.randomUUID();

        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(
                event(item, "TASK_DISPATCHED"),
                event(item, "TASK_COMPLETED")  // has a completed, but second item missing
        ));
        // Add a second item that's dispatched but never terminated
        UUID orphan = UUID.randomUUID();
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(
                event(item,   "TASK_DISPATCHED"),
                event(item,   "TASK_COMPLETED"),
                event(orphan, "TASK_DISPATCHED")  // dispatched, never terminates
        ));

        LTLPolicyVerifier.LTLVerificationReport report = verifier.verify(planId);

        assertThat(report.formulaResults().get("S1_safety_dispatch_terminates")).isFalse();
        assertThat(report.counterexamples()).containsKey("S1");
        assertThat(report.violations()).anyMatch(v -> v.contains("S1"));
        assertThat(report.overallAdherence()).isLessThan(1.0);
    }

    // ── S2 safety violation ───────────────────────────────────────────────────

    @Test
    @DisplayName("S2 violated: completed appears before dispatched (out-of-order)")
    void verify_s2Violation_completedBeforeDispatched() {
        UUID planId = UUID.randomUUID();
        UUID item   = UUID.randomUUID();

        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(
                event(item, "TASK_COMPLETED"),    // out-of-order: no prior DISPATCHED
                event(item, "TASK_DISPATCHED")
        ));

        LTLPolicyVerifier.LTLVerificationReport report = verifier.verify(planId);

        assertThat(report.formulaResults().get("S2_safety_order_preserved")).isFalse();
        assertThat(report.counterexamples()).containsKey("S2");
        assertThat(report.violations()).anyMatch(v -> v.contains("S2"));
    }

    // ── L2 liveness violation ─────────────────────────────────────────────────

    @Test
    @DisplayName("L2 violated: CONTEXT_REQUESTED exceeds maxContextRequests (3)")
    void verify_l2Violation_tooManyContextRequests() {
        UUID planId = UUID.randomUUID();
        UUID item   = UUID.randomUUID();

        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(
                event(item, "TASK_DISPATCHED"),
                event(item, "CONTEXT_REQUESTED"),
                event(item, "CONTEXT_REQUESTED"),
                event(item, "CONTEXT_REQUESTED"),
                event(item, "CONTEXT_REQUESTED"),  // 4th request > max=3
                event(item, "TASK_COMPLETED")
        ));

        LTLPolicyVerifier.LTLVerificationReport report = verifier.verify(planId);

        assertThat(report.formulaResults().get("L2_liveness_context_not_infinite")).isFalse();
        assertThat(report.counterexamples()).containsKey("L2");
        assertThat(report.violations()).anyMatch(v -> v.contains("L2"));
    }

    // ── Overall adherence ─────────────────────────────────────────────────────

    @Test
    @DisplayName("overallAdherence = satisfied formulae / 4")
    void verify_adherenceCalculation_correctFraction() {
        UUID planId = UUID.randomUUID();
        // Only L1 satisfied (has a completion), but no dispatches so S1 trivially satisfied too
        UUID item = UUID.randomUUID();
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(
                event(null, "TASK_COMPLETED")  // no itemId, counts for L1 global check
        ));

        LTLPolicyVerifier.LTLVerificationReport report = verifier.verify(planId);

        // 4 formulae: overallAdherence = satisfied/4
        assertThat(report.overallAdherence()).isBetween(0.0, 1.0);
        long satisfied = report.formulaResults().values().stream().filter(v -> v).count();
        assertThat(report.overallAdherence())
                .isCloseTo((double) satisfied / 4, within(0.001));
    }
}
