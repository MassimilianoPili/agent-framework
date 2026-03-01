package com.agentframework.gp.math;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DenseMatrixTest {

    @Test
    void addDiagonal_augmentsDiagonalElements() {
        var m = new DenseMatrix(new double[][]{
                {1.0, 2.0},
                {3.0, 4.0}
        });
        var result = m.addDiagonal(10.0);
        assertThat(result.get(0, 0)).isEqualTo(11.0);
        assertThat(result.get(0, 1)).isEqualTo(2.0);
        assertThat(result.get(1, 0)).isEqualTo(3.0);
        assertThat(result.get(1, 1)).isEqualTo(14.0);
        // Original unchanged
        assertThat(m.get(0, 0)).isEqualTo(1.0);
    }

    @Test
    void multiply_identityTimesVector_returnsVector() {
        var identity = new DenseMatrix(new double[][]{
                {1.0, 0.0, 0.0},
                {0.0, 1.0, 0.0},
                {0.0, 0.0, 1.0}
        });
        double[] v = {3.0, 5.0, 7.0};
        double[] result = identity.multiply(v);
        assertThat(result).containsExactly(3.0, 5.0, 7.0);
    }

    @Test
    void multiply_knownMatrixAndVector_returnsExpected() {
        // [[2, 1], [0, 3]] * [4, 5] = [13, 15]
        var m = new DenseMatrix(new double[][]{
                {2.0, 1.0},
                {0.0, 3.0}
        });
        double[] result = m.multiply(new double[]{4.0, 5.0});
        assertThat(result).containsExactly(13.0, 15.0);
    }

    @Test
    void diagonal_extractsCorrectElements() {
        var m = new DenseMatrix(new double[][]{
                {10.0, 2.0, 3.0},
                {4.0, 20.0, 6.0},
                {7.0, 8.0, 30.0}
        });
        assertThat(m.diagonal()).containsExactly(10.0, 20.0, 30.0);
    }

    @Test
    void constructor_defensiveCopy_mutatingOriginalDoesNotAffectMatrix() {
        double[][] data = {{1.0, 2.0}, {3.0, 4.0}};
        var m = new DenseMatrix(data);
        data[0][0] = 999.0;
        assertThat(m.get(0, 0)).isEqualTo(1.0);
    }
}
