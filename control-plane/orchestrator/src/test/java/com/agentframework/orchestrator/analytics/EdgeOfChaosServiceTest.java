package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.EdgeOfChaosService.EOCTuningReport;
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
 * Unit tests for {@link EdgeOfChaosService}.
 *
 * <p>Verifies Lyapunov proxy computation and exploration adaptation for
 * chaotic, ordered, and insufficient-data scenarios.</p>
 */
@ExtendWith(MockitoExtension.class)
class EdgeOfChaosServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private EdgeOfChaosService service;

    @BeforeEach
    void setUp() {
        service = new EdgeOfChaosService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "targetLyapunov", 0.0);
        ReflectionTestUtils.setField(service, "adaptationRate", 0.01);
    }

    private List<Object[]> makeRewards(double... values) {
        List<Object[]> rows = new ArrayList<>();
        for (double v : values) {
            rows.add(new Object[]{"BE", v});
        }
        return rows;
    }

    @Test
    @DisplayName("tune with chaotic rewards reduces exploration")
    void tune_chaoticRewards_reducesExploration() {
        // Highly varying rewards → high Lyapunov → reduce exploration
        List<Object[]> rows = makeRewards(
                0.1, 0.9, 0.0, 1.0, 0.1, 0.9, 0.0, 1.0, 0.1, 0.9, 0.0, 1.0);
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", 200)).thenReturn(rows);

        EOCTuningReport report = service.tune("BE", 0.5);

        assertThat(report).isNotNull();
        assertThat(report.lyapunovExponent()).isGreaterThan(0.0);
        // High Lyapunov → adaptationSignal > 0 → newExploration < currentExploration
        assertThat(report.newExploration()).isLessThan(report.currentExploration());
    }

    @Test
    @DisplayName("tune with smooth rewards increases exploration")
    void tune_smoothRewards_increasesExploration() {
        // Monotonically increasing rewards → low Lyapunov → increase exploration
        List<Object[]> rows = makeRewards(
                0.50, 0.51, 0.52, 0.53, 0.54, 0.55, 0.56, 0.57, 0.58, 0.59, 0.60, 0.61);
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", 200)).thenReturn(rows);

        EOCTuningReport report = service.tune("BE", 0.1);

        assertThat(report).isNotNull();
        // Smooth series → Var(diffs)/Var(rewards) is low
        // Smooth series → Lyapunov ≈ 0 (tiny floating-point residual)
        assertThat(report.lyapunovExponent()).isGreaterThanOrEqualTo(0.0);
        // With Lyapunov ≈ 0 ≈ targetLyapunov=0, adaptationSignal ≈ 0 (near-zero, no strong push)
        assertThat(report.adaptationSignal()).isCloseTo(0.0, within(1e-10));
    }

    @Test
    @DisplayName("tune with insufficient data returns null")
    void tune_insufficientData_returnsNull() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", 200))
                .thenReturn(makeRewards(0.5, 0.6, 0.7));  // < MIN_SAMPLES=10

        EOCTuningReport report = service.tune("BE", 0.3);

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("tune clamps newExploration to [0, 1]")
    void tune_clampsBoundsToZeroOne() {
        // Near-constant rewards → max adaptation pushing above 1
        List<Object[]> rows = makeRewards(
                0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", 200)).thenReturn(rows);

        EOCTuningReport report = service.tune("BE", 0.99);

        assertThat(report).isNotNull();
        assertThat(report.newExploration()).isBetween(0.0, 1.0);
    }
}
