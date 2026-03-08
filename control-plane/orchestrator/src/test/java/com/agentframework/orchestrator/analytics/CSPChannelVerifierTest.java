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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CSPChannelVerifier}.
 *
 * <p>Verifies protocol adherence, deadlock freedom detection, and liveness checks.</p>
 */
@ExtendWith(MockitoExtension.class)
class CSPChannelVerifierTest {

    @Mock private PlanEventRepository planEventRepository;

    private CSPChannelVerifier verifier;

    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        verifier = new CSPChannelVerifier(planEventRepository);
        ReflectionTestUtils.setField(verifier, "maxEventsPerPlan", 500);
    }

    /** Builds a PlanEvent with the given itemId and eventType (sequence n). */
    private PlanEvent makeEvent(UUID itemId, String eventType, long seq) {
        return new PlanEvent(UUID.randomUUID(), planId, itemId, eventType,
                "{}", Instant.ofEpochSecond(seq), seq);
    }

    @Test
    @DisplayName("returns null when no events exist for the plan")
    void verify_noEvents_returnsNull() {
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId))
                .thenReturn(List.of());

        assertThat(verifier.verify(planId)).isNull();
    }

    @Test
    @DisplayName("perfect protocol trace returns no violations and score 1.0")
    void verify_perfectTrace_noViolations() {
        UUID item = UUID.randomUUID();
        List<PlanEvent> events = List.of(
                makeEvent(item, "TASK_CREATED",    1),
                makeEvent(item, "TASK_DISPATCHED", 2),
                makeEvent(item, "TASK_STARTED",    3),
                makeEvent(item, "TASK_COMPLETED",  4)
        );
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId))
                .thenReturn(events);

        CSPChannelVerifier.CSPVerificationReport report = verifier.verify(planId);

        assertThat(report).isNotNull();
        assertThat(report.protocolViolations()).isEmpty();
        assertThat(report.livenessViolations()).isEmpty();
        assertThat(report.deadlockFreedom()).isTrue();
        assertThat(report.adherenceScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("out-of-order events are detected as protocol violations")
    void verify_outOfOrderEvents_protocolViolation() {
        UUID item = UUID.randomUUID();
        // STARTED before DISPATCHED — protocol violation
        List<PlanEvent> events = List.of(
                makeEvent(item, "TASK_CREATED",    1),
                makeEvent(item, "TASK_STARTED",    2),   // wrong: skipped DISPATCHED
                makeEvent(item, "TASK_DISPATCHED", 3),   // wrong: out of order
                makeEvent(item, "TASK_COMPLETED",  4)
        );
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId))
                .thenReturn(events);

        CSPChannelVerifier.CSPVerificationReport report = verifier.verify(planId);

        assertThat(report).isNotNull();
        assertThat(report.protocolViolations()).isNotEmpty();
        assertThat(report.adherenceScore()).isLessThan(1.0);
    }

    @Test
    @DisplayName("dispatched task with no terminal event is a liveness violation")
    void verify_dispatchedNoTerminal_livenessViolation() {
        UUID item = UUID.randomUUID();
        List<PlanEvent> events = List.of(
                makeEvent(item, "TASK_CREATED",    1),
                makeEvent(item, "TASK_DISPATCHED", 2),
                makeEvent(item, "TASK_STARTED",    3)
                // no COMPLETED or FAILED
        );
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId))
                .thenReturn(events);

        CSPChannelVerifier.CSPVerificationReport report = verifier.verify(planId);

        assertThat(report).isNotNull();
        assertThat(report.livenessViolations()).isNotEmpty();
        assertThat(report.livenessViolations().get(0)).contains(item.toString());
    }

    @Test
    @DisplayName("TASK_FAILED is a valid terminal event (no liveness violation)")
    void verify_taskFailed_validTerminal() {
        UUID item = UUID.randomUUID();
        List<PlanEvent> events = List.of(
                makeEvent(item, "TASK_CREATED",    1),
                makeEvent(item, "TASK_DISPATCHED", 2),
                makeEvent(item, "TASK_STARTED",    3),
                makeEvent(item, "TASK_FAILED",     4)
        );
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId))
                .thenReturn(events);

        CSPChannelVerifier.CSPVerificationReport report = verifier.verify(planId);

        assertThat(report).isNotNull();
        assertThat(report.livenessViolations()).isEmpty();
    }

    @Test
    @DisplayName("multiple items — only violating items reduce adherence score")
    void verify_multipleItems_partialAdherence() {
        UUID goodItem = UUID.randomUUID();
        UUID badItem  = UUID.randomUUID();

        List<PlanEvent> events = new ArrayList<>();
        // Good item: perfect trace
        events.add(makeEvent(goodItem, "TASK_CREATED",    1));
        events.add(makeEvent(goodItem, "TASK_DISPATCHED", 2));
        events.add(makeEvent(goodItem, "TASK_STARTED",    3));
        events.add(makeEvent(goodItem, "TASK_COMPLETED",  4));
        // Bad item: skipped DISPATCHED
        events.add(makeEvent(badItem, "TASK_CREATED",   5));
        events.add(makeEvent(badItem, "TASK_STARTED",   6));   // skip DISPATCHED
        events.add(makeEvent(badItem, "TASK_COMPLETED", 7));

        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId))
                .thenReturn(events);

        CSPChannelVerifier.CSPVerificationReport report = verifier.verify(planId);

        assertThat(report).isNotNull();
        // 1 out of 2 items violated → score = 0.5
        assertThat(report.adherenceScore()).isEqualTo(0.5);
    }
}
