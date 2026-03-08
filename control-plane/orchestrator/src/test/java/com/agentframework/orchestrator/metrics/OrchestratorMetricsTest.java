package com.agentframework.orchestrator.metrics;

import com.agentframework.orchestrator.sse.SseEmitterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrchestratorMetrics}.
 *
 * Uses {@link SimpleMeterRegistry} — an in-memory registry with no Prometheus server
 * or Spring context required. {@code @PostConstruct} is not triggered by Mockito, so
 * {@link OrchestratorMetrics#init()} is called explicitly in {@link #setUp()}.
 */
@ExtendWith(MockitoExtension.class)
class OrchestratorMetricsTest {

    @Mock
    private SseEmitterRegistry sseEmitterRegistry;

    private SimpleMeterRegistry registry;
    private OrchestratorMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OrchestratorMetrics(registry, sseEmitterRegistry);
        // @PostConstruct does not fire in plain unit tests — call init() manually.
        metrics.init();
    }

    // ── Plan-level counters ──────────────────────────────────────────────────

    @Test
    void recordPlanCreated_incrementsCounter() {
        metrics.recordPlanCreated();
        metrics.recordPlanCreated();

        double count = registry.counter("orchestrator.plans.created").count();
        assertThat(count).isEqualTo(2.0);
    }

    @Test
    void recordPlanCompleted_and_failed_taggedCorrectly() {
        metrics.recordPlanCompleted();
        metrics.recordPlanCompleted();
        metrics.recordPlanFailed();

        Counter completed = registry.get("orchestrator.plans.completed")
                .tag("status", "COMPLETED")
                .counter();
        Counter failed = registry.get("orchestrator.plans.completed")
                .tag("status", "FAILED")
                .counter();

        assertThat(completed.count()).isEqualTo(2.0);
        assertThat(failed.count()).isEqualTo(1.0);
    }

    // ── Item-level counters ──────────────────────────────────────────────────

    @Test
    void recordItemDispatched_perWorkerType_separateCounters() {
        metrics.recordItemDispatched("BE");
        metrics.recordItemDispatched("BE");
        metrics.recordItemDispatched("FE");

        Counter beCounter = registry.get("orchestrator.items.dispatched")
                .tag("worker_type", "BE")
                .counter();
        Counter feCounter = registry.get("orchestrator.items.dispatched")
                .tag("worker_type", "FE")
                .counter();

        assertThat(beCounter.count()).isEqualTo(2.0);
        assertThat(feCounter.count()).isEqualTo(1.0);
    }

    // ── SSE gauge ───────────────────────────────────────────────────────────

    @Test
    void sseGauge_reflectsLiveConnectionCount() {
        when(sseEmitterRegistry.getActiveConnectionCount()).thenReturn(3);

        Gauge gauge = registry.get("orchestrator.sse.active.connections").gauge();

        // Gauge reads the live value at query time via the lambda registered in init().
        assertThat(gauge.value()).isEqualTo(3.0);
    }
}
