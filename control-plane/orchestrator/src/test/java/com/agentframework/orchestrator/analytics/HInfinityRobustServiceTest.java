package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.HInfinityRobustService.RobustDispatchReport;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
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
 * Unit tests for {@link HInfinityRobustService}.
 *
 * <p>Verifies worst-case robust profile selection over normal distribution quantile.</p>
 */
@ExtendWith(MockitoExtension.class)
class HInfinityRobustServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private WorkerProfileRegistry profileRegistry;

    private HInfinityRobustService service;

    @BeforeEach
    void setUp() {
        service = new HInfinityRobustService(taskOutcomeRepository, profileRegistry);
        ReflectionTestUtils.setField(service, "confidenceLevel", 0.95);
    }

    /**
     * Creates mock rows for findTrainingDataRaw.
     * Column [10] = actual_reward.
     */
    private List<Object[]> makeTrainingData(double... rewards) {
        List<Object[]> data = new ArrayList<>();
        for (double r : rewards) {
            Object[] row = new Object[12];
            row[10] = r;
            data.add(row);
        }
        return data;
    }

    @Test
    @DisplayName("computeRobustChoice selects low-variance profile over high-mean high-variance profile")
    void computeRobustChoice_selectsLowVarianceProfile() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-stable", "be-volatile"));

        // be-stable: mean=0.75, std≈0.03 (tight) → worst-case ≈ 0.75 - 1.645*0.03 ≈ 0.70
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-stable", 100))
                .thenReturn(makeTrainingData(0.72, 0.74, 0.76, 0.75, 0.78, 0.73, 0.74, 0.76, 0.75, 0.77));

        // be-volatile: mean=0.85, std≈0.15 (wide) → worst-case ≈ 0.85 - 1.645*0.15 ≈ 0.60
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-volatile", 100))
                .thenReturn(makeTrainingData(0.9, 0.5, 0.95, 0.6, 0.85, 0.5, 0.9, 0.95, 0.7, 0.8));

        RobustDispatchReport report = service.computeRobustChoice("BE");

        assertThat(report).isNotNull();
        assertThat(report.candidates()).hasSize(2);
        // Stable profile wins even though volatile has higher mean
        assertThat(report.robustChoice()).isEqualTo("be-stable");
        assertThat(report.worstCaseReward()).isGreaterThan(0.6);
    }

    @Test
    @DisplayName("computeRobustChoice with no profiles returns null")
    void computeRobustChoice_noProfiles_returnsNull() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE)).thenReturn(List.of());

        RobustDispatchReport report = service.computeRobustChoice("BE");

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("computeRobustChoice filters profiles with insufficient samples")
    void computeRobustChoice_insufficientSamples_filtersProfile() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-ok", "be-sparse"));

        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-ok", 100))
                .thenReturn(makeTrainingData(0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8));

        // Only 3 samples — below MIN_SAMPLES=5
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-sparse", 100))
                .thenReturn(makeTrainingData(0.9, 0.9, 0.9));

        RobustDispatchReport report = service.computeRobustChoice("BE");

        assertThat(report).isNotNull();
        assertThat(report.candidates()).hasSize(1);
        assertThat(report.robustChoice()).isEqualTo("be-ok");
    }
}
