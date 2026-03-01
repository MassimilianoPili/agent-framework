package com.agentframework.gp.math;

/**
 * Cholesky decomposition for symmetric positive definite (SPD) matrices.
 *
 * <p>Computes lower triangular L such that A = LL&sup1;. Used for GP posterior inference:</p>
 * <ol>
 *   <li>Solving (K + &sigma;&sup2;&#8345;I)&alpha; = y via Lz = y, L&sup1;T&alpha; = z</li>
 *   <li>Computing log-determinant as 2 &times; &sum;log(L&#7522;&#7522;)</li>
 *   <li>Posterior variance via L&sup2;&supˆ;&sup1;k* (forward substitution)</li>
 * </ol>
 *
 * <p>Complexity: O(N&sup3;/3) for decomposition, O(N&sup2;) per solve.
 * At N=500: ~42M flops &rarr; sub-millisecond on modern CPUs.</p>
 */
public final class CholeskyDecomposition {

    private final DenseMatrix lower;
    private final int n;

    /**
     * Decomposes the given SPD matrix.
     *
     * @param spd symmetric positive definite matrix (only lower triangle is read)
     * @throws ArithmeticException if the matrix is not positive definite
     */
    public CholeskyDecomposition(DenseMatrix spd) {
        if (spd.rows() != spd.cols()) {
            throw new IllegalArgumentException("Cholesky requires a square matrix: "
                    + spd.rows() + "x" + spd.cols());
        }
        this.n = spd.rows();
        this.lower = new DenseMatrix(n, n);

        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0.0;
                for (int k = 0; k < j; k++) {
                    sum += lower.get(i, k) * lower.get(j, k);
                }
                if (i == j) {
                    double diag = spd.get(i, i) - sum;
                    if (diag <= 0.0) {
                        throw new ArithmeticException(
                                "Matrix is not positive definite: diagonal element at index "
                                + i + " is " + diag + " after subtraction");
                    }
                    lower.set(i, j, Math.sqrt(diag));
                } else {
                    lower.set(i, j, (spd.get(i, j) - sum) / lower.get(j, j));
                }
            }
        }
    }

    /** Returns the lower triangular factor L. */
    public DenseMatrix lower() {
        return lower;
    }

    /**
     * Solves Lz = b (forward substitution).
     *
     * @param b right-hand side vector of length n
     * @return z such that Lz = b
     */
    public double[] solveForward(double[] b) {
        if (b.length != n) {
            throw new IllegalArgumentException("Vector length " + b.length + " != n " + n);
        }
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = b[i];
            for (int j = 0; j < i; j++) {
                sum -= lower.get(i, j) * z[j];
            }
            z[i] = sum / lower.get(i, i);
        }
        return z;
    }

    /**
     * Solves L^T x = z (backward substitution).
     *
     * @param z right-hand side vector of length n
     * @return x such that L^T x = z
     */
    public double[] solveBackward(double[] z) {
        if (z.length != n) {
            throw new IllegalArgumentException("Vector length " + z.length + " != n " + n);
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = z[i];
            for (int j = i + 1; j < n; j++) {
                sum -= lower.get(j, i) * x[j]; // L^T[i][j] = L[j][i]
            }
            x[i] = sum / lower.get(i, i);
        }
        return x;
    }

    /**
     * Solves Ax = b via forward + backward substitution: Lz = b, L^T x = z.
     *
     * @param b right-hand side vector of length n
     * @return x such that Ax = b
     */
    public double[] solve(double[] b) {
        return solveBackward(solveForward(b));
    }

    /**
     * Log-determinant of the original matrix: 2 &times; &sum;log(L&#7522;&#7522;).
     * Useful for model selection (marginal likelihood).
     */
    public double logDeterminant() {
        double logDet = 0.0;
        for (int i = 0; i < n; i++) {
            logDet += Math.log(lower.get(i, i));
        }
        return 2.0 * logDet;
    }
}
