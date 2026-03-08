package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.RenormalizationGroupService.RGAnalysisReport;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RenormalizationGroupService}.
 *
 * <p>Verifies coupling computation across scales, beta function,
 * and edge cases (too few items).</p>
 */
@ExtendWith(MockitoExtension.class)
class RenormalizationGroupServiceTest {

    @Mock private PlanItemRepository planItemRepository;

    private RenormalizationGroupService service;

    @BeforeEach
    void setUp() {
        service = new RenormalizationGroupService(planItemRepository);
        ReflectionTestUtils.setField(service, "maxScales", 3);
    }

    private PlanItem makeItem(int ordinal, String taskKey, List<String> deps) {
        return new PlanItem(UUID.randomUUID(), ordinal, taskKey, "title", "desc",
                            WorkerType.BE, "be-java", deps, List.of());
    }

    @Test
    @DisplayName("analyse returns RG report with coupling for each scale")
    void analyse_withDependencies_returnsCouplingReport() {
        UUID planId = UUID.randomUUID();

        // 6-item linear chain: T1→T2→T3→T4→T5→T6
        List<PlanItem> items = new ArrayList<>();
        items.add(makeItem(1, "T1", List.of()));
        items.add(makeItem(2, "T2", List.of("T1")));
        items.add(makeItem(3, "T3", List.of("T2")));
        items.add(makeItem(4, "T4", List.of("T3")));
        items.add(makeItem(5, "T5", List.of("T4")));
        items.add(makeItem(6, "T6", List.of("T5")));

        when(planItemRepository.findByPlanId(planId)).thenReturn(items);

        RGAnalysisReport report = service.analyse(planId);

        assertThat(report).isNotNull();
        assertThat(report.numItems()).isEqualTo(6);
        assertThat(report.couplingByScale()).containsKeys("fine", "medium", "coarse");
        assertThat(report.betaFunction()).hasSize(2);
        // All coupling values are non-negative
        report.couplingByScale().values().forEach(c -> assertThat(c).isGreaterThanOrEqualTo(0.0));
    }

    @Test
    @DisplayName("analyse with fewer than 3 items returns null")
    void analyse_tooFewItems_returnsNull() {
        UUID planId = UUID.randomUUID();
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(
                makeItem(1, "T1", List.of()),
                makeItem(2, "T2", List.of("T1"))
        ));

        RGAnalysisReport report = service.analyse(planId);

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("analyse with no dependency edges has zero coupling at all scales")
    void analyse_noEdges_zeroCoupling() {
        UUID planId = UUID.randomUUID();

        List<PlanItem> items = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            items.add(makeItem(i, "T" + i, List.of()));  // all independent
        }

        when(planItemRepository.findByPlanId(planId)).thenReturn(items);

        RGAnalysisReport report = service.analyse(planId);

        assertThat(report).isNotNull();
        // No inter-block edges → all couplings = 0
        report.couplingByScale().values()
              .forEach(c -> assertThat(c).isEqualTo(0.0));
    }
}
