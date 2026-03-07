package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ValueOfInformationService.VoiExplorationReport;
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
 * Unit tests for {@link ValueOfInformationService}.
 *
 * <p>Verifies VoI exploration analysis with multiple profiles, insufficient data,
 * and well-known profiles where no exploration is needed.</p>
 */
@ExtendWith(MockitoExtension.class)
class ValueOfInformationServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private WorkerProfileRegistry profileRegistry;

    private ValueOfInformationService service;

    @BeforeEach
    void setUp() {
        service = new ValueOfInformationService(taskOutcomeRepository, profileRegistry);
        ReflectionTestUtils.setField(service, "explorationFraction", 0.15);
        ReflectionTestUtils.setField(service, "monteCarloSamples", 500);
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
    @DisplayName("evaluateExploration with multiple profiles returns VoI ranking")
    void evaluateExploration_withMultipleProfiles_returnsRanking() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        // be-java: high variance (worth exploring)
        List<Object[]> javaData = new ArrayList<>();
        for (int i = 0; i < 10; i++) javaData.add(makeRow(0.3 + (i % 5) * 0.15));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(javaData);

        // be-go: low variance (well-known)
        List<Object[]> goData = new ArrayList<>();
        for (int i = 0; i < 10; i++) goData.add(makeRow(0.7));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 100))
                .thenReturn(goData);

        VoiExplorationReport report = service.evaluateExploration("BE");

        assertThat(report).isNotNull();
        assertThat(report.workerType()).isEqualTo("BE");
        assertThat(report.profilesEvaluated()).isEqualTo(2);
        assertThat(report.voiReport()).isNotNull();
        assertThat(report.voiReport().profileNames()).hasSize(2);
        assertThat(report.voiReport().evsiValues()).hasSize(2);
        assertThat(report.voiReport().rankingByVoi()).hasSize(2);
        // be-java (higher variance) should rank first for exploration
        assertThat(report.voiReport().rankingByVoi()[0]).isEqualTo(0); // be-java index
    }

    @Test
    @DisplayName("evaluateExploration with insufficient data returns null")
    void evaluateExploration_insufficientData_returnsNull() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java"));

        List<Object[]> tooFew = new ArrayList<>();
        for (int i = 0; i < 2; i++) tooFew.add(makeRow(0.5));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(tooFew);

        VoiExplorationReport report = service.evaluateExploration("BE");

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("evaluateExploration with all well-known profiles returns low exploration value")
    void evaluateExploration_allWellKnown_lowExplorationValue() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        // Both profiles: very stable rewards (near-zero variance)
        List<Object[]> stableData = new ArrayList<>();
        for (int i = 0; i < 10; i++) stableData.add(makeRow(0.7));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(stableData);
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 100))
                .thenReturn(stableData);

        VoiExplorationReport report = service.evaluateExploration("BE");

        assertThat(report).isNotNull();
        assertThat(report.voiReport().totalExplorationValue()).isCloseTo(0.0, within(0.01));
    }
}
