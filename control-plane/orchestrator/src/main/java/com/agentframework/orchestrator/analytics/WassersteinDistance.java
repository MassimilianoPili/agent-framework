package com.agentframework.orchestrator.analytics;

import java.util.Arrays;

/**
 * Wasserstein-1 (Earth Mover's) distance between 1D empirical distributions.
 *
 * <p>Closed-form for 1D: sort both samples, align quantiles, sum absolute differences.</p>
 * <pre>
 * W₁(P, Q) = ∫₀¹ |F_P⁻¹(t) - F_Q⁻¹(t)| dt
 *           ≈ (1/n) × Σ|p_sorted[i] - q_sorted[i]|
 * </pre>
 *
 * <p>When sample sizes differ, the shorter distribution is linearly interpolated
 * to the longer's size via quantile matching.</p>
 *
 * <p>Complexity: O(n log n) for the sort, O(n) for the summation.</p>
 *
 * @see <a href="https://doi.org/10.1007/978-3-540-71050-9">
 *     Villani (2009), Optimal Transport: Old and New</a>
 * @see <a href="https://doi.org/10.1561/2200000073">
 *     Peyré &amp; Cuturi (2019), Computational Optimal Transport</a>
 */
public final class WassersteinDistance {

    /** Minimum samples required per distribution for meaningful comparison. */
    static final int MIN_SAMPLES = 10;

    private WassersteinDistance() {}

    /**
     * Wasserstein-1 distance between two empirical distributions.
     *
     * <p>Sorts both arrays, interpolates if sizes differ, then computes
     * the average absolute difference of aligned quantiles.</p>
     *
     * @param p first distribution samples (will be copied and sorted)
     * @param q second distribution samples (will be copied and sorted)
     * @return W₁ distance (non-negative)
     * @throws IllegalArgumentException if either array is empty
     */
    public static double w1(double[] p, double[] q) {
        if (p.length == 0 || q.length == 0) {
            throw new IllegalArgumentException("Distributions must be non-empty");
        }

        double[] sortedP = Arrays.copyOf(p, p.length);
        double[] sortedQ = Arrays.copyOf(q, q.length);
        Arrays.sort(sortedP);
        Arrays.sort(sortedQ);

        return w1Sorted(sortedP, sortedQ);
    }

    /**
     * W₁ on already-sorted arrays (skips sort step).
     *
     * <p>If sizes differ, interpolates the shorter array to the longer's size.</p>
     */
    static double w1Sorted(double[] sortedP, double[] sortedQ) {
        int n;
        double[] a, b;

        if (sortedP.length == sortedQ.length) {
            a = sortedP;
            b = sortedQ;
            n = sortedP.length;
        } else if (sortedP.length > sortedQ.length) {
            n = sortedP.length;
            a = sortedP;
            b = interpolateQuantiles(sortedQ, n);
        } else {
            n = sortedQ.length;
            a = interpolateQuantiles(sortedP, n);
            b = sortedQ;
        }

        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return sum / n;
    }

    /**
     * Linearly interpolates a sorted array to {@code targetN} evenly-spaced quantiles.
     *
     * <pre>
     * quantile[i] = sorted[floor(idx)] + frac × (sorted[ceil(idx)] - sorted[floor(idx)])
     * where idx = i × (len - 1) / (targetN - 1)
     * </pre>
     *
     * @param sorted  sorted input array
     * @param targetN desired number of quantile points
     * @return interpolated array of length targetN
     */
    static double[] interpolateQuantiles(double[] sorted, int targetN) {
        if (sorted.length == 1) {
            double[] result = new double[targetN];
            Arrays.fill(result, sorted[0]);
            return result;
        }

        double[] result = new double[targetN];
        for (int i = 0; i < targetN; i++) {
            double idx = (double) i * (sorted.length - 1) / (targetN - 1);
            int lo = (int) Math.floor(idx);
            int hi = Math.min(lo + 1, sorted.length - 1);
            double frac = idx - lo;
            result[i] = sorted[lo] + frac * (sorted[hi] - sorted[lo]);
        }
        return result;
    }
}
