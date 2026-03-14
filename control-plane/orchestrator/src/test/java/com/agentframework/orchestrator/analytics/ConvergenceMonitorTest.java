package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ConvergenceMonitor.ConvergenceReport;
import com.agentframework.orchestrator.analytics.ConvergenceMonitor.ProfileConvergence;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConvergenceMonitor}.
 *
 * <p>Verifies sliding-window variance computation, convergence detection,
 * and edge cases (insufficient data, all-same values, oscillating values).</p>
 */
@ExtendWith(MockitoExtension.class)
class ConvergenceMonitorTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private ConvergenceMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new ConvergenceMonitor(taskOutcomeRepository);
        ReflectionTestUtils.setField(monitor, "convergenceWindow", 5);
        ReflectionTestUtils.setField(monitor, "convergenceThreshold", 0.005);
    }

    @Test
    @DisplayName("checkConvergence with no data returns insufficient data status")
    void checkConvergence_noData() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", 50))
                .thenReturn(List.of());

        ConvergenceReport report = monitor.checkConvergence("BE");

        assertThat(report.workerType()).isEqualTo("BE");
        assertThat(report.profiles()).hasSize(1);
        assertThat(report.profiles().get(0).status()).isEqualTo("insufficient data");
        assertThat(report.profiles().get(0).converged()).isFalse();
        assertThat(report.allConverged()).isFalse();
    }

    @Test
    @DisplayName("checkConvergence with single observation returns insufficient data")
    void checkConvergence_singleObservation() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", 50))
                .thenReturn(rewardRows(0.7));

        ConvergenceReport report = monitor.checkConvergence("BE");

        assertThat(report.profiles().get(0).observations()).isEqualTo(1);
        assertThat(report.profiles().get(0).status()).isEqualTo("insufficient data");
    }

    @Test
    @DisplayName("checkConvergence with constant rewards is converged")
    void checkConvergence_constantRewards_converged() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", 50))
                .thenReturn(rewardRows(0.8, 0.8, 0.8, 0.8, 0.8));

        ConvergenceReport report = monitor.checkConvergence("BE");

        assertThat(report.allConverged()).isTrue();
        assertThat(report.profiles().get(0).variance()).isEqualTo(0.0);
        assertThat(report.profiles().get(0).status()).isEqualTo("converged");
    }

    @Test
    @DisplayName("checkConvergence with oscillating rewards is not converged")
    void checkConvergence_oscillatingRewards_notConverged() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", 50))
                .thenReturn(rewardRows(0.1, 0.9, 0.1, 0.9, 0.1, 0.9, 0.1));

        ConvergenceReport report = monitor.checkConvergence("BE");

        assertThat(report.allConverged()).isFalse();
        ProfileConvergence p = report.profiles().get(0);
        assertThat(p.variance()).isGreaterThan(0.005);
        assertThat(p.status()).isEqualTo("oscillating");
    }

    @Test
    @DisplayName("checkConvergence with near-constant rewards is converged")
    void checkConvergence_nearConstant_converged() {
        // Variance of [0.500, 0.501, 0.499, 0.500, 0.502] ≈ 1.3e-6 < 0.005
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", 50))
                .thenReturn(rewardRows(0.500, 0.501, 0.499, 0.500, 0.502));

        ConvergenceReport report = monitor.checkConvergence("BE");

        assertThat(report.allConverged()).isTrue();
        assertThat(report.profiles().get(0).variance()).isLessThan(0.005);
    }

    @Test
    @DisplayName("checkConvergence uses sliding window (last N values)")
    void checkConvergence_usesWindow() {
        // First 3 values are wild, last 5 are stable — window=5 should see convergence
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", 50))
                .thenReturn(rewardRows(0.1, 0.9, 0.2, 0.7, 0.7, 0.7, 0.7, 0.7));

        ConvergenceReport report = monitor.checkConvergence("BE");

        assertThat(report.allConverged()).isTrue();
        assertThat(report.profiles().get(0).observations()).isEqualTo(8);
    }

    @Test
    @DisplayName("computeVariance returns zero for identical values")
    void computeVariance_identical() {
        double variance = monitor.computeVariance(List.of(1.0, 1.0, 1.0));
        assertThat(variance).isEqualTo(0.0);
    }

    @Test
    @DisplayName("computeVariance returns correct value for known data")
    void computeVariance_knownData() {
        // [2, 4, 6] → mean=4, variance = ((4+0+4)/2) = 4.0 (Bessel's)
        double variance = monitor.computeVariance(List.of(2.0, 4.0, 6.0));
        assertThat(variance).isCloseTo(4.0, within(1e-10));
    }

    @Test
    @DisplayName("computeVariance returns zero for single value")
    void computeVariance_singleValue() {
        double variance = monitor.computeVariance(List.of(5.0));
        assertThat(variance).isEqualTo(0.0);
    }

    @Test
    @DisplayName("report contains correct metadata")
    void reportMetadata() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("FE", 50))
                .thenReturn(rewardRows(0.5, 0.5));

        ConvergenceReport report = monitor.checkConvergence("FE");

        assertThat(report.windowSize()).isEqualTo(5);
        assertThat(report.varianceThreshold()).isEqualTo(0.005);
        assertThat(report.workerType()).isEqualTo("FE");
    }

    /**
     * Helper: creates reward rows for findRewardTimeseriesByWorkerType.
     * Each row is [workerType(String), actual_reward(Number)].
     */
    private List<Object[]> rewardRows(double... rewards) {
        List<Object[]> rows = new ArrayList<>();
        for (double r : rewards) {
            rows.add(new Object[]{"BE", r});
        }
        return rows;
    }
}
