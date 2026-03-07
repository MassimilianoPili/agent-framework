package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.VCGMechanismService.VCGPricingReport;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VCGMechanismService}.
 *
 * <p>Verifies VCG pricing computation with multiple profiles, insufficient data,
 * and single-profile degenerate cases.</p>
 */
@ExtendWith(MockitoExtension.class)
class VCGMechanismServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private WorkerProfileRegistry profileRegistry;

    private VCGMechanismService service;

    @BeforeEach
    void setUp() {
        service = new VCGMechanismService(taskOutcomeRepository, profileRegistry);
    }

    /**
     * Creates a mock row matching findTrainingDataRaw format (12 columns).
     * Index 10 = actual_reward.
     */
    private Object[] makeRow(double actualReward) {
        Object[] row = new Object[12];
        row[0] = UUID.randomUUID();       // id
        row[1] = UUID.randomUUID();       // planItemId
        row[2] = UUID.randomUUID();       // planId
        row[3] = "task-key";              // taskKey
        row[4] = "BE";                    // workerType
        row[5] = "be-java";              // workerProfile
        row[6] = "[0.1,0.2,0.3]";        // embeddingText
        row[7] = 1500.0;                 // eloAtDispatch
        row[8] = 0.7;                    // gpMu
        row[9] = 0.01;                   // gpSigma2
        row[10] = actualReward;           // actualReward
        row[11] = java.sql.Timestamp.valueOf("2026-01-01 00:00:00"); // createdAt
        return row;
    }

    @Test
    @DisplayName("computePricing with multiple profiles returns VCG allocation and pricing")
    void computePricing_withMultipleProfiles_returnsVCGResult() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go", "be-rust"));

        // be-java: mean reward = 0.8
        List<Object[]> javaData = new ArrayList<>();
        for (int i = 0; i < 10; i++) javaData.add(makeRow(0.8));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(javaData);

        // be-go: mean reward = 0.6
        List<Object[]> goData = new ArrayList<>();
        for (int i = 0; i < 10; i++) goData.add(makeRow(0.6));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 100))
                .thenReturn(goData);

        // be-rust: mean reward = 0.7
        List<Object[]> rustData = new ArrayList<>();
        for (int i = 0; i < 10; i++) rustData.add(makeRow(0.7));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-rust", 100))
                .thenReturn(rustData);

        VCGPricingReport report = service.computePricing("BE");

        assertThat(report.workerType()).isEqualTo("BE");
        assertThat(report.profilesEvaluated()).isEqualTo(3);
        assertThat(report.result()).isNotNull();
        assertThat(report.result().winner()).isEqualTo("be-java");
        // Second-price = 0.7 (be-rust)
        assertThat(report.result().payment()).isCloseTo(0.7, within(1e-9));
        // Rent = 0.8 - 0.7 = 0.1
        assertThat(report.result().informationRent()).isCloseTo(0.1, within(1e-9));
    }

    @Test
    @DisplayName("computePricing with insufficient data returns null result")
    void computePricing_insufficientData_returnsNullResult() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        // Both profiles have < MIN_OUTCOMES (5)
        List<Object[]> tooFew = new ArrayList<>();
        for (int i = 0; i < 3; i++) tooFew.add(makeRow(0.5));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(tooFew);
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 100))
                .thenReturn(tooFew);

        VCGPricingReport report = service.computePricing("BE");

        assertThat(report.result()).isNull();
        assertThat(report.profilesEvaluated()).isEqualTo(0);
    }

    @Test
    @DisplayName("computePricing with single profile returns zero payment")
    void computePricing_singleProfile_returnsZeroPayment() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        // Only be-java has enough data
        List<Object[]> javaData = new ArrayList<>();
        for (int i = 0; i < 10; i++) javaData.add(makeRow(0.8));
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(javaData);

        // be-go: insufficient
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 100))
                .thenReturn(new ArrayList<>());

        VCGPricingReport report = service.computePricing("BE");

        assertThat(report.profilesEvaluated()).isEqualTo(1);
        assertThat(report.result()).isNotNull();
        assertThat(report.result().payment()).isEqualTo(0.0);
    }
}
