package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ActiveInferenceService.FreeEnergyReport;
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
 * Unit tests for {@link ActiveInferenceService}.
 *
 * <p>Verifies free energy minimisation for optimal profile selection,
 * balancing high mean reward (low surprise) against uncertainty (KL).</p>
 */
@ExtendWith(MockitoExtension.class)
class ActiveInferenceServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private WorkerProfileRegistry profileRegistry;

    private ActiveInferenceService service;

    @BeforeEach
    void setUp() {
        service = new ActiveInferenceService(taskOutcomeRepository, profileRegistry);
        ReflectionTestUtils.setField(service, "klWeight", 1.0);
    }

    /**
     * Creates training data with specified gp_mu (index 8) and gp_sigma2 (index 9).
     * Rows: [8]=gp_mu, [9]=gp_sigma2, [10]=actual_reward
     */
    private List<Object[]> makeGpData(double gpMu, double gpSigma2, int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object[] row = new Object[12];
            row[8]  = gpMu;
            row[9]  = gpSigma2;
            row[10] = gpMu;
            rows.add(row);
        }
        return rows;
    }

    @Test
    @DisplayName("computeFreeEnergy selects profile with minimum free energy")
    void computeFreeEnergy_selectsMinimumFreeEnergy() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        // be-java: mu=0.8, sigma2=0.1 → F = -0.8 + 1.0*0.1 = -0.7
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 100))
                .thenReturn(makeGpData(0.8, 0.1, 10));

        // be-go: mu=0.7, sigma2=0.3 → F = -0.7 + 1.0*0.3 = -0.4 (higher F = worse)
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 100))
                .thenReturn(makeGpData(0.7, 0.3, 10));

        FreeEnergyReport report = service.computeFreeEnergy("BE");

        assertThat(report).isNotNull();
        assertThat(report.candidates()).hasSize(2);
        assertThat(report.optimalChoice()).isEqualTo("be-java");
        // F(be-java) = -0.7 < F(be-go) = -0.4
        assertThat(report.freeEnergies()[0]).isLessThan(report.freeEnergies()[1]);
    }

    @Test
    @DisplayName("computeFreeEnergy with no profiles returns null")
    void computeFreeEnergy_noProfiles_returnsNull() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE)).thenReturn(List.of());

        FreeEnergyReport report = service.computeFreeEnergy("BE");

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("computeFreeEnergy penalises high uncertainty profile")
    void computeFreeEnergy_penalisesHighUncertainty() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-certain", "be-uncertain"));

        // Both same mean, but different uncertainty
        // be-certain: mu=0.8, sigma2=0.05 → F = -0.8 + 0.05 = -0.75
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-certain", 100))
                .thenReturn(makeGpData(0.8, 0.05, 8));

        // be-uncertain: mu=0.8, sigma2=0.5 → F = -0.8 + 0.5 = -0.3 (worse)
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-uncertain", 100))
                .thenReturn(makeGpData(0.8, 0.5, 8));

        FreeEnergyReport report = service.computeFreeEnergy("BE");

        assertThat(report).isNotNull();
        assertThat(report.optimalChoice()).isEqualTo("be-certain");
    }
}
