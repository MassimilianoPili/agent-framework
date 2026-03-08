package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.FunctorialSemanticsService.FunctorialReport;
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
 * Unit tests for {@link FunctorialSemanticsService}.
 *
 * <p>Verifies functor construction, natural transformation, compositionality check,
 * and edge cases (few items, missing outcomes).</p>
 */
@ExtendWith(MockitoExtension.class)
class FunctorialSemanticsServiceTest {

    @Mock private PlanItemRepository planItemRepository;
    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private FunctorialSemanticsService service;

    @BeforeEach
    void setUp() {
        service = new FunctorialSemanticsService(planItemRepository, taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "compositionalityThreshold", 0.3);
        ReflectionTestUtils.setField(service, "compositionalityCheck", true);
    }

    private PlanItem makeItem(UUID id, int ordinal, String taskKey, List<String> deps) {
        return new PlanItem(id, ordinal, taskKey, "title", "desc",
                            WorkerType.BE, "be-java", deps, List.of());
    }

    /** Creates a mock outcome list (as returned by findOutcomeByPlanItemId): [[id, gp_mu, actual_reward]]. */
    private List<Object[]> outcomeRow(double gpMu, double actualReward) {
        Object[] row = new Object[]{UUID.randomUUID(), gpMu, actualReward};
        List<Object[]> result = new ArrayList<>();
        result.add(row);
        return result;
    }

    @Test
    @DisplayName("compute returns functor map for items with outcomes")
    void compute_itemsWithOutcomes_returnsFunctor() {
        UUID planId = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        PlanItem item1 = makeItem(id1, 1, "T1", List.of());
        PlanItem item2 = makeItem(id2, 2, "T2", List.of("T1"));

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item1, item2));
        when(taskOutcomeRepository.findOutcomeByPlanItemId(id1))
                .thenReturn(List.of(outcomeRow(0.8, 0.9)));
        when(taskOutcomeRepository.findOutcomeByPlanItemId(id2))
                .thenReturn(List.of(outcomeRow(0.7, 0.6)));

        FunctorialReport report = service.compute(planId);

        assertThat(report).isNotNull();
        assertThat(report.numObjects()).isEqualTo(2);
        assertThat(report.numMorphisms()).isEqualTo(1);  // T2 depends on T1
        assertThat(report.functor()).containsKey(id1);
        assertThat(report.functor().get(id1)).isCloseTo(0.8, within(1e-9));
        assertThat(report.functor()).containsKey(id2);
        assertThat(report.naturalTransform().get(id1)).isCloseTo(0.1, within(1e-9));  // 0.9 - 0.8
        assertThat(report.naturalTransform().get(id2)).isCloseTo(-0.1, within(1e-9)); // 0.6 - 0.7
    }

    @Test
    @DisplayName("compute returns null when fewer than 2 items have outcomes")
    void compute_insufficientOutcomes_returnsNull() {
        UUID planId = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();

        PlanItem item1 = makeItem(id1, 1, "T1", List.of());

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item1));
        // Exactly 1 item → below MIN_ITEMS (2) → null
        when(taskOutcomeRepository.findOutcomeByPlanItemId(id1))
                .thenReturn(List.of(outcomeRow(0.8, 0.9)));

        FunctorialReport report = service.compute(planId);

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("compute returns null when plan has no items")
    void compute_noItems_returnsNull() {
        UUID planId = UUID.randomUUID();
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of());

        FunctorialReport report = service.compute(planId);

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("compositionality is declared for near-additive functor on length-2 path")
    void compute_compositionality_nearAdditivePathDeclaredCompositional() {
        UUID planId = UUID.randomUUID();
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();

        // A → B → C  (chain of 2 deps)
        PlanItem itemA = makeItem(idA, 1, "A", List.of());
        PlanItem itemB = makeItem(idB, 2, "B", List.of("A"));
        PlanItem itemC = makeItem(idC, 3, "C", List.of("B"));

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(itemA, itemB, itemC));
        when(taskOutcomeRepository.findOutcomeByPlanItemId(idA))
                .thenReturn(List.of(outcomeRow(0.0, 0.0)));
        when(taskOutcomeRepository.findOutcomeByPlanItemId(idB))
                .thenReturn(List.of(outcomeRow(0.5, 0.5)));
        when(taskOutcomeRepository.findOutcomeByPlanItemId(idC))
                .thenReturn(List.of(outcomeRow(1.0, 1.0)));

        FunctorialReport report = service.compute(planId);

        assertThat(report).isNotNull();
        // Compositionality error on A→B→C: edgeAB=0.5, edgeBC=0.5, compositeAC=1.0
        // error = |1.0 - (0.5+0.5)| = 0.0 → compositional
        assertThat(report.isCompositional()).isTrue();
        assertThat(report.maxCompositionalityError()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("natural transformation is empty when no actual rewards available")
    void compute_noActualRewards_emptyNaturalTransform() {
        UUID planId = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        PlanItem item1 = makeItem(id1, 1, "T1", List.of());
        PlanItem item2 = makeItem(id2, 2, "T2", List.of());

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item1, item2));
        // actual_reward = null (row[2] = null)
        when(taskOutcomeRepository.findOutcomeByPlanItemId(id1))
                .thenReturn(List.of(new Object[]{UUID.randomUUID(), 0.8, null}));
        when(taskOutcomeRepository.findOutcomeByPlanItemId(id2))
                .thenReturn(List.of(new Object[]{UUID.randomUUID(), 0.7, null}));

        FunctorialReport report = service.compute(planId);

        assertThat(report).isNotNull();
        assertThat(report.naturalTransform()).isEmpty();
    }
}
