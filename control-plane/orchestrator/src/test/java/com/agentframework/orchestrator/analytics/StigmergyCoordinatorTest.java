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
 * Unit tests for {@link StigmergyCoordinator}.
 *
 * <p>Verifies the ACO pheromone lifecycle: deposit (τ += Q*reward), evaporation
 * (τ *= 1-ρ), argmax route selection, convergence detection, and the
 * {@code extractTaskType} prefix function.</p>
 */
@ExtendWith(MockitoExtension.class)
class StigmergyCoordinatorTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private StigmergyCoordinator coordinator;

    /** INITIAL_PHEROMONE constant from the service. */
    private static final double INITIAL_PHEROMONE = 0.1;

    @BeforeEach
    void setUp() {
        coordinator = new StigmergyCoordinator(taskOutcomeRepository);
        ReflectionTestUtils.setField(coordinator, "evaporationRate", 0.1);
        ReflectionTestUtils.setField(coordinator, "pheromoneAlpha",  1.0);
        ReflectionTestUtils.setField(coordinator, "depositQ",        1.0);
    }

    /** Creates a findRewardsByWorkerType row: [workerType, reward]. */
    private Object[] rewardRow(String workerType, double reward) {
        return new Object[]{workerType, reward};
    }

    // ── No data ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no data returns null")
    void analyse_noData_returnsNull() {
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(List.<Object[]>of());

        assertThat(coordinator.analyse()).isNull();
    }

    // ── Pheromone deposit ─────────────────────────────────────────────────────

    @Test
    @DisplayName("positive reward deposits pheromone — τ > INITIAL after deposit+evaporation")
    void analyse_positiveReward_pheromoneIncreasesAboveInitial() {
        // be-java has reward=0.8 → deposit: τ = (0.1 + 1.0*0.8) * (1-0.1) = 0.81
        List<Object[]> rows = new ArrayList<>();
        rows.add(rewardRow("be-java", 0.8));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        StigmergyCoordinator.StigmergyReport report = coordinator.analyse();

        assertThat(report).isNotNull();
        double tau = report.pheromoneMatrix().get("be").get("be-java");
        // After deposit + one evaporation: (0.1 + 0.8) * 0.9 = 0.81
        assertThat(tau).isCloseTo(0.81, within(0.001));
    }

    @Test
    @DisplayName("zero reward does NOT deposit pheromone — τ stays at INITIAL after evaporation")
    void analyse_zeroReward_noDeposit() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(rewardRow("be-java", 0.0));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        StigmergyCoordinator.StigmergyReport report = coordinator.analyse();

        double tau = report.pheromoneMatrix().get("be").get("be-java");
        // No deposit: τ = INITIAL * (1-ρ) = 0.1 * 0.9 = 0.09
        assertThat(tau).isCloseTo(INITIAL_PHEROMONE * 0.9, within(0.001));
    }

    // ── Recommended route (argmax) ────────────────────────────────────────────

    @Test
    @DisplayName("worker with higher reward gets higher τ and becomes recommended route")
    void analyse_twoWorkersSameTask_higherRewardRecommended() {
        // be-java: reward=0.9 → τ = (0.1 + 0.9) * 0.9 = 0.90
        // be-go:   reward=0.3 → τ = (0.1 + 0.3) * 0.9 = 0.36
        List<Object[]> rows = new ArrayList<>();
        rows.add(rewardRow("be-java", 0.9));
        rows.add(rewardRow("be-go",   0.3));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        StigmergyCoordinator.StigmergyReport report = coordinator.analyse();

        assertThat(report.recommendedRoutes()).containsKey("be");
        assertThat(report.recommendedRoutes().get("be")).isEqualTo("be-java");
    }

    // ── Evaporation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("evaporation rate is reflected in the report")
    void analyse_evaporationRate_matchesConfig() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(rewardRow("fe-react", 0.7));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        StigmergyCoordinator.StigmergyReport report = coordinator.analyse();

        assertThat(report.evaporationRate()).isEqualTo(0.1);
    }

    // ── Convergence ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("single worker per task type → convergenceDetected = true (trivially)")
    void analyse_singleWorkerPerTask_convergenceTrue() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(rewardRow("be-java",     0.8));
        rows.add(rewardRow("fe-react",    0.7));
        rows.add(rewardRow("dba-postgres", 0.9));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        StigmergyCoordinator.StigmergyReport report = coordinator.analyse();

        // Each task type has exactly 1 worker → max deviation = 0 < ε → converged
        assertThat(report.convergenceDetected()).isTrue();
    }

    @Test
    @DisplayName("two workers with very different τ → convergenceDetected = false")
    void analyse_twoWorkersHighVariance_convergenceFalse() {
        // be-java: high reward → high τ; be-go: zero reward → low τ → large deviation
        List<Object[]> rows = new ArrayList<>();
        rows.add(rewardRow("be-java", 1.0));
        rows.add(rewardRow("be-java", 1.0));
        rows.add(rewardRow("be-java", 1.0));
        rows.add(rewardRow("be-go",   0.0));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        StigmergyCoordinator.StigmergyReport report = coordinator.analyse();

        // be-java τ >> be-go τ → max deviation from mean > CONVERGENCE_EPS (0.01)
        assertThat(report.convergenceDetected()).isFalse();
    }

    // ── Top routes ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("topRoutes is non-empty and contains τ value")
    void analyse_topRoutes_containsTauSymbol() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(rewardRow("be-java", 0.8));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        StigmergyCoordinator.StigmergyReport report = coordinator.analyse();

        assertThat(report.topRoutes()).isNotEmpty();
        assertThat(report.topRoutes().get(0)).contains("τ=");
    }

    // ── extractTaskType (package-private static helper) ───────────────────────

    @Test
    @DisplayName("extractTaskType: 'be-java' → 'be'")
    void extractTaskType_dashSeparated_returnsPrefix() {
        assertThat(StigmergyCoordinator.extractTaskType("be-java")).isEqualTo("be");
    }

    @Test
    @DisplayName("extractTaskType: 'dba-postgres' → 'dba'")
    void extractTaskType_threeLetterPrefix_returnsPrefix() {
        assertThat(StigmergyCoordinator.extractTaskType("dba-postgres")).isEqualTo("dba");
    }

    @Test
    @DisplayName("extractTaskType: no dash → returns full string")
    void extractTaskType_noDash_returnsWholeString() {
        assertThat(StigmergyCoordinator.extractTaskType("worker")).isEqualTo("worker");
    }
}
