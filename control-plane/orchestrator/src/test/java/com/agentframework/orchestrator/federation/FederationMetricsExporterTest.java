package com.agentframework.orchestrator.federation;

import com.agentframework.common.privacy.DifferentialPrivacyMechanism;
import com.agentframework.common.privacy.PrivacyBudget;
import com.agentframework.common.privacy.PrivatizedMetrics;
import com.agentframework.orchestrator.reward.WorkerEloStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FederationMetricsExporter} (#43 — Differential Privacy).
 */
@DisplayName("FederationMetricsExporter (#43) — Federated DP metrics")
class FederationMetricsExporterTest {

    /** Deterministic mechanism that adds fixed noise = sensitivity (for testability). */
    private final DifferentialPrivacyMechanism fixedNoiseMechanism =
            new DifferentialPrivacyMechanism() {
                @Override
                public double privatize(double trueValue, double sensitivity, double epsilon) {
                    // Add exactly +sensitivity as noise (deterministic for testing)
                    return trueValue + sensitivity;
                }

                @Override
                public double remainingBudget(double initialEpsilon, int queriesUsed) {
                    return Math.max(0.0, 1.0 - queriesUsed * initialEpsilon);
                }
            };

    private FederationPrivacyProperties properties;
    private PrivacyBudget budget;
    private FederationMetricsExporter exporter;

    @BeforeEach
    void setUp() {
        properties = new FederationPrivacyProperties(
                true,    // enabled
                1.0,     // epsilon
                100,     // maxQueriesPerDay
                32.0,    // eloSensitivity
                2.0      // rewardSensitivity
        );
        budget = new PrivacyBudget(100);
        exporter = new FederationMetricsExporter(properties, fixedNoiseMechanism, budget);
    }

    // ── Export metrics ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("exportMetrics")
    class ExportMetricsTests {

        @Test
        @DisplayName("adds noise to ELO and reward, bins match count")
        void exportMetrics_addsNoiseAndBins() {
            WorkerEloStats stats = createStats("be-java", 1600.0, 0.75, 27);

            PrivatizedMetrics result = exporter.exportMetrics(stats);

            // Fixed mechanism: privatize(x, s, ε) = x + s
            // So noisyElo = stats.getEloRating() + eloSensitivity(32)
            assertThat(result.noisyEloRating()).isEqualTo(stats.getEloRating() + 32.0);
            // noisyReward = stats.avgReward() + rewardSensitivity(2)
            assertThat(result.noisyAverageReward()).isEqualTo(stats.avgReward() + 2.0);
            assertThat(result.approximateMatchCount()).isEqualTo(30);  // 27 → binned to 30
            assertThat(result.workerProfile()).isEqualTo("be-java");
            assertThat(result.epsilonUsed()).isEqualTo(1.0);
            assertThat(result.exportedAt()).isNotNull();
        }

        @Test
        @DisplayName("records query in budget")
        void exportMetrics_recordsBudgetQuery() {
            WorkerEloStats stats = createStats("be-java", 1600.0, 0.5, 10);

            assertThat(budget.queriesUsedToday()).isZero();

            exporter.exportMetrics(stats);

            assertThat(budget.queriesUsedToday()).isEqualTo(1);
            assertThat(budget.remaining()).isEqualTo(99);
        }

        @Test
        @DisplayName("budget exhausted throws PrivacyBudgetExhaustedException")
        void exportMetrics_budgetExhausted_throws() {
            PrivacyBudget tinyBudget = new PrivacyBudget(1);
            FederationMetricsExporter tinyExporter =
                    new FederationMetricsExporter(properties, fixedNoiseMechanism, tinyBudget);

            WorkerEloStats stats = createStats("be-java", 1600.0, 0.5, 10);

            // First call succeeds
            tinyExporter.exportMetrics(stats);

            // Second call: budget exhausted
            assertThatThrownBy(() -> tinyExporter.exportMetrics(stats))
                    .isInstanceOf(PrivacyBudgetExhaustedException.class)
                    .hasMessageContaining("exhausted");
        }
    }

    // ── Convenience methods ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Convenience privatisation methods")
    class ConvenienceTests {

        @Test
        @DisplayName("privatizeElo adds noise via mechanism")
        void privatizeElo_addsNoise() {
            double noisy = exporter.privatizeElo(1600.0);
            assertThat(noisy).isEqualTo(1600.0 + 32.0); // fixed mechanism: + sensitivity
        }

        @Test
        @DisplayName("privatizeReward adds noise via mechanism")
        void privatizeReward_addsNoise() {
            double noisy = exporter.privatizeReward(0.8);
            assertThat(noisy).isEqualTo(0.8 + 2.0); // fixed mechanism: + sensitivity
        }
    }

    // ── binToNearest10 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("binToNearest10 (k-anonymity)")
    class BinTests {

        @Test
        @DisplayName("bins count to nearest 10 with minimum of 10")
        void binToNearest10_variousValues() {
            assertThat(FederationMetricsExporter.binToNearest10(27)).isEqualTo(30);
            assertThat(FederationMetricsExporter.binToNearest10(14)).isEqualTo(10);
            assertThat(FederationMetricsExporter.binToNearest10(5)).isEqualTo(10);   // min 10
            assertThat(FederationMetricsExporter.binToNearest10(0)).isEqualTo(10);   // min 10
            assertThat(FederationMetricsExporter.binToNearest10(25)).isEqualTo(30);  // rounds up at midpoint
            assertThat(FederationMetricsExporter.binToNearest10(100)).isEqualTo(100);
            assertThat(FederationMetricsExporter.binToNearest10(99)).isEqualTo(100);
        }
    }

    // ── Budget introspection ─────────────────────────────────────────────────

    @Test
    @DisplayName("getRemainingBudget and getQueriesUsedToday reflect state")
    void budgetIntrospection() {
        assertThat(exporter.getRemainingBudget()).isEqualTo(100);
        assertThat(exporter.getQueriesUsedToday()).isZero();

        exporter.exportMetrics(createStats("be-java", 1600.0, 0.5, 10));

        assertThat(exporter.getRemainingBudget()).isEqualTo(99);
        assertThat(exporter.getQueriesUsedToday()).isEqualTo(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a WorkerEloStats with desired match count.
     * applyEloUpdate increments matchCount; recordReward builds cumulativeReward.
     * avgReward() = cumulativeReward / matchCount.
     */
    private static WorkerEloStats createStats(String profile, double elo, double avgReward, int matches) {
        WorkerEloStats stats = new WorkerEloStats(profile);
        for (int i = 0; i < matches; i++) {
            stats.applyEloUpdate(1600.0, true); // increments matchCount
            stats.recordReward(avgReward);       // adds to cumulativeReward
        }
        return stats;
    }
}
