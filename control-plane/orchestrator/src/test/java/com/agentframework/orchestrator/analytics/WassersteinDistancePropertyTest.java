package com.agentframework.orchestrator.analytics;

import net.jqwik.api.*;
import net.jqwik.api.constraints.Size;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for {@link WassersteinDistance}.
 *
 * <p>Verifies the metric space axioms (non-negativity, identity, symmetry,
 * triangle inequality) hold for randomly generated empirical distributions.</p>
 */
class WassersteinDistancePropertyTest {

    private static final double TOLERANCE = 1e-10;

    // ── Non-negativity: W₁(P, Q) ≥ 0 ───────────────────────────────────────

    @Property(tries = 500)
    void nonNegativity(
            @ForAll("distribution") List<Double> p,
            @ForAll("distribution") List<Double> q) {

        double w = WassersteinDistance.w1(toArray(p), toArray(q));
        assertThat(w).isGreaterThanOrEqualTo(-TOLERANCE);
    }

    // ── Identity: W₁(P, P) = 0 ──────────────────────────────────────────────

    @Property(tries = 500)
    void identityOfIndiscernibles(@ForAll("distribution") List<Double> p) {
        double[] arr = toArray(p);
        double w = WassersteinDistance.w1(arr, arr);
        assertThat(w).isCloseTo(0.0, within(TOLERANCE));
    }

    // ── Symmetry: W₁(P, Q) = W₁(Q, P) ──────────────────────────────────────

    @Property(tries = 500)
    void symmetry(
            @ForAll("distribution") List<Double> p,
            @ForAll("distribution") List<Double> q) {

        double[] pArr = toArray(p);
        double[] qArr = toArray(q);

        double w_pq = WassersteinDistance.w1(pArr, qArr);
        double w_qp = WassersteinDistance.w1(qArr, pArr);

        assertThat(w_pq).isCloseTo(w_qp, within(TOLERANCE));
    }

    // ── Triangle inequality: W₁(P, R) ≤ W₁(P, Q) + W₁(Q, R) ───────────────

    @Property(tries = 300)
    void triangleInequality(
            @ForAll("equalSizeDistribution") List<Double> p,
            @ForAll("equalSizeDistribution") List<Double> q,
            @ForAll("equalSizeDistribution") List<Double> r) {

        double[] pArr = toArray(p);
        double[] qArr = toArray(q);
        double[] rArr = toArray(r);

        double w_pr = WassersteinDistance.w1(pArr, rArr);
        double w_pq = WassersteinDistance.w1(pArr, qArr);
        double w_qr = WassersteinDistance.w1(qArr, rArr);

        // Triangle inequality with small tolerance for floating-point
        assertThat(w_pr).isLessThanOrEqualTo(w_pq + w_qr + 1e-9);
    }

    // ── Translation invariance: W₁(P+c, Q+c) = W₁(P, Q) ────────────────────

    @Property(tries = 300)
    void translationInvariance(
            @ForAll("distribution") List<Double> p,
            @ForAll("distribution") List<Double> q,
            @ForAll("shift") double c) {

        double[] pArr = toArray(p);
        double[] qArr = toArray(q);

        double[] pShifted = new double[pArr.length];
        double[] qShifted = new double[qArr.length];
        for (int i = 0; i < pArr.length; i++) pShifted[i] = pArr[i] + c;
        for (int i = 0; i < qArr.length; i++) qShifted[i] = qArr[i] + c;

        double original = WassersteinDistance.w1(pArr, qArr);
        double shifted = WassersteinDistance.w1(pShifted, qShifted);

        assertThat(shifted).isCloseTo(original, within(1e-8));
    }

    // ── Scale equivariance: W₁(aP, aQ) = |a| × W₁(P, Q) ───────────────────

    @Property(tries = 200)
    void scaleEquivariance(
            @ForAll("distribution") List<Double> p,
            @ForAll("distribution") List<Double> q,
            @ForAll("positiveScale") double a) {

        double[] pArr = toArray(p);
        double[] qArr = toArray(q);

        double[] pScaled = new double[pArr.length];
        double[] qScaled = new double[qArr.length];
        for (int i = 0; i < pArr.length; i++) pScaled[i] = pArr[i] * a;
        for (int i = 0; i < qArr.length; i++) qScaled[i] = qArr[i] * a;

        double original = WassersteinDistance.w1(pArr, qArr);
        double scaled = WassersteinDistance.w1(pScaled, qScaled);

        assertThat(scaled).isCloseTo(a * original, within(1e-7));
    }

    // ── Known value: delta distributions ─────────────────────────────────────

    @Property(tries = 200)
    void deltasDistance(@ForAll("value") double a, @ForAll("value") double b) {
        // W₁ between two delta distributions δ(a) and δ(b) = |a - b|
        double[] p = new double[10];
        double[] q = new double[10];
        java.util.Arrays.fill(p, a);
        java.util.Arrays.fill(q, b);

        double w = WassersteinDistance.w1(p, q);
        assertThat(w).isCloseTo(Math.abs(a - b), within(1e-10));
    }

    // ── Providers ────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<Double>> distribution() {
        return Arbitraries.doubles().between(-100.0, 100.0)
                .list().ofMinSize(1).ofMaxSize(50);
    }

    @Provide
    Arbitrary<List<Double>> equalSizeDistribution() {
        return Arbitraries.doubles().between(-50.0, 50.0)
                .list().ofSize(20);
    }

    @Provide
    Arbitrary<Double> shift() {
        return Arbitraries.doubles().between(-1000.0, 1000.0);
    }

    @Provide
    Arbitrary<Double> positiveScale() {
        return Arbitraries.doubles().between(0.01, 100.0);
    }

    @Provide
    Arbitrary<Double> value() {
        return Arbitraries.doubles().between(-50.0, 50.0);
    }

    private double[] toArray(List<Double> list) {
        return list.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
