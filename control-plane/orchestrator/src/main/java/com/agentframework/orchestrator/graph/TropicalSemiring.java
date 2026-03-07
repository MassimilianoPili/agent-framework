package com.agentframework.orchestrator.graph;

/**
 * Pure algebraic operations in the tropical (min-plus) semiring {@code (R ∪ {+∞}, min, +)}.
 *
 * <ul>
 *   <li><b>Tropical addition</b>: {@code a ⊕ b = min(a, b)}</li>
 *   <li><b>Tropical multiplication</b>: {@code a ⊗ b = a + b}</li>
 *   <li><b>Additive identity</b>: {@code +∞} (neutral element for min)</li>
 *   <li><b>Multiplicative identity</b>: {@code 0} (neutral element for +)</li>
 * </ul>
 *
 * <p>Matrix operations follow standard linear algebra with ⊕ replacing + and ⊗ replacing ×.
 * The Kleene closure {@code A* = I ⊕ A ⊕ A² ⊕ ... ⊕ A^(n-1)} computes all-pairs
 * shortest paths — equivalent to Floyd-Warshall expressed algebraically.</p>
 *
 * @see <a href="https://doi.org/10.1090/S0273-0979-09-01256-4">
 *     Speyer &amp; Sturmfels (2009), Mathematics Magazine</a>
 * @see <a href="https://doi.org/10.1007/978-1-84996-299-5">
 *     Butkovič (2010), Max-linear Systems</a>
 */
public final class TropicalSemiring {

    /** Additive identity: +∞ (neutral element for min). */
    public static final double INF = Double.POSITIVE_INFINITY;

    /** Multiplicative identity: 0 (neutral element for +). */
    public static final double ONE = 0.0;

    private TropicalSemiring() {} // utility class

    /**
     * Tropical addition: {@code a ⊕ b = min(a, b)}.
     */
    public static double add(double a, double b) {
        return Math.min(a, b);
    }

    /**
     * Tropical multiplication: {@code a ⊗ b = a + b}.
     * If either operand is +∞, the result is +∞.
     */
    public static double multiply(double a, double b) {
        if (a == INF || b == INF) return INF;
        return a + b;
    }

    /**
     * Tropical matrix multiplication: {@code C[i][j] = min_k(A[i][k] + B[k][j])}.
     *
     * @param a matrix of dimension m × p
     * @param b matrix of dimension p × n
     * @return result matrix of dimension m × n
     */
    public static double[][] matMul(double[][] a, double[][] b) {
        int m = a.length;
        int p = b.length;
        int n = b[0].length;

        double[][] c = new double[m][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                double val = INF;
                for (int k = 0; k < p; k++) {
                    val = add(val, multiply(a[i][k], b[k][j]));
                }
                c[i][j] = val;
            }
        }
        return c;
    }

    /**
     * Returns the n × n tropical identity matrix:
     * diagonal = 0 (multiplicative identity), off-diagonal = +∞ (additive identity).
     */
    public static double[][] identity(int n) {
        double[][] id = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                id[i][j] = (i == j) ? ONE : INF;
            }
        }
        return id;
    }

    /**
     * Deep copy of a matrix.
     */
    public static double[][] copy(double[][] m) {
        double[][] c = new double[m.length][];
        for (int i = 0; i < m.length; i++) {
            c[i] = m[i].clone();
        }
        return c;
    }

    /**
     * Computes the Kleene closure: {@code A* = I ⊕ A ⊕ A² ⊕ ... ⊕ A^(n-1)}.
     *
     * <p>This gives all-pairs shortest paths in the graph whose adjacency matrix is A.
     *
     * @param a square adjacency matrix (n × n)
     * @return the closure matrix (n × n)
     */
    public static double[][] kleeneClosure(double[][] a) {
        int n = a.length;
        double[][] result = identity(n);
        double[][] power = copy(a);

        for (int iter = 0; iter < n; iter++) {
            // result = result ⊕ power (element-wise min)
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    result[i][j] = add(result[i][j], power[i][j]);
                }
            }
            if (iter < n - 1) {
                power = matMul(power, a);
            }
        }
        return result;
    }
}
