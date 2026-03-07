package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.reward.WorkerEloStats;
import com.agentframework.orchestrator.reward.WorkerEloStatsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReplicatorDynamicsService}.
 */
@ExtendWith(MockitoExtension.class)
class ReplicatorDynamicsServiceTest {

    @Mock
    private WorkerEloStatsRepository eloStatsRepository;

    @InjectMocks
    private ReplicatorDynamicsService service;

    @Test
    void analyse_emptyStats_returnsEmptyReport() {
        when(eloStatsRepository.findAllByOrderByEloRatingDesc())
                .thenReturn(Collections.emptyList());

        WorkerPopulationReport report = service.analyse();

        assertThat(report.profileCount()).isZero();
        assertThat(report.dEss()).isEqualTo(0.0);
        assertThat(report.rebalanceRecommended()).isFalse();
    }

    @Test
    void replicatorStep_uniformFitness_staysUniform() {
        double[] x = {0.5, 0.5};
        double[] fitness = {1.0, 1.0};

        double[] result = service.replicatorStep(x, fitness);

        assertThat(result[0]).isCloseTo(0.5, within(1e-9));
        assertThat(result[1]).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void replicatorStep_highFitness_growsInFrequency() {
        double[] x = {0.5, 0.5};
        double[] fitness = {2.0, 1.0}; // profile 0 has double the fitness

        double[] result = service.replicatorStep(x, fitness);

        // Profile 0 should grow: x0' = 0.5 * 2.0 / 1.5 = 0.667
        assertThat(result[0]).isGreaterThan(0.5);
        assertThat(result[1]).isLessThan(0.5);
        assertThat(result[0] + result[1]).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void analyse_twoProfiles_highFitnessDominates() {
        WorkerEloStats strong = createStats("be-java", 10, 8.0);
        WorkerEloStats weak   = createStats("be-go", 10, 2.0);
        when(eloStatsRepository.findAllByOrderByEloRatingDesc())
                .thenReturn(List.of(strong, weak));

        WorkerPopulationReport report = service.analyse();

        assertThat(report.profileCount()).isEqualTo(2);
        assertThat(report.equilibrium().get("be-java"))
                .isGreaterThan(report.equilibrium().get("be-go"));
    }

    @Test
    void analyse_largeDeviation_triggersRebalance() {
        // One profile with very high fitness, one very low → large D_ESS
        WorkerEloStats dominant = createStats("be-java", 100, 90.0);
        WorkerEloStats weak     = createStats("be-go", 100, 5.0);
        when(eloStatsRepository.findAllByOrderByEloRatingDesc())
                .thenReturn(List.of(dominant, weak));

        WorkerPopulationReport report = service.analyse();

        assertThat(report.dEss()).isGreaterThan(ReplicatorDynamicsService.REBALANCE_THRESHOLD);
        assertThat(report.rebalanceRecommended()).isTrue();
        assertThat(report.rebalanceHints()).isNotEmpty();
    }

    @Test
    void analyse_uniformFitness_noRebalance() {
        WorkerEloStats a = createStats("be-java", 10, 5.0);
        WorkerEloStats b = createStats("be-go", 10, 5.0);
        WorkerEloStats c = createStats("fe-react", 10, 5.0);
        when(eloStatsRepository.findAllByOrderByEloRatingDesc())
                .thenReturn(List.of(a, b, c));

        WorkerPopulationReport report = service.analyse();

        assertThat(report.dEss()).isCloseTo(0.0, within(1e-6));
        assertThat(report.rebalanceRecommended()).isFalse();
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private WorkerEloStats createStats(String profile, int matchCount, double cumulativeReward) {
        WorkerEloStats stats = new WorkerEloStats(profile);
        // Use reflection to set private fields for testing
        try {
            var matchField = WorkerEloStats.class.getDeclaredField("matchCount");
            matchField.setAccessible(true);
            matchField.setInt(stats, matchCount);

            var rewardField = WorkerEloStats.class.getDeclaredField("cumulativeReward");
            rewardField.setAccessible(true);
            rewardField.setDouble(stats, cumulativeReward);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set test data on WorkerEloStats", e);
        }
        return stats;
    }
}
