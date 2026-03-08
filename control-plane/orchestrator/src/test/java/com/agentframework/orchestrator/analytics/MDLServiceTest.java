package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.MDLService.MDLReport;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MDLService}.
 *
 * <p>Verifies MDL computation for DAG structure, outcome encoding,
 * empty plan, and normalisation.</p>
 */
@ExtendWith(MockitoExtension.class)
class MDLServiceTest {

    @Mock private PlanItemRepository planItemRepository;
    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private MDLService service;

    @BeforeEach
    void setUp() {
        service = new MDLService(planItemRepository, taskOutcomeRepository);
    }

    private PlanItem makeItem(String taskKey, List<String> deps) {
        return new PlanItem(UUID.randomUUID(), 1, taskKey, "title", "desc",
                            WorkerType.BE, "be-java", deps, List.of());
    }

    private Object[] makeOutcome(String profile, double reward) {
        return new Object[]{profile, reward, "BE", "task-x"};
    }

    @Test
    @DisplayName("compute returns valid MDL report for plan with edges")
    void compute_withEdges_returnsReport() {
        UUID planId = UUID.randomUUID();

        List<PlanItem> items = List.of(
                makeItem("T1", List.of()),
                makeItem("T2", List.of("T1")),        // 1 edge
                makeItem("T3", List.of("T1", "T2"))   // 2 edges — total 3 edges
        );

        when(planItemRepository.findByPlanId(planId)).thenReturn(items);
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(
                List.of(makeOutcome("be-java", 0.8), makeOutcome("be-java", 0.7)));

        MDLReport report = service.compute(planId);

        assertThat(report).isNotNull();
        assertThat(report.numItems()).isEqualTo(3);
        assertThat(report.numEdges()).isEqualTo(3);
        assertThat(report.bitsForStructure()).isGreaterThan(0.0);
        // NLL can be negative when sigma2 is small (Gaussian PDF > 1 at peak)
        assertThat(report.bitsForOutcomes()).isFinite();
        assertThat(report.totalMDL()).isEqualTo(report.bitsForStructure() + report.bitsForOutcomes());
        assertThat(report.normalizedMDL()).isCloseTo(report.totalMDL() / 3.0, within(1e-9));
    }

    @Test
    @DisplayName("compute returns null for plan with no items")
    void compute_noItems_returnsNull() {
        UUID planId = UUID.randomUUID();
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of());

        MDLReport report = service.compute(planId);

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("compute plan with no edges has zero structural bits")
    void compute_noEdges_zeroStructureBits() {
        UUID planId = UUID.randomUUID();

        List<PlanItem> items = List.of(
                makeItem("T1", List.of()),
                makeItem("T2", List.of()));

        when(planItemRepository.findByPlanId(planId)).thenReturn(items);
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(List.of());

        MDLReport report = service.compute(planId);

        assertThat(report).isNotNull();
        assertThat(report.numEdges()).isEqualTo(0);
        assertThat(report.bitsForStructure()).isEqualTo(0.0);
        assertThat(report.totalMDL()).isGreaterThanOrEqualTo(0.0);
    }
}
