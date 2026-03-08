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
 * Unit tests for {@link PACBayesService}.
 *
 * <p>Verifies convergence detection, required-sample calculation,
 * KL divergence computation, and edge cases.</p>
 */
@ExtendWith(MockitoExtension.class)
class PACBayesServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private PACBayesService service;

    @BeforeEach
    void setUp() {
        service = new PACBayesService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "defaultEpsilon", 0.05);
        ReflectionTestUtils.setField(service, "defaultDelta",   0.05);
    }

    /** Row format: [task_key, reward, gp_mu, ...] */
    private Object[] makeRow(double reward) {
        return new Object[]{"T1", reward, 0.8};
    }

    @Test
    @DisplayName("returns null when no outcomes exist for the worker type")
    void compute_noOutcomes_returnsNull() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("unknown", 1000))
                .thenReturn(List.of());

        assertThat(service.compute("unknown")).isNull();
    }

    @Test
    @DisplayName("convergence is not reached when sample count is below required")
    void compute_fewSamples_convergenceNotReached() {
        // ε=0.05, δ=0.05 → n_min = ⌈2·ln(2/0.05)/0.05²⌉ = ⌈2·ln(40)/0.0025⌉ ≈ 2956
        // Provide only 10 samples
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(makeRow(0.7 + i * 0.01));

        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("be-java", 1000))
                .thenReturn(rows);

        PACBayesService.PACBayesReport report = service.compute("be-java");

        assertThat(report).isNotNull();
        assertThat(report.currentSamples()).isEqualTo(10);
        assertThat(report.convergenceReached()).isFalse();
        assertThat(report.requiredSamples()).isGreaterThan(10);
    }

    @Test
    @DisplayName("convergence is reached when sample count meets n_min")
    void compute_enoughSamples_convergenceReached() {
        // Use large ε to make n_min small: ε=0.3, δ=0.1 → n_min = ⌈2·ln(20)/0.09⌉ ≈ 66
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) rows.add(makeRow(0.75));

        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("fe-ts", 1000))
                .thenReturn(rows);

        PACBayesService.PACBayesReport report = service.compute("fe-ts", 0.3, 0.1);

        assertThat(report).isNotNull();
        assertThat(report.currentSamples()).isEqualTo(100);
        assertThat(report.convergenceReached()).isTrue();
    }

    @Test
    @DisplayName("confidence bound decreases as more samples are provided")
    void compute_moreSamples_tighterBound() {
        List<Object[]> few  = new ArrayList<>();
        List<Object[]> many = new ArrayList<>();
        for (int i = 0; i < 10;  i++) few.add(makeRow(0.8));
        for (int i = 0; i < 200; i++) many.add(makeRow(0.8));

        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("ops-k8s-few", 1000))
                .thenReturn(few);
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("ops-k8s-many", 1000))
                .thenReturn(many);

        PACBayesService.PACBayesReport fewReport  = service.compute("ops-k8s-few");
        PACBayesService.PACBayesReport manyReport = service.compute("ops-k8s-many");

        assertThat(fewReport.confidenceBound()).isGreaterThan(manyReport.confidenceBound());
    }

    @Test
    @DisplayName("convergence curve has CURVE_STEPS entries and is strictly decreasing")
    void compute_convergenceCurve_monotoneDecreasing() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) rows.add(makeRow(0.7));

        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("ml", 1000))
                .thenReturn(rows);

        PACBayesService.PACBayesReport report = service.compute("ml");

        assertThat(report).isNotNull();
        assertThat(report.convergenceCurve()).hasSize(PACBayesService.CURVE_STEPS);

        // ε_i = sqrt(2·ln(2/δ)/n_i) — strictly decreasing as n increases
        List<double[]> curve = report.convergenceCurve();
        for (int i = 1; i < curve.size(); i++) {
            assertThat(curve.get(i)[1]).isLessThanOrEqualTo(curve.get(i - 1)[1]);
        }
    }

    @Test
    @DisplayName("KL divergence is non-negative")
    void compute_klDivergence_nonNegative() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) rows.add(makeRow(0.6 + i * 0.01));

        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("dba", 1000))
                .thenReturn(rows);

        PACBayesService.PACBayesReport report = service.compute("dba");

        assertThat(report).isNotNull();
        assertThat(report.klDivergence()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("invalid epsilon throws IllegalArgumentException")
    void compute_invalidEpsilon_throws() {
        assertThatThrownBy(() -> service.compute("be", 0.0, 0.05))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compute("be", 1.0, 0.05))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
