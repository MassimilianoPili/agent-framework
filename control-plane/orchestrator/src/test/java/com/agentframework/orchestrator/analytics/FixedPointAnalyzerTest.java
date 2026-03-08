package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FixedPointAnalyzer}.
 *
 * <p>Verifies the Banach Contraction Mapping iteration (T(x) = (α·x + μ)/(1+α)),
 * the Brouwer condition (always true for rewards ∈ [0,1]), convergence guarantees,
 * and the fixed-point value matching the sample mean.</p>
 */
@ExtendWith(MockitoExtension.class)
class FixedPointAnalyzerTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private FixedPointAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new FixedPointAnalyzer(taskOutcomeRepository);
        ReflectionTestUtils.setField(analyzer, "epsilon",       0.001);
        ReflectionTestUtils.setField(analyzer, "maxIterations", 100);
        ReflectionTestUtils.setField(analyzer, "maxSamples",    500);
    }

    /** Creates a timeseries row: [timestamp, reward]. */
    private Object[] row(double reward) {
        return new Object[]{"2024-01-01T00:00:00", reward};
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("null workerType throws IllegalArgumentException")
    void analyse_nullWorkerType_throws() {
        assertThatThrownBy(() -> analyzer.analyse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank workerType throws IllegalArgumentException")
    void analyse_blankWorkerType_throws() {
        assertThatThrownBy(() -> analyzer.analyse("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── No data ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no historical data returns null")
    void analyse_noData_returnsNull() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("be-java", 500))
                .thenReturn(List.of());

        assertThat(analyzer.analyse("be-java")).isNull();
    }

    // ── Convergence guarantees ────────────────────────────────────────────────

    @Test
    @DisplayName("contraction ratio < 1 — Banach theorem guarantees convergence")
    void contractionRatio_strictlyLessThanOne() {
        // L = α/(1+α) = 0.1/1.1 ≈ 0.0909; strictly < 1 by construction
        assertThat(FixedPointAnalyzer.CONTRACTION_RATIO).isLessThan(1.0);
        assertThat(FixedPointAnalyzer.CONTRACTION_RATIO).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("converges for typical rewards — fixed point equals sample mean")
    void analyse_typicalRewards_convergesAndFixedPointEqualsMean() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("be-java", 500))
                .thenReturn(List.of(row(0.8), row(0.9), row(0.7), row(0.8), row(0.8)));

        FixedPointAnalyzer.FixedPointReport report = analyzer.analyse("be-java");

        assertThat(report).isNotNull();
        assertThat(report.workerType()).isEqualTo("be-java");
        assertThat(report.converged()).isTrue();
        // Fixed point x* = sample mean = (0.8+0.9+0.7+0.8+0.8)/5 = 0.8
        assertThat(report.fixedPointValue()).isCloseTo(0.8, within(0.001));
        assertThat(report.contractionRatio()).isEqualTo(FixedPointAnalyzer.CONTRACTION_RATIO);
    }

    // ── Brouwer condition ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Brouwer condition always true — T: [0,1]→[0,1] continuous")
    void analyse_brouwerConditionAlwaysTrue() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("fe-react", 500))
                .thenReturn(List.of(row(0.3), row(0.4)));

        FixedPointAnalyzer.FixedPointReport report = analyzer.analyse("fe-react");

        assertThat(report.brouwerConditionMet()).isTrue();
    }

    // ── Convergence curve ─────────────────────────────────────────────────────

    @Test
    @DisplayName("convergence curve is non-empty and errors are non-negative")
    void analyse_convergenceCurve_nonNegativeErrors() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("dba-postgres", 500))
                .thenReturn(List.of(row(0.5), row(0.5), row(0.5)));

        FixedPointAnalyzer.FixedPointReport report = analyzer.analyse("dba-postgres");

        assertThat(report.convergenceCurve()).isNotEmpty();
        report.convergenceCurve().forEach(point ->
                assertThat(point[1]).isGreaterThanOrEqualTo(0.0));
    }

    // ── Single observation ────────────────────────────────────────────────────

    @Test
    @DisplayName("single observation → fixed point equals that observation")
    void analyse_singleObservation_fixedPointEqualsValue() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("ops-k8s", 500))
                .thenReturn(List.of(row(0.6)));

        FixedPointAnalyzer.FixedPointReport report = analyzer.analyse("ops-k8s");

        assertThat(report.fixedPointValue()).isCloseTo(0.6, within(0.001));
        assertThat(report.converged()).isTrue();
    }

    // ── Extreme values ────────────────────────────────────────────────────────

    @Test
    @DisplayName("all rewards = 0 → fixed point is 0, still converges")
    void analyse_allZeroRewards_fixedPointZero() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("be-go", 500))
                .thenReturn(List.of(row(0.0), row(0.0), row(0.0)));

        FixedPointAnalyzer.FixedPointReport report = analyzer.analyse("be-go");

        assertThat(report.fixedPointValue()).isCloseTo(0.0, within(0.001));
        assertThat(report.converged()).isTrue();
    }
}
