package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.graph.CriticalPathCalculator;
import com.agentframework.orchestrator.graph.TropicalScheduler;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MarketMakingDispatcher} — market-making dispatch prioritization.
 */
@ExtendWith(MockitoExtension.class)
class MarketMakingDispatcherTest {

    @Mock
    private PlanItemRepository planItemRepository;

    @Mock
    private CriticalPathCalculator criticalPathCalculator;

    private MarketMakingDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new MarketMakingDispatcher(planItemRepository, criticalPathCalculator);
        ReflectionTestUtils.setField(dispatcher, "targetMultiplier", 2.0);
        ReflectionTestUtils.setField(dispatcher, "decayThresholdHours", 2.0);
    }

    @Test
    void prioritize_cpItemFirst() {
        Plan plan = new Plan(UUID.randomUUID(), "test");
        PlanItem cpItem = new PlanItem(UUID.randomUUID(), 0, "BE-001", "CP task",
                "On critical path", WorkerType.BE, "be-java", List.of());
        PlanItem normalItem = new PlanItem(UUID.randomUUID(), 1, "FE-001", "Normal task",
                "Not on critical path", WorkerType.FE, "fe-react", List.of());

        // Setup: BE-001 is on critical path
        when(criticalPathCalculator.computeSchedule(any())).thenReturn(
                new TropicalScheduler.ScheduleResult(
                        Map.of("BE-001", 0.0, "FE-001", 0.0),
                        Map.of("BE-001", 0.0, "FE-001", 100.0),
                        Map.of("BE-001", 0.0, "FE-001", 100.0),
                        List.of("BE-001"),
                        300000.0
                ));
        when(planItemRepository.countDispatchedByWorkerType()).thenReturn(List.of());
        when(planItemRepository.countCompletedAfterByWorkerType(any())).thenReturn(List.of());

        List<PlanItem> result = dispatcher.prioritize(List.of(normalItem, cpItem), plan);

        // CP item should be first (higher priority due to cpBonus=2.0)
        assertThat(result.get(0).getTaskKey()).isEqualTo("BE-001");
    }

    @Test
    void prioritize_emptyList_returnsEmpty() {
        Plan plan = new Plan(UUID.randomUUID(), "test");

        List<PlanItem> result = dispatcher.prioritize(List.of(), plan);

        assertThat(result).isEmpty();
    }
}
