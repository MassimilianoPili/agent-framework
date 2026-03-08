package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.SuperrationalityService.SuperrationalityReport;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SuperrationalityService}.
 *
 * <p>Verifies cooperation gain computation for cooperative pairs, empty data,
 * and pairs below the minimum overlap threshold.</p>
 */
@ExtendWith(MockitoExtension.class)
class SuperrationalityServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private SuperrationalityService service;

    @BeforeEach
    void setUp() {
        service = new SuperrationalityService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "minOverlap", 2);
    }

    /**
     * Creates a row matching findPlanWorkerRewardSummary format.
     * [0]=plan_id_text(String), [1]=worker_type(String), [2]=actual_reward(Number)
     */
    private Object[] makeRow(String planId, String workerType, double reward) {
        return new Object[]{planId, workerType, reward};
    }

    @Test
    @DisplayName("compute with cooperative pairs returns positive cooperation gain")
    void compute_cooperativePairs_returnsPositiveGain() {
        // Plan-1: both BE and FE present (higher rewards when together)
        // Plan-2: both BE and FE present (higher rewards when together)
        // Plan-3: only BE present (lower baseline)
        List<Object[]> rows = new ArrayList<>();
        rows.add(makeRow("plan-1", "BE", 0.9));
        rows.add(makeRow("plan-1", "FE", 0.85));
        rows.add(makeRow("plan-2", "BE", 0.88));
        rows.add(makeRow("plan-2", "FE", 0.82));
        rows.add(makeRow("plan-3", "BE", 0.5));  // BE alone: lower reward

        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(rows);

        SuperrationalityReport report = service.compute();

        assertThat(report).isNotNull();
        assertThat(report.cooperationGains()).containsKey("BE × FE");
        // BE earns more when FE is present (0.89 vs 0.5) → positive gain
        assertThat(report.cooperationGains().get("BE × FE")).isGreaterThan(0.0);
        assertThat(report.cooperativePairs()).contains("BE × FE");
        assertThat(report.globalGain()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("compute with empty data returns null")
    void compute_emptyData_returnsNull() {
        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(List.of());

        SuperrationalityReport report = service.compute();

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("compute with insufficient overlap skips pair")
    void compute_insufficientOverlap_skipsPair() {
        // Only 1 co-occurring plan (minOverlap=2 → insufficient)
        List<Object[]> rows = new ArrayList<>();
        rows.add(makeRow("plan-1", "BE", 0.8));
        rows.add(makeRow("plan-1", "FE", 0.7));
        rows.add(makeRow("plan-2", "BE", 0.6));  // FE absent

        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(rows);

        SuperrationalityReport report = service.compute();

        assertThat(report).isNotNull();
        // Only 1 plan with both BE and FE → below minOverlap=2, pair skipped
        assertThat(report.cooperationGains()).isEmpty();
        assertThat(report.cooperativePairs()).isEmpty();
    }

    @Test
    @DisplayName("compute with single worker type has no pairs")
    void compute_singleWorkerType_noPairs() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(makeRow("plan-1", "BE", 0.8));
        rows.add(makeRow("plan-2", "BE", 0.7));
        rows.add(makeRow("plan-3", "BE", 0.9));

        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(rows);

        SuperrationalityReport report = service.compute();

        assertThat(report).isNotNull();
        assertThat(report.cooperationGains()).isEmpty();
        assertThat(report.globalGain()).isEqualTo(0.0);
    }
}
