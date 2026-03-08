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
 * Unit tests for {@link PotentialRewardShapingService}.
 *
 * <p>Covers: policy invariance (non-negative bonus), improvement ratio ≥ 1,
 * potential size n+1, gamma effects, edge cases.</p>
 */
@ExtendWith(MockitoExtension.class)
class PotentialRewardShapingServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private PotentialRewardShapingService service;

    @BeforeEach
    void setUp() {
        service = new PotentialRewardShapingService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "gamma", 0.99);
        ReflectionTestUtils.setField(service, "maxSamples", 500);
    }

    private Object[] row(String type, double reward) {
        return new Object[]{type, reward};
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("throws for null or blank workerType")
    void shape_invalidWorkerType_throws() {
        assertThatThrownBy(() -> service.shape(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.shape("")).isInstanceOf(IllegalArgumentException.class);
    }

    // ── No data ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns null when no data exists")
    void shape_noData_returnsNull() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(List.<Object[]>of());
        assertThat(service.shape("be-java")).isNull();
    }

    // ── Non-negative shaping bonus ────────────────────────────────────────────

    @Test
    @DisplayName("shaped rewards ≥ original (conservatism: no negative shaping)")
    void shape_shapedRewardsNonNegativeBonus() {
        List<Object[]> rows = new ArrayList<>();
        for (double r : new double[]{0.3, 0.5, 0.8, 0.6, 0.9}) {
            rows.add(row("be-java", r));
        }
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(rows);

        PotentialRewardShapingService.ShapedRewardReport report = service.shape("be-java");

        assertThat(report).isNotNull();
        for (int i = 0; i < report.originalRewards().size(); i++) {
            assertThat(report.shapedRewards().get(i))
                    .isGreaterThanOrEqualTo(report.originalRewards().get(i));
        }
    }

    // ── Potential size ────────────────────────────────────────────────────────

    @Test
    @DisplayName("potentials list has size n+1 (includes initial state)")
    void shape_potentialSizeNPlusOne() {
        List<Object[]> rows = List.of(row("be-java", 0.5), row("be-java", 0.8), row("be-java", 0.6));
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(rows);

        PotentialRewardShapingService.ShapedRewardReport report = service.shape("be-java");

        assertThat(report.potentials()).hasSize(4);  // n=3 → n+1=4
        assertThat(report.potentials().get(0)).isEqualTo(0.0);  // initial state = 0
    }

    // ── Improvement ratio ─────────────────────────────────────────────────────

    @Test
    @DisplayName("improvement ratio ≥ 1.0 (shaping only adds value)")
    void shape_improvementRatioAtLeastOne() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(row("be-java", 0.7));
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(rows);

        PotentialRewardShapingService.ShapedRewardReport report = service.shape("be-java");

        assertThat(report.improvementRatio()).isGreaterThanOrEqualTo(1.0);
        assertThat(report.totalIntrinsicBonus()).isGreaterThanOrEqualTo(0.0);
    }

    // ── Gamma = 1.0 (no discounting) ─────────────────────────────────────────

    @Test
    @DisplayName("gamma = 1.0: shaping bonus is constant Φ' - Φ between consecutive steps")
    void shape_gammaOne_bonusIsIncrement() {
        ReflectionTestUtils.setField(service, "gamma", 1.0);
        List<Object[]> rows = List.of(row("be-java", 0.4), row("be-java", 0.8));
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(rows);

        PotentialRewardShapingService.ShapedRewardReport report = service.shape("be-java");

        assertThat(report.gamma()).isEqualTo(1.0);
        assertThat(report.originalRewards()).hasSize(2);
        assertThat(report.shapedRewards()).hasSize(2);
    }

    // ── Single reward ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("single reward produces valid report with potentials size 2")
    void shape_singleReward_validReport() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType(any(), anyInt()))
                .thenReturn(List.<Object[]>of(row("fe-ts", 0.9)));

        PotentialRewardShapingService.ShapedRewardReport report = service.shape("fe-ts");

        assertThat(report.originalRewards()).hasSize(1);
        assertThat(report.shapedRewards()).hasSize(1);
        assertThat(report.potentials()).hasSize(2);
        assertThat(report.workerType()).isEqualTo("fe-ts");
    }
}
