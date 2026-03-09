package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.config.CriticalityProperties;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.orchestrator.metrics.OrchestratorMetrics;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CriticalityMonitor} (#56).
 *
 * <p>Uses Mockito to mock {@link PlanItemRepository}, {@link ApplicationEventPublisher},
 * and {@link OrchestratorMetrics}. {@link CriticalityProperties} is instantiated directly
 * (no ReflectionTestUtils needed for config fields).</p>
 */
@ExtendWith(MockitoExtension.class)
class CriticalityMonitorTest {

    @Mock
    private PlanItemRepository planItemRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OrchestratorMetrics metrics;

    private CriticalityMonitor monitor;

    private static final CriticalityProperties DEFAULT_PROPS =
            new CriticalityProperties(true, 30_000L, 5, 0.3, 50, 0.5, 0.8);

    @BeforeEach
    void setUp() {
        monitor = new CriticalityMonitor(planItemRepository, eventPublisher, metrics, DEFAULT_PROPS);
        ReflectionTestUtils.setField(monitor, "staleTimeoutMinutes", 30);
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
        verify(metrics).recordCriticalityIndex(anyDouble());
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
        verify(metrics).recordCriticalityIndex(anyDouble());
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
        CriticalityProperties disabledProps = new CriticalityProperties(
                false, 30_000L, 5, 0.3, 50, 0.5, 0.8);
        monitor = new CriticalityMonitor(planItemRepository, eventPublisher, metrics, disabledProps);

        monitor.evaluate();

        verifyNoInteractions(planItemRepository);
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(metrics);
    }

    @Test
    void computeSnapshot_emptyLoads_returnsStableSnapshot() {
        when(planItemRepository.countPendingByWorkerType()).thenReturn(Collections.emptyList());
        when(planItemRepository.countFailedByWorkerType()).thenReturn(Collections.emptyList());
        when(planItemRepository.countStaleDispatchedByWorkerType(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        CriticalitySnapshot snapshot = monitor.computeSnapshot();

        assertThat(snapshot.criticalityIndex()).isEqualTo(0.0);
        assertThat(snapshot.level()).isEqualTo("STABLE");
        assertThat(snapshot.toppled()).isEmpty();
    }

    @Test
    void computeSnapshot_highLoad_topplesAndStabilises() {
        // BE: 20 pending → load = 20, threshold = 15 → topple
        // After topple: BE load = 20-15 = 5, spillover to REVIEW and CONTEXT_MANAGER
        // Post-stabilisation C is computed on the stabilised loads
        when(planItemRepository.countPendingByWorkerType())
                .thenReturn(List.<Object[]>of(new Object[]{WorkerType.BE, 20L}));
        when(planItemRepository.countFailedByWorkerType())
                .thenReturn(Collections.emptyList());
        when(planItemRepository.countStaleDispatchedByWorkerType(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        CriticalitySnapshot snapshot = monitor.computeSnapshot();

        // BE toppled during stabilisation
        assertThat(snapshot.toppled()).contains("BE");
        // Pre-stabilisation load was 20
        assertThat(snapshot.loads().get("BE")).isEqualTo(20.0);
        // Post-stabilisation BE load is reduced (20 - 15 = 5)
        assertThat(snapshot.loadsAfterStabilise().get("BE")).isLessThan(20.0);
    }

    @Test
    void evaluate_metricsRecorded_forEachWorkerType() {
        when(planItemRepository.countPendingByWorkerType())
                .thenReturn(List.<Object[]>of(
                        new Object[]{WorkerType.BE, 5L},
                        new Object[]{WorkerType.FE, 3L}));
        when(planItemRepository.countFailedByWorkerType())
                .thenReturn(Collections.emptyList());
        when(planItemRepository.countStaleDispatchedByWorkerType(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        monitor.evaluate();

        verify(metrics).recordCriticalityIndex(anyDouble());
        verify(metrics).recordWorkerLoad(eq("BE"), eq(5.0));
        verify(metrics).recordWorkerLoad(eq("FE"), eq(3.0));
    }
}
