package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ContractTheoryService.ContractEvaluationReport;
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
 * Unit tests for {@link ContractTheoryService}.
 *
 * <p>Verifies contract evaluation with multiple profiles, insufficient data,
 * and all-above-target scenarios.</p>
 */
@ExtendWith(MockitoExtension.class)
class ContractTheoryServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private WorkerProfileRegistry profileRegistry;

    private ContractTheoryService service;

    @BeforeEach
    void setUp() {
        service = new ContractTheoryService(taskOutcomeRepository, profileRegistry);
        ReflectionTestUtils.setField(service, "defaultQualityTarget", 0.6);
        ReflectionTestUtils.setField(service, "defaultBonusMultiplier", 1.5);
        ReflectionTestUtils.setField(service, "defaultPenaltyRate", 0.5);
        ReflectionTestUtils.setField(service, "learningRate", 0.1);
    }

    /**
     * Creates a mock row matching findTrainingDataRaw format (12 columns).
     * Index 7 = eloAtDispatch (token cost proxy), index 10 = actual_reward.
     */
    private Object[] makeRow(double actualReward, double eloAtDispatch) {
        Object[] row = new Object[12];
        row[0] = UUID.randomUUID();
        row[1] = UUID.randomUUID();
        row[2] = UUID.randomUUID();
        row[3] = "task-key";
        row[4] = "BE";
        row[5] = "be-java";
        row[6] = "[0.1,0.2,0.3]";
        row[7] = eloAtDispatch;
        row[8] = actualReward + 0.02;
        row[9] = 0.01;
        row[10] = actualReward;
        row[11] = java.sql.Timestamp.valueOf("2026-01-01 00:00:00");
        return row;
    }

    @Test
    @DisplayName("evaluateContracts with multiple profiles returns report")
    void evaluateContracts_withMultipleProfiles_returnsReport() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        // be-java: high quality (above target)
        List<Object[]> javaData = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            javaData.add(makeRow(0.7 + 0.02 * (i % 3), 1500.0)); // 0.70, 0.72, 0.74
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(javaData);

        // be-go: low quality (below target)
        List<Object[]> goData = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            goData.add(makeRow(0.3 + 0.01 * i, 1200.0)); // 0.30 to 0.39
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 100))
                .thenReturn(goData);

        ContractEvaluationReport report = service.evaluateContracts("BE");

        assertThat(report).isNotNull();
        assertThat(report.workerType()).isEqualTo("BE");
        assertThat(report.profilesEvaluated()).isEqualTo(2);
        assertThat(report.contractReport()).isNotNull();
        assertThat(report.contractReport().contracts()).hasSize(2);
        assertThat(report.contractReport().evaluations()).hasSize(2);
        // IC should be satisfied with default bonus=1.5 > penalty=0.5
        assertThat(report.systemIncentiveCompatible()).isTrue();
    }

    @Test
    @DisplayName("evaluateContracts with insufficient data returns null")
    void evaluateContracts_insufficientData_returnsNull() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java"));

        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(new ArrayList<>());

        ContractEvaluationReport report = service.evaluateContracts("BE");

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("evaluateContracts with all above target has positive surplus")
    void evaluateContracts_allAboveTarget_positiveSurplus() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java"));

        // All observations consistently high quality
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            data.add(makeRow(0.85, 1000.0)); // constant high reward
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(data);

        ContractEvaluationReport report = service.evaluateContracts("BE");

        assertThat(report).isNotNull();
        assertThat(report.contractReport().profilesAboveTarget()).isEqualTo(1);
        assertThat(report.contractReport().profilesBelowTarget()).isEqualTo(0);
        assertThat(report.contractReport().systemSurplus()).isGreaterThanOrEqualTo(0.0);
        assertThat(report.contractReport().recommendations()).isEmpty();
    }
}
