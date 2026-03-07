package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CriticalityMonitor}.
 *
 * <p>Uses Mockito to mock {@link PlanItemRepository} and {@link ApplicationEventPublisher},
 * and {@link ReflectionTestUtils} to inject {@code @Value} fields.
 */
@ExtendWith(MockitoExtension.class)
class CriticalityMonitorTest {

    @Mock
    private PlanItemRepository planItemRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CriticalityMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new CriticalityMonitor(planItemRepository, eventPublisher);
        ReflectionTestUtils.setField(monitor, "targetInventory", 5);
        ReflectionTestUtils.setField(monitor, "staleTimeoutMinutes", 30);
        ReflectionTestUtils.setField(monitor, "enabled", true);
    }

    @Test
    void computeLoads_noItems_returnsEmpty() {
        when(planItemRepository.countPendingByWorkerType()).thenReturn(Collections.emptyList());
        when(planItemRepository.countFailedByWorkerType()).thenReturn(Collections.emptyList());
        when(planItemRepository.countStaleDispatchedByWorkerType(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        var loads = monitor.computeLoads();

        assertThat(loads).isEmpty();
    }

    @Test
    void computeLoads_pendingAndFailed_correctWeights() {
        // 3 pending BE + 2 failed BE → load = 3 + 2*2 = 7.0
        when(planItemRepository.countPendingByWorkerType())
                .thenReturn(List.<Object[]>of(new Object[]{WorkerType.BE, 3L}));
        when(planItemRepository.countFailedByWorkerType())
                .thenReturn(List.<Object[]>of(new Object[]{WorkerType.BE, 2L}));
        when(planItemRepository.countStaleDispatchedByWorkerType(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        var loads = monitor.computeLoads();

        assertThat(loads).containsEntry("BE", 7.0);
    }

    @Test
    void evaluate_stable_noEventPublished() {
        // load BE = 3 (pending), threshold = 15 → C = 3/15 = 0.2 → STABLE
        when(planItemRepository.countPendingByWorkerType())
                .thenReturn(List.<Object[]>of(new Object[]{WorkerType.BE, 3L}));
        when(planItemRepository.countFailedByWorkerType())
                .thenReturn(Collections.emptyList());
        when(planItemRepository.countStaleDispatchedByWorkerType(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        monitor.evaluate();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void evaluate_warning_loggedButNoEvent() {
        // load BE = 10 (pending), threshold = 15 → C = 10/15 ≈ 0.667 → WARNING
        when(planItemRepository.countPendingByWorkerType())
                .thenReturn(List.<Object[]>of(new Object[]{WorkerType.BE, 10L}));
        when(planItemRepository.countFailedByWorkerType())
                .thenReturn(Collections.emptyList());
        when(planItemRepository.countStaleDispatchedByWorkerType(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        monitor.evaluate();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void evaluate_alert_publishesSystemCriticalityEvent() {
        // load BE = 5 pending + 5 failed → 5 + 5*2 = 15, threshold = 15 → C = 1.0 → ALERT
        when(planItemRepository.countPendingByWorkerType())
                .thenReturn(List.<Object[]>of(new Object[]{WorkerType.BE, 5L}));
        when(planItemRepository.countFailedByWorkerType())
                .thenReturn(List.<Object[]>of(new Object[]{WorkerType.BE, 5L}));
        when(planItemRepository.countStaleDispatchedByWorkerType(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        monitor.evaluate();

        ArgumentCaptor<SpringPlanEvent> captor = ArgumentCaptor.forClass(SpringPlanEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        SpringPlanEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(SpringPlanEvent.SYSTEM_CRITICALITY);
        assertThat(event.planId()).isNull();
    }

    @Test
    void evaluate_disabled_doesNothing() {
        ReflectionTestUtils.setField(monitor, "enabled", false);

        monitor.evaluate();

        verifyNoInteractions(planItemRepository);
        verifyNoInteractions(eventPublisher);
    }
}
