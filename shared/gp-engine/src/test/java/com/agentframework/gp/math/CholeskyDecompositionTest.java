package com.agentframework.gp.math;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CholeskyDecompositionTest {

    @Test
    void decompose_2x2_returnsCorrectLowerTriangular() {
        // A = [[4, 2], [2, 3]]  →  L = [[2, 0], [1, sqrt(2)]]
        var A = new DenseMatrix(new double[][]{{4.0, 2.0}, {2.0, 3.0}});
        var chol = new CholeskyDecomposition(A);
        DenseMatrix L = chol.lower();

        assertThat(L.get(0, 0)).isCloseTo(2.0, within(1e-10));
        assertThat(L.get(0, 1)).isCloseTo(0.0, within(1e-10));
        assertThat(L.get(1, 0)).isCloseTo(1.0, within(1e-10));
        assertThat(L.get(1, 1)).isCloseTo(Math.sqrt(2.0), within(1e-10));
    }

    @Test
    void decompose_3x3_LLT_equalsOriginal() {
        // SPD matrix: A = [[4, 12, -16], [12, 37, -43], [-16, -43, 98]]
        var A = new DenseMatrix(new double[][]{
                {4.0, 12.0, -16.0},
                {12.0, 37.0, -43.0},
                {-16.0, -43.0, 98.0}
        });
        var chol = new CholeskyDecomposition(A);
        DenseMatrix L = chol.lower();

        // Reconstruct: A_reconstructed = L * L^T
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0.0;
                for (int k = 0; k < 3; k++) {
                    sum += L.get(i, k) * L.get(j, k);
                }
                assertThat(sum).as("A[%d][%d]", i, j)
                        .isCloseTo(A.get(i, j), within(1e-10));
            }
        }
    }

    @Test
    void solve_knownSystem_returnsExpectedSolution() {
        // A = [[4, 2], [2, 3]], b = [14, 13]  → x = [2, 3]
        var A = new DenseMatrix(new double[][]{{4.0, 2.0}, {2.0, 3.0}});
        var chol = new CholeskyDecomposition(A);
        double[] x = chol.solve(new double[]{14.0, 13.0});

        assertThat(x[0]).isCloseTo(2.0, within(1e-10));
        assertThat(x[1]).isCloseTo(3.0, within(1e-10));
    }

    @Test
    void decompose_notPositiveDefinite_throwsArithmeticException() {
        // Not SPD: [[1, 2], [2, 1]] — eigenvalues -1 and 3
        var notSpd = new DenseMatrix(new double[][]{{1.0, 2.0}, {2.0, 1.0}});
        assertThatThrownBy(() -> new CholeskyDecomposition(notSpd))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("not positive definite");
    }

    @Test
    void logDeterminant_matchesDirectComputation() {
        // A = [[4, 2], [2, 3]], det(A) = 12-4 = 8, log(8) ≈ 2.0794
        var A = new DenseMatrix(new double[][]{{4.0, 2.0}, {2.0, 3.0}});
        var chol = new CholeskyDecomposition(A);
        assertThat(chol.logDeterminant()).isCloseTo(Math.log(8.0), within(1e-10));
    }
}
