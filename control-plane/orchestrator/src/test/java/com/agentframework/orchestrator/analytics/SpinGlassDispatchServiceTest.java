package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.SpinGlassDispatchService.DispatchOrderReport;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
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
 * Unit tests for {@link SpinGlassDispatchService}.
 *
 * <p>Verifies that the Simulated Annealing finds a valid dispatch ordering,
 * respects dependency constraints, and handles edge cases.</p>
 */
@ExtendWith(MockitoExtension.class)
class SpinGlassDispatchServiceTest {

    @Mock private PlanItemRepository planItemRepository;
    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private SpinGlassDispatchService service;

    @BeforeEach
    void setUp() {
        service = new SpinGlassDispatchService(planItemRepository, taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "maxIterations", 500);
        ReflectionTestUtils.setField(service, "coolingRate", 0.95);
    }

    private PlanItem makeItem(int ordinal, String taskKey, List<String> deps) {
        return new PlanItem(UUID.randomUUID(), ordinal, taskKey, "title", "desc",
                            WorkerType.BE, "be-java", deps, List.of());
    }

    @Test
    @DisplayName("optimise returns valid dispatch order for plan with dependencies")
    void optimise_withDependencies_returnsValidOrder() {
        UUID planId = UUID.randomUUID();

        List<PlanItem> items = List.of(
                makeItem(1, "T1", List.of()),
                makeItem(2, "T2", List.of("T1")),
                makeItem(3, "T3", List.of("T1")),
                makeItem(4, "T4", List.of("T2", "T3"))
        );

        when(planItemRepository.findByPlanId(planId)).thenReturn(items);

        DispatchOrderReport report = service.optimise(planId);

        assertThat(report).isNotNull();
        assertThat(report.optimalOrder()).hasSize(4);
        assertThat(report.optimalOrder()).containsExactlyInAnyOrder("T1", "T2", "T3", "T4");
        assertThat(report.iterations()).isEqualTo(500);
        assertThat(report.finalEnergy()).isGreaterThan(0.0);

        // Constraint: T1 must appear before T2 and T3
        List<String> order = report.optimalOrder();
        int posT1 = order.indexOf("T1");
        int posT2 = order.indexOf("T2");
        int posT3 = order.indexOf("T3");
        int posT4 = order.indexOf("T4");
        assertThat(posT1).isLessThan(posT2);
        assertThat(posT1).isLessThan(posT3);
        assertThat(posT2).isLessThan(posT4);
        assertThat(posT3).isLessThan(posT4);
    }

    @Test
    @DisplayName("optimise with single item returns null")
    void optimise_singleItem_returnsNull() {
        UUID planId = UUID.randomUUID();
        when(planItemRepository.findByPlanId(planId))
                .thenReturn(List.of(makeItem(1, "T1", List.of())));

        DispatchOrderReport report = service.optimise(planId);

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("optimise with independent tasks returns all tasks")
    void optimise_independentTasks_returnsAllTasks() {
        UUID planId = UUID.randomUUID();

        List<PlanItem> items = List.of(
                makeItem(1, "T1", List.of()),
                makeItem(2, "T2", List.of()),
                makeItem(3, "T3", List.of())
        );

        when(planItemRepository.findByPlanId(planId)).thenReturn(items);

        DispatchOrderReport report = service.optimise(planId);

        assertThat(report).isNotNull();
        assertThat(report.optimalOrder()).hasSize(3);
        assertThat(report.optimalOrder()).containsExactlyInAnyOrder("T1", "T2", "T3");
    }
}
