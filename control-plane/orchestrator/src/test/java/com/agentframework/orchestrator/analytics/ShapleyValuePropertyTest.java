package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ShapleyValue.CoalitionValueFunction;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for {@link ShapleyValue}.
 *
 * <p>Verifies the four Shapley axioms (efficiency, symmetry, null player, additivity)
 * hold for randomly generated cooperative games, plus Monte Carlo convergence.</p>
 */
class ShapleyValuePropertyTest {

    private static final double EXACT_TOLERANCE = 1e-9;
    private static final double MC_TOLERANCE = 0.15; // Monte Carlo has variance

    // ── Efficiency axiom: Σφᵢ = v(N) ────────────────────────────────────────

    @Property(tries = 200)
    void efficiencyAxiom_shapleyValuesSumToGrandCoalition(
            @ForAll @IntRange(min = 1, max = 8) int n,
            @ForAll("playerRewards") @Size(min = 1, max = 8) List<Double> rewards) {

        int players = Math.min(n, rewards.size());
        double[] r = rewards.stream().limit(players).mapToDouble(Double::doubleValue).toArray();

        CoalitionValueFunction v = coalition -> {
            double sum = 0;
            for (int p : coalition) sum += r[p];
            return sum;
        };

        double[] phi = ShapleyValue.shapleyValue(players, v);

        double grandValue = 0;
        for (double val : r) grandValue += val;

        double shapleySum = 0;
        for (double val : phi) shapleySum += val;

        assertThat(shapleySum).isCloseTo(grandValue, within(EXACT_TOLERANCE));
    }

    // ── Symmetry axiom: equal players get equal Shapley values ───────────────

    @Property(tries = 100)
    void symmetryAxiom_equalPlayersGetEqualValues(
            @ForAll @IntRange(min = 2, max = 6) int n,
            @ForAll("positiveDouble") double commonReward) {

        // All players have the same reward → all Shapley values should be equal
        double[] r = new double[n];
        Arrays.fill(r, commonReward);

        CoalitionValueFunction v = coalition -> {
            double sum = 0;
            for (int p : coalition) sum += r[p];
            return sum;
        };

        double[] phi = ShapleyValue.shapleyValue(n, v);

        double expected = commonReward; // additive game → φᵢ = rᵢ
        for (int i = 0; i < n; i++) {
            assertThat(phi[i]).isCloseTo(expected, within(EXACT_TOLERANCE));
        }
    }

    // ── Null player axiom: zero-contribution players get zero Shapley value ──

    @Property(tries = 100)
    void nullPlayerAxiom_zeroContributionGetsZeroValue(
            @ForAll @IntRange(min = 2, max = 6) int n,
            @ForAll("playerRewards") @Size(min = 2, max = 6) List<Double> rewards) {

        int players = Math.min(n, rewards.size());
        double[] r = rewards.stream().limit(players).mapToDouble(Double::doubleValue).toArray();

        // Make last player a null player (zero reward)
        r[players - 1] = 0.0;

        CoalitionValueFunction v = coalition -> {
            double sum = 0;
            for (int p : coalition) sum += r[p];
            return sum;
        };

        double[] phi = ShapleyValue.shapleyValue(players, v);

        // Null player should get zero Shapley value in an additive game
        assertThat(phi[players - 1]).isCloseTo(0.0, within(EXACT_TOLERANCE));
    }

    // ── Additivity axiom: φ(v + w) = φ(v) + φ(w) ───────────────────────────

    @Property(tries = 100)
    void additivityAxiom_shapleyOfSumEqualsSumOfShapleys(
            @ForAll @IntRange(min = 2, max = 5) int n,
            @ForAll("smallPlayerRewards") @Size(min = 2, max = 5) List<Double> rewardsA,
            @ForAll("smallPlayerRewards") @Size(min = 2, max = 5) List<Double> rewardsB) {

        int players = Math.min(n, Math.min(rewardsA.size(), rewardsB.size()));
        double[] a = rewardsA.stream().limit(players).mapToDouble(Double::doubleValue).toArray();
        double[] b = rewardsB.stream().limit(players).mapToDouble(Double::doubleValue).toArray();

        CoalitionValueFunction vA = coalition -> {
            double sum = 0; for (int p : coalition) sum += a[p]; return sum;
        };
        CoalitionValueFunction vB = coalition -> {
            double sum = 0; for (int p : coalition) sum += b[p]; return sum;
        };
        CoalitionValueFunction vSum = coalition -> {
            double sum = 0; for (int p : coalition) sum += a[p] + b[p]; return sum;
        };

        double[] phiA = ShapleyValue.shapleyValue(players, vA);
        double[] phiB = ShapleyValue.shapleyValue(players, vB);
        double[] phiSum = ShapleyValue.shapleyValue(players, vSum);

        for (int i = 0; i < players; i++) {
            assertThat(phiSum[i]).isCloseTo(phiA[i] + phiB[i], within(EXACT_TOLERANCE));
        }
    }

    // ── Monte Carlo convergence: MC approaches exact with enough samples ─────

    @Property(tries = 50)
    void monteCarloConvergesToExact(
            @ForAll @IntRange(min = 2, max = 5) int n,
            @ForAll("playerRewards") @Size(min = 2, max = 5) List<Double> rewards) {

        int players = Math.min(n, rewards.size());
        double[] r = rewards.stream().limit(players).mapToDouble(Double::doubleValue).toArray();

        CoalitionValueFunction v = coalition -> {
            double sum = 0;
            for (int p : coalition) sum += r[p];
            return sum;
        };

        double[] exact = ShapleyValue.shapleyValue(players, v);
        double[] mc = ShapleyValue.monteCarloShapley(players, v, 50000, 42L);

        for (int i = 0; i < players; i++) {
            assertThat(mc[i]).isCloseTo(exact[i], within(MC_TOLERANCE));
        }
    }

    // ── Banzhaf: non-negative for non-negative games ─────────────────────────

    @Property(tries = 100)
    void banzhafNonNegativeForNonNegativeGames(
            @ForAll @IntRange(min = 1, max = 6) int n,
            @ForAll("positiveRewards") @Size(min = 1, max = 6) List<Double> rewards) {

        int players = Math.min(n, rewards.size());
        double[] r = rewards.stream().limit(players).mapToDouble(Double::doubleValue).toArray();

        CoalitionValueFunction v = coalition -> {
            double sum = 0;
            for (int p : coalition) sum += r[p];
            return sum;
        };

        double[] beta = ShapleyValue.banzhafIndex(players, v);

        for (int i = 0; i < players; i++) {
            assertThat(beta[i]).isGreaterThanOrEqualTo(-EXACT_TOLERANCE);
        }
    }

    // ── Providers ────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<Double>> playerRewards() {
        return Arbitraries.doubles().between(-10.0, 10.0).list().ofMinSize(1).ofMaxSize(8);
    }

    @Provide
    Arbitrary<List<Double>> smallPlayerRewards() {
        return Arbitraries.doubles().between(-5.0, 5.0).list().ofMinSize(2).ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<Double>> positiveRewards() {
        return Arbitraries.doubles().between(0.0, 10.0).list().ofMinSize(1).ofMaxSize(6);
    }

    @Provide
    Arbitrary<Double> positiveDouble() {
        return Arbitraries.doubles().between(0.01, 100.0);
    }
}
