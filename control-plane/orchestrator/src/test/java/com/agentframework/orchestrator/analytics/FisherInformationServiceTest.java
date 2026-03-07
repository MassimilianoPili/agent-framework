package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.FisherInformationService.FisherUncertaintyReport;
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
 * Unit tests for {@link FisherInformationService}.
 *
 * <p>Verifies Fisher uncertainty analysis with multiple profiles, insufficient data,
 * and single-profile cases.</p>
 */
@ExtendWith(MockitoExtension.class)
class FisherInformationServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private WorkerProfileRegistry profileRegistry;

    private FisherInformationService service;

    @BeforeEach
    void setUp() {
        service = new FisherInformationService(taskOutcomeRepository, profileRegistry);
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
        row[8] = 0.7;
        row[9] = 0.01;
        row[10] = actualReward;
        row[11] = java.sql.Timestamp.valueOf("2026-01-01 00:00:00");
        return row;
    }

    @Test
    @DisplayName("analyzeUncertainty with multiple profiles returns Fisher report")
    void analyzeUncertainty_withMultipleProfiles_returnsReport() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        // be-java: varying rewards (high uncertainty)
        List<Object[]> javaData = new ArrayList<>();
        for (int i = 0; i < 10; i++) javaData.add(makeRow(0.5 + (i % 5) * 0.1));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(javaData);

        // be-go: stable rewards (low uncertainty)
        List<Object[]> goData = new ArrayList<>();
        for (int i = 0; i < 10; i++) goData.add(makeRow(0.7));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 100))
                .thenReturn(goData);

        FisherUncertaintyReport report = service.analyzeUncertainty("BE");

        assertThat(report).isNotNull();
        assertThat(report.workerType()).isEqualTo("BE");
        assertThat(report.profilesAnalyzed()).isEqualTo(2);
        assertThat(report.fisherReport()).isNotNull();
        assertThat(report.fisherReport().profileNames()).hasSize(2);
        assertThat(report.fisherReport().decompositions()).hasSize(2);
        // be-java should be more informative (higher reducible uncertainty)
        assertThat(report.mostInformativeProfile()).isEqualTo("be-java");
    }

    @Test
    @DisplayName("analyzeUncertainty with insufficient data returns null")
    void analyzeUncertainty_insufficientData_returnsNull() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java"));

        List<Object[]> tooFew = new ArrayList<>();
        for (int i = 0; i < 3; i++) tooFew.add(makeRow(0.5));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(tooFew);

        FisherUncertaintyReport report = service.analyzeUncertainty("BE");

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("analyzeUncertainty with single profile identifies it as most informative")
    void analyzeUncertainty_singleProfile_identifiesAsInformative() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java"));

        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < 10; i++) data.add(makeRow(0.5 + i * 0.05));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(data);

        FisherUncertaintyReport report = service.analyzeUncertainty("BE");

        assertThat(report).isNotNull();
        assertThat(report.profilesAnalyzed()).isEqualTo(1);
        assertThat(report.mostInformativeProfile()).isEqualTo("be-java");
    }
}
