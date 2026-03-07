package com.agentframework.orchestrator.budget;

/**
 * Empirical covariance matrix of reward streams across worker types.
 *
 * <p>Computes pairwise covariance {@code Cov(R_i, R_j) = (1/(n-1)) × Σ((r_ik - μ_i)(r_jk - μ_j))}
 * for each pair of worker types with overlapping observations.</p>
 *
 * <p>When any pair has fewer than {@link #MIN_RECORDS_FOR_FULL_COV} overlapping observations,
 * the matrix falls back to <b>diagonal mode</b>: only variances (no cross-covariances).
 * This prevents noisy covariance estimates from dominating portfolio optimization in cold-start.</p>
 *
 * @see <a href="https://doi.org/10.2307/2975974">
 *     Markowitz (1952), Portfolio Selection, Journal of Finance</a>
 */
public class CovarianceMatrix {

    /** Minimum overlapping observations for full (non-diagonal) covariance. */
    public static final int MIN_RECORDS_FOR_FULL_COV = 100;

    private final String[] workerTypes;
    private final double[][] matrix;
    private final double[] means;
    private final boolean diagonal;

    /**
     * Computes the empirical covariance matrix from reward history.
     *
     * @param workerTypes  names of worker types (one per row in rewardHistory)
     * @param rewardHistory {@code rewardHistory[i][k]} = k-th observed reward for worker type i.
     *                      Rows may have different lengths (only overlapping observations are used).
     */
    public CovarianceMatrix(String[] workerTypes, double[][] rewardHistory) {
        this.workerTypes = workerTypes;
        int n = workerTypes.length;
        this.means = new double[n];
        this.matrix = new double[n][n];

        // Compute means
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (double v : rewardHistory[i]) {
                sum += v;
            }
            means[i] = rewardHistory[i].length > 0 ? sum / rewardHistory[i].length : 0;
        }

        // Check if we have enough overlapping observations for full covariance
        int minOverlap = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int overlap = Math.min(rewardHistory[i].length, rewardHistory[j].length);
                minOverlap = Math.min(minOverlap, overlap);
            }
        }
        this.diagonal = n > 1 && minOverlap < MIN_RECORDS_FOR_FULL_COV;

        // Compute covariance matrix
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                double cov;
                if (i == j) {
                    // Variance: Var(X) = (1/(n-1)) × Σ(x_k - μ)²
                    cov = computeVariance(rewardHistory[i], means[i]);
                } else if (diagonal) {
                    cov = 0.0;
                } else {
                    cov = computeCovariance(rewardHistory[i], means[i],
                                            rewardHistory[j], means[j]);
                }
                matrix[i][j] = cov;
                matrix[j][i] = cov;
            }
        }
    }

    /** Returns Cov(i, j). In diagonal mode, returns 0.0 for i ≠ j. */
    public double covariance(int i, int j) {
        return matrix[i][j];
    }

    /** Returns E[R_i] — the mean reward for worker type i. */
    public double mean(int i) {
        return means[i];
    }

    /** Number of worker types in the matrix. */
    public int size() {
        return workerTypes.length;
    }

    /** Name of worker type at index i. */
    public String workerType(int i) {
        return workerTypes[i];
    }

    /** True if the matrix is diagonal-only (cold-start, insufficient overlapping data). */
    public boolean isDiagonal() {
        return diagonal;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static double computeVariance(double[] data, double mean) {
        if (data.length < 2) return 0.0;
        double sum = 0;
        for (double v : data) {
            double diff = v - mean;
            sum += diff * diff;
        }
        return sum / (data.length - 1);
    }

    private static double computeCovariance(double[] x, double muX, double[] y, double muY) {
        int overlap = Math.min(x.length, y.length);
        if (overlap < 2) return 0.0;
        double sum = 0;
        for (int k = 0; k < overlap; k++) {
            sum += (x[k] - muX) * (y[k] - muY);
        }
        return sum / (overlap - 1);
    }
}
