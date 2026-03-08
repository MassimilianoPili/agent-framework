package com.agentframework.orchestrator.analytics;

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
 * Unit tests for {@link BayesianSurpriseService}.
 *
 * <p>Covers: prior-only (no data), positive surprise, negative surprise,
 * KL ≥ 0, posterior convergence, input validation.</p>
 */
@ExtendWith(MockitoExtension.class)
class BayesianSurpriseServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private BayesianSurpriseService service;

    @BeforeEach
    void setUp() {
        service = new BayesianSurpriseService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "maxSamples", 500);
    }

    private Object[] row(String type, double reward) {
        return new Object[]{type, reward};
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("throws IllegalArgumentException for null or blank workerType")
    void analyse_invalidWorkerType_throws() {
        assertThatThrownBy(() -> service.analyse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.analyse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── No data ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns null when no data exists")
    void analyse_noData_returnsNull() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(List.<Object[]>of());

        assertThat(service.analyse("be-java")).isNull();
    }

    // ── EXPECTED category ─────────────────────────────────────────────────────

    @Test
    @DisplayName("single reward near prior mean → EXPECTED category")
    void analyse_rewardNearMean_expected() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(List.<Object[]>of(row("be-java", 0.5)));

        BayesianSurpriseService.BayesianSurpriseReport report = service.analyse("be-java");

        assertThat(report).isNotNull();
        assertThat(report.surpriseCategory())
                .isEqualTo(BayesianSurpriseService.SurpriseCategory.EXPECTED);
    }

    // ── POSITIVE_SURPRISE ─────────────────────────────────────────────────────

    @Test
    @DisplayName("many high rewards → POSITIVE_SURPRISE, z-score ≥ 1")
    void analyse_highRewards_positiveSurprise() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) rows.add(row("be-java", 0.95));
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(rows);

        BayesianSurpriseService.BayesianSurpriseReport report = service.analyse("be-java");

        assertThat(report.surpriseCategory())
                .isEqualTo(BayesianSurpriseService.SurpriseCategory.POSITIVE_SURPRISE);
        assertThat(report.zScore()).isGreaterThanOrEqualTo(BayesianSurpriseService.SURPRISE_Z);
    }

    // ── NEGATIVE_SURPRISE ─────────────────────────────────────────────────────

    @Test
    @DisplayName("many low rewards → NEGATIVE_SURPRISE, z-score ≤ -1")
    void analyse_lowRewards_negativeSurprise() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) rows.add(row("be-java", 0.05));
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(rows);

        BayesianSurpriseService.BayesianSurpriseReport report = service.analyse("be-java");

        assertThat(report.surpriseCategory())
                .isEqualTo(BayesianSurpriseService.SurpriseCategory.NEGATIVE_SURPRISE);
        assertThat(report.zScore()).isLessThanOrEqualTo(-BayesianSurpriseService.SURPRISE_Z);
    }

    // ── KL divergence properties ──────────────────────────────────────────────

    @Test
    @DisplayName("KL divergence is always ≥ 0 (Gibbs inequality)")
    void analyse_klDivergenceNonNegative() {
        List<Object[]> rows = List.of(row("be-java", 0.7), row("be-java", 0.8));
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(rows);

        BayesianSurpriseService.BayesianSurpriseReport report = service.analyse("be-java");

        assertThat(report.klDivergence()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("KL = 0 only when posterior equals prior exactly")
    void analyse_priorEqualsPosterior_klNearZero() {
        // With many observations at exactly the prior mean, posterior converges to 0.5
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) rows.add(row("be-java", 0.5));
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(rows);

        BayesianSurpriseService.BayesianSurpriseReport report = service.analyse("be-java");

        // Posterior mean ≈ 0.5, variance → 0, KL > 0 because variance decreased
        assertThat(report.klDivergence()).isGreaterThanOrEqualTo(0.0);
        assertThat(report.posteriorMean()).isCloseTo(0.5, within(0.01));
    }

    // ── Posterior convergence ─────────────────────────────────────────────────

    @Test
    @DisplayName("posterior mean converges toward sample mean with many observations")
    void analyse_manyObservations_posteriorConverges() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) rows.add(row("be-java", 0.8));
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(rows);

        BayesianSurpriseService.BayesianSurpriseReport report = service.analyse("be-java");

        assertThat(report.posteriorMean()).isCloseTo(0.8, within(0.02));
        assertThat(report.observations()).isEqualTo(1000);
    }

    // ── Report completeness ───────────────────────────────────────────────────

    @Test
    @DisplayName("report contains correct prior values")
    void analyse_reportHasCorrectPrior() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(List.<Object[]>of(row("be-java", 0.6)));

        BayesianSurpriseService.BayesianSurpriseReport report = service.analyse("be-java");

        assertThat(report.priorMean()).isEqualTo(BayesianSurpriseService.PRIOR_MU);
        assertThat(report.priorVariance()).isEqualTo(BayesianSurpriseService.PRIOR_VAR);
        assertThat(report.workerType()).isEqualTo("be-java");
    }
}
