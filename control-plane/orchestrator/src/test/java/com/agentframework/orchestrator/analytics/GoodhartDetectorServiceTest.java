package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.GoodhartDetectorService.GoodhartAuditReport;
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
 * Unit tests for {@link GoodhartDetectorService}.
 *
 * <p>Verifies Goodhart audit with multiple profiles, insufficient data,
 * and all-healthy profiles.</p>
 */
@ExtendWith(MockitoExtension.class)
class GoodhartDetectorServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private WorkerProfileRegistry profileRegistry;

    private GoodhartDetectorService service;

    @BeforeEach
    void setUp() {
        service = new GoodhartDetectorService(taskOutcomeRepository, profileRegistry);
        ReflectionTestUtils.setField(service, "windowSize", 50);
        ReflectionTestUtils.setField(service, "divergenceThreshold", 0.3);
        ReflectionTestUtils.setField(service, "minSampleSize", 10);
    }

    /**
     * Creates a mock row matching findTrainingDataRaw format (12 columns).
     * Index 8 = gpMu, index 10 = actual_reward.
     */
    private Object[] makeRow(double gpMu, double actualReward) {
        Object[] row = new Object[12];
        row[0] = UUID.randomUUID();
        row[1] = UUID.randomUUID();
        row[2] = UUID.randomUUID();
        row[3] = "task-key";
        row[4] = "BE";
        row[5] = "be-java";
        row[6] = "[0.1,0.2,0.3]";
        row[7] = 1500.0;
        row[8] = gpMu;
        row[9] = 0.01;
        row[10] = actualReward;
        row[11] = java.sql.Timestamp.valueOf("2026-01-01 00:00:00");
        return row;
    }

    @Test
    @DisplayName("auditMetrics with multiple profiles returns Goodhart report")
    void auditMetrics_withMultipleProfiles_returnsReport() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        // be-java: good proxy-goal alignment (gpMu ≈ actualReward)
        List<Object[]> javaData = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            double reward = 0.6 + (i % 5) * 0.05;
            javaData.add(makeRow(reward + 0.02, reward)); // proxy close to goal
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 50))
                .thenReturn(javaData);

        // be-go: poor proxy-goal alignment (gpMu uncorrelated with actualReward)
        List<Object[]> goData = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            double reward = 0.5 + (i % 5) * 0.05;
            double gpMu = 0.8 - (i % 5) * 0.05; // inverse correlation
            goData.add(makeRow(gpMu, reward));
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 50))
                .thenReturn(goData);

        GoodhartAuditReport report = service.auditMetrics("BE");

        assertThat(report).isNotNull();
        assertThat(report.workerType()).isEqualTo("BE");
        assertThat(report.profilesAudited()).isEqualTo(2);
        assertThat(report.goodhartReport()).isNotNull();
        assertThat(report.goodhartReport().profileHealths()).hasSize(2);
    }

    @Test
    @DisplayName("auditMetrics with insufficient data returns null")
    void auditMetrics_insufficientData_returnsNull() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java"));

        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 50))
                .thenReturn(new ArrayList<>());

        GoodhartAuditReport report = service.auditMetrics("BE");

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("auditMetrics with all healthy profiles returns high system score")
    void auditMetrics_allHealthy_returnsHighScore() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java"));

        // Strong proxy-goal correlation, sufficient data
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double reward = 0.5 + i * 0.02;
            data.add(makeRow(reward + 0.01, reward)); // nearly perfect proxy
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 50))
                .thenReturn(data);

        GoodhartAuditReport report = service.auditMetrics("BE");

        assertThat(report).isNotNull();
        assertThat(report.systemHealthy()).isTrue();
        assertThat(report.goodhartReport().systemHealthScore()).isGreaterThan(0.5);
        assertThat(report.goodhartReport().profilesAtRisk()).isEqualTo(0);
    }
}
