package com.agentframework.gp.math;

/**
 * Minimal dense matrix for GP kernel and posterior computations.
 *
 * <p>Row-major {@code double[][]} storage. Immutable after construction via public API —
 * the package-private {@link #set(int, int, double)} is reserved for
 * {@link CholeskyDecomposition} which builds the lower triangular factor in place.</p>
 *
 * <p>Thread-safe once constructed (all mutation happens during Cholesky build).</p>
 *
 * <p>Designed for N &le; 500. At that size, row-major vs column-major cache effects
 * are negligible (~1 MB total for 500&times;500 doubles).</p>
 */
public final class DenseMatrix {

    private final double[][] data;
    private final int rows;
    private final int cols;

    /** Creates a zero-initialized matrix. */
    public DenseMatrix(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("Matrix dimensions must be positive: " + rows + "x" + cols);
        }
        this.rows = rows;
        this.cols = cols;
        this.data = new double[rows][cols];
    }

    /** Creates a matrix from existing data (defensive copy). */
    public DenseMatrix(double[][] data) {
        if (data == null || data.length == 0 || data[0] == null || data[0].length == 0) {
            throw new IllegalArgumentException("Data must be non-null and non-empty");
        }
        this.rows = data.length;
        this.cols = data[0].length;
        this.data = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            if (data[i].length != cols) {
                throw new IllegalArgumentException("Jagged array: row " + i
                        + " has " + data[i].length + " cols, expected " + cols);
            }
            System.arraycopy(data[i], 0, this.data[i], 0, cols);
        }
    }

    public int rows() { return rows; }
    public int cols() { return cols; }

    public double get(int i, int j) {
        return data[i][j];
    }

    /** Package-private setter for {@link CholeskyDecomposition}. */
    void set(int i, int j, double val) {
        data[i][j] = val;
    }

    /**
     * Returns a new matrix = this + value * I.
     * Used for the noise term: K + &sigma;&sup2;&#8345;I.
     *
     * @throws IllegalStateException if the matrix is not square
     */
    public DenseMatrix addDiagonal(double value) {
        if (rows != cols) {
            throw new IllegalStateException("addDiagonal requires a square matrix: " + rows + "x" + cols);
        }
        DenseMatrix result = new DenseMatrix(rows, cols);
        for (int i = 0; i < rows; i++) {
            System.arraycopy(data[i], 0, result.data[i], 0, cols);
            result.data[i][i] += value;
        }
        return result;
    }

    /**
     * Matrix-vector multiply: y = this &times; x.
     *
     * @param x vector of length {@link #cols()}
     * @return result vector of length {@link #rows()}
     */
    public double[] multiply(double[] x) {
        if (x.length != cols) {
            throw new IllegalArgumentException("Vector length " + x.length + " != cols " + cols);
        }
        double[] y = new double[rows];
        for (int i = 0; i < rows; i++) {
            double sum = 0.0;
            for (int j = 0; j < cols; j++) {
                sum += data[i][j] * x[j];
            }
            y[i] = sum;
        }
        return y;
    }

    /** Returns the diagonal elements as a double[min(rows,cols)]. */
    public double[] diagonal() {
        int n = Math.min(rows, cols);
        double[] diag = new double[n];
        for (int i = 0; i < n; i++) {
            diag[i] = data[i][i];
        }
        return diag;
    }
}
