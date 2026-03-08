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
 * Unit tests for {@link ErgodicBudgetAnalyzer}.
 *
 * <p>Covers null/empty data, near-ergodic (constant rewards),
 * mildly and strongly non-ergodic regimes, and Kelly fraction bounds.</p>
 */
@ExtendWith(MockitoExtension.class)
class ErgodicBudgetAnalyzerTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private ErgodicBudgetAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ErgodicBudgetAnalyzer(taskOutcomeRepository);
        ReflectionTestUtils.setField(analyzer, "maxSamples", 500);
    }

    /** Row: [worker_type (String), actual_reward (Double)] */
    private Object[] row(double reward) {
        return new Object[]{"be-java", reward};
    }

    // ── Empty / null data ───────────────────────────────────────────────────────

    @Test
    @DisplayName("returns null when no outcomes exist")
    void analyze_noOutcomes_returnsNull() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("unknown", 500))
                .thenReturn(List.of());

        assertThat(analyzer.analyze("unknown")).isNull();
    }

    @Test
    @DisplayName("returns null when fewer than 5 positive samples")
    void analyze_fewSamples_returnsNull() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row(0.8));
        rows.add(row(0.7));
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("be-java", 500))
                .thenReturn(rows);

        assertThat(analyzer.analyze("be-java")).isNull();
    }

    // ── Near-ergodic regime ─────────────────────────────────────────────────────

    @Test
    @DisplayName("constant rewards → ergodicity gap ≈ 0, NEAR_ERGODIC regime")
    void analyze_constantRewards_nearErgodic() {
        // AM(x, x, ...) = GM(x, x, ...) = x → gap = 0
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(row(0.75));

        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("ops", 500))
                .thenReturn(rows);

        ErgodicBudgetAnalyzer.ErgodicReport report = analyzer.analyze("ops");

        assertThat(report).isNotNull();
        assertThat(report.ergodicityGap()).isCloseTo(0.0, within(1e-9));
        assertThat(report.regime()).isEqualTo(ErgodicBudgetAnalyzer.ErgodicsRegime.NEAR_ERGODIC);
        // ensemble == time average
        assertThat(report.ensembleAverage()).isCloseTo(report.timeAverage(), within(1e-9));
    }

    // ── Ergodicity gap is always non-negative (AM ≥ GM) ──────────────────────────

    @Test
    @DisplayName("ergodicity gap is always ≥ 0 (AM–GM inequality)")
    void analyze_amGmInequality_gapNonNegative() {
        // High-variance rewards: 0.1, 0.9 alternating
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(row(i % 2 == 0 ? 0.1 : 0.9));
        }

        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("fe-ts", 500))
                .thenReturn(rows);

        ErgodicBudgetAnalyzer.ErgodicReport report = analyzer.analyze("fe-ts");

        assertThat(report).isNotNull();
        assertThat(report.ergodicityGap()).isGreaterThanOrEqualTo(0.0);
        // ensemble_avg = 0.5, time_avg = GM(0.1^5, 0.9^5)^(1/10) < 0.5
        assertThat(report.ensembleAverage()).isGreaterThanOrEqualTo(report.timeAverage());
    }

    // ── Non-ergodic regime ──────────────────────────────────────────────────────

    @Test
    @DisplayName("high-variance rewards → large ergodicity gap, non-ergodic regime")
    void analyze_highVariance_nonErgodic() {
        // Extreme variance: 0.01 and 0.99 in equal proportion → large gap
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add(row(i % 2 == 0 ? 0.01 : 0.99));
        }

        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("ml", 500))
                .thenReturn(rows);

        ErgodicBudgetAnalyzer.ErgodicReport report = analyzer.analyze("ml");

        assertThat(report).isNotNull();
        assertThat(report.ergodicityGap()).isGreaterThan(0.10);
        assertThat(report.regime()).isEqualTo(ErgodicBudgetAnalyzer.ErgodicsRegime.STRONGLY_NON_ERGODIC);
    }

    // ── Kelly fraction bounds ───────────────────────────────────────────────────

    @Test
    @DisplayName("Kelly fraction is in [0, 1]")
    void analyze_kellyFraction_bounded() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(row(0.5 + i * 0.04));

        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("dba", 500))
                .thenReturn(rows);

        ErgodicBudgetAnalyzer.ErgodicReport report = analyzer.analyze("dba");

        assertThat(report).isNotNull();
        assertThat(report.kellyFraction()).isBetween(0.0, 1.0);
        assertThat(report.recommendedBudgetShare()).isBetween(0.0, 1.0);
    }

    // ── Worker type included in report ──────────────────────────────────────────

    @Test
    @DisplayName("report preserves the worker type label")
    void analyze_workerType_preserved() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(row(0.7));

        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("fe-react", 500))
                .thenReturn(rows);

        ErgodicBudgetAnalyzer.ErgodicReport report = analyzer.analyze("fe-react");

        assertThat(report).isNotNull();
        assertThat(report.workerType()).isEqualTo("fe-react");
        assertThat(report.sampleCount()).isEqualTo(10);
    }
}
