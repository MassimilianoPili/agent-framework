package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.RealOptionsService.RealOptionsValuationReport;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RealOptionsService}.
 *
 * <p>Verifies deferral evaluation with multiple profiles, insufficient data,
 * and low-volatility profiles.</p>
 */
@ExtendWith(MockitoExtension.class)
class RealOptionsServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private WorkerProfileRegistry profileRegistry;

    private RealOptionsService service;

    @BeforeEach
    void setUp() {
        service = new RealOptionsService(taskOutcomeRepository, profileRegistry);
        ReflectionTestUtils.setField(service, "discountRate", 0.05);
        ReflectionTestUtils.setField(service, "urgencyWeight", 0.1);
        ReflectionTestUtils.setField(service, "minObservations", 5);
    }

    /**
     * Creates a mock row matching findTrainingDataRaw format (12 columns).
     * Index 10 = actual_reward.
     */
    private Object[] makeRow(double actualReward) {
        Object[] row = new Object[12];
        row[0] = UUID.randomUUID();
        row[1] = UUID.randomUUID();
        row[2] = UUID.randomUUID();
        row[3] = "task-key";
        row[4] = "BE";
        row[5] = "be-java";
        row[6] = "[0.1,0.2,0.3]";
        row[7] = 1500.0;
        row[8] = actualReward + 0.02;
        row[9] = 0.01;
        row[10] = actualReward;
        row[11] = java.sql.Timestamp.valueOf("2026-01-01 00:00:00");
        return row;
    }

    @Test
    @DisplayName("evaluateDeferral with multiple profiles returns report")
    void evaluateDeferral_withMultipleProfiles_returnsReport() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        // be-java: stable, low volatility (rewards ≈ 0.7)
        List<Object[]> javaData = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            javaData.add(makeRow(0.70 + 0.01 * (i % 3))); // 0.70, 0.71, 0.72
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(javaData);

        // be-go: volatile (rewards vary widely)
        List<Object[]> goData = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            goData.add(makeRow(0.2 + 0.1 * i)); // 0.2 to 1.1
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 100))
                .thenReturn(goData);

        RealOptionsValuationReport report = service.evaluateDeferral("BE");

        assertThat(report).isNotNull();
        assertThat(report.workerType()).isEqualTo("BE");
        assertThat(report.profilesEvaluated()).isEqualTo(2);
        assertThat(report.optionsReport()).isNotNull();
        assertThat(report.optionsReport().decisions()).hasSize(2);
    }

    @Test
    @DisplayName("evaluateDeferral with insufficient data returns null")
    void evaluateDeferral_insufficientData_returnsNull() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java"));

        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(new ArrayList<>());

        RealOptionsValuationReport report = service.evaluateDeferral("BE");

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("evaluateDeferral with low volatility has no deferrals")
    void evaluateDeferral_lowVolatility_noDeferrals() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java"));

        // Very stable rewards → σ ≈ 0 → threshold ≈ cost → V >> cost → execute
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            data.add(makeRow(0.8)); // constant high reward
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(data);

        RealOptionsValuationReport report = service.evaluateDeferral("BE");

        assertThat(report).isNotNull();
        assertThat(report.optionsReport().profilesDeferred()).isEqualTo(0);
        assertThat(report.optionsReport().profilesReady()).isEqualTo(1);
        assertThat(report.systemDeferralRatio()).isCloseTo(0.0, within(1e-9));
    }
}
