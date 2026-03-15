package com.agentframework.orchestrator.analytics;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for {@link HedgeAlgorithm}.
 *
 * <p>Verifies probability distribution invariants (sum-to-one, non-negativity)
 * are maintained through arbitrary sequences of weight updates, plus correctness
 * of the learning rate formula and regret bound.</p>
 */
class HedgeAlgorithmPropertyTest {

    private static final double SUM_TOLERANCE = 1e-9;

    // ── Uniform weights sum to 1 ─────────────────────────────────────────────

    @Property(tries = 200)
    void uniformWeightsSumToOne(@ForAll @IntRange(min = 1, max = 100) int n) {
        double[] w = HedgeAlgorithm.uniformWeights(n);

        assertThat(w).hasSize(n);
        assertThat(Arrays.stream(w).sum()).isCloseTo(1.0, within(SUM_TOLERANCE));

        // All weights equal
        double expected = 1.0 / n;
        for (double wi : w) {
            assertThat(wi).isCloseTo(expected, within(SUM_TOLERANCE));
        }
    }

    // ── Updated weights always sum to 1 ──────────────────────────────────────

    @Property(tries = 500)
    void updatedWeightsSumToOne(
            @ForAll @IntRange(min = 1, max = 20) int n,
            @ForAll("losses") @Size(min = 1, max = 20) List<Double> lossValues,
            @ForAll("learningRate") double eta) {

        int experts = Math.min(n, lossValues.size());
        double[] weights = HedgeAlgorithm.uniformWeights(experts);
        double[] losses = lossValues.stream().limit(experts).mapToDouble(Double::doubleValue).toArray();

        double[] updated = HedgeAlgorithm.update(weights, losses, eta);

        assertThat(updated).hasSize(experts);
        assertThat(Arrays.stream(updated).sum()).isCloseTo(1.0, within(SUM_TOLERANCE));
    }

    // ── Weights always non-negative ──────────────────────────────────────────

    @Property(tries = 500)
    void weightsAlwaysNonNegative(
            @ForAll @IntRange(min = 1, max = 20) int n,
            @ForAll("losses") @Size(min = 1, max = 20) List<Double> lossValues,
            @ForAll("wideEta") double eta) {

        int experts = Math.min(n, lossValues.size());
        double[] weights = HedgeAlgorithm.uniformWeights(experts);
        double[] losses = lossValues.stream().limit(experts).mapToDouble(Double::doubleValue).toArray();

        double[] updated = HedgeAlgorithm.update(weights, losses, eta);

        for (double w : updated) {
            assertThat(w).isGreaterThanOrEqualTo(0.0);
        }
    }

    // ── Multi-round stability: repeated updates preserve sum-to-1 ────────────

    @Property(tries = 100)
    void multiRoundStability(
            @ForAll @IntRange(min = 2, max = 10) int n,
            @ForAll @IntRange(min = 5, max = 50) int rounds) {

        double[] weights = HedgeAlgorithm.uniformWeights(n);
        double eta = HedgeAlgorithm.learningRate(n, rounds);
        java.util.Random rng = new java.util.Random(42);

        for (int t = 0; t < rounds; t++) {
            double[] losses = new double[n];
            for (int i = 0; i < n; i++) {
                losses[i] = rng.nextDouble(); // random loss in [0, 1)
            }
            weights = HedgeAlgorithm.update(weights, losses, eta);

            // Invariant must hold at every round
            assertThat(Arrays.stream(weights).sum())
                    .isCloseTo(1.0, within(SUM_TOLERANCE));
            for (double w : weights) {
                assertThat(w).isGreaterThanOrEqualTo(0.0);
            }
        }
    }

    // ── Zero loss preserves relative proportions ─────────────────────────────

    @Property(tries = 200)
    void zeroLossPreservesWeights(@ForAll @IntRange(min = 2, max = 10) int n) {
        double[] weights = HedgeAlgorithm.uniformWeights(n);
        double eta = 0.5;
        double[] zeroLosses = new double[n]; // all zeros

        double[] updated = HedgeAlgorithm.update(weights, zeroLosses, eta);

        // exp(-η × 0) = 1 for all experts → weights unchanged
        for (int i = 0; i < n; i++) {
            assertThat(updated[i]).isCloseTo(weights[i], within(SUM_TOLERANCE));
        }
    }

    // ── Lower loss expert gains weight ───────────────────────────────────────

    @Property(tries = 200)
    void lowerLossExpertGainsWeight(
            @ForAll("smallEta") double eta) {

        double[] weights = HedgeAlgorithm.uniformWeights(3);
        // Expert 0 has no loss, expert 2 has highest loss
        double[] losses = {0.0, 0.5, 1.0};

        double[] updated = HedgeAlgorithm.update(weights, losses, eta);

        // Expert 0 (zero loss) should have highest weight
        assertThat(updated[0]).isGreaterThan(updated[1]);
        assertThat(updated[1]).isGreaterThan(updated[2]);
    }

    // ── Learning rate: η = √(ln(K)/T) ───────────────────────────────────────

    @Property(tries = 200)
    void learningRateFormula(
            @ForAll @IntRange(min = 2, max = 100) int k,
            @ForAll @IntRange(min = 1, max = 100000) int t) {

        double eta = HedgeAlgorithm.learningRate(k, t);
        double expected = Math.sqrt(Math.log(k) / t);

        assertThat(eta).isCloseTo(expected, within(1e-12));
        assertThat(eta).isGreaterThan(0.0);
    }

    // ── Regret bound: √(T × ln(K)) ──────────────────────────────────────────

    @Property(tries = 200)
    void regretBoundFormula(
            @ForAll @IntRange(min = 2, max = 100) int k,
            @ForAll @IntRange(min = 1, max = 100000) int t) {

        double bound = HedgeAlgorithm.regretBound(k, t);
        double expected = Math.sqrt((double) t * Math.log(k));

        assertThat(bound).isCloseTo(expected, within(1e-10));
    }

    // ── Regret sublinear: bound/T → 0 as T → ∞ ──────────────────────────────

    @Property(tries = 50)
    void regretIsSublinear(@ForAll @IntRange(min = 2, max = 20) int k) {
        // Regret / T should decrease as T increases
        double prev = Double.MAX_VALUE;
        for (int t : new int[]{100, 1000, 10000, 100000}) {
            double normalized = HedgeAlgorithm.regretBound(k, t) / t;
            assertThat(normalized).isLessThan(prev);
            prev = normalized;
        }
    }

    // ── selectExpert returns valid index ──────────────────────────────────────

    @Property(tries = 200)
    void selectExpertReturnsValidIndex(@ForAll @IntRange(min = 1, max = 50) int n) {
        double[] weights = HedgeAlgorithm.uniformWeights(n);
        int selected = HedgeAlgorithm.selectExpert(weights);
        assertThat(selected).isBetween(0, n - 1);
    }

    // ── Providers ────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<Double>> losses() {
        return Arbitraries.doubles().between(0.0, 1.0).list().ofMinSize(1).ofMaxSize(20);
    }

    @Provide
    Arbitrary<Double> learningRate() {
        return Arbitraries.doubles().between(0.01, 2.0);
    }

    @Provide
    Arbitrary<Double> wideEta() {
        return Arbitraries.doubles().between(0.01, 5.0);
    }

    @Provide
    Arbitrary<Double> smallEta() {
        return Arbitraries.doubles().between(0.01, 1.0);
    }
}
