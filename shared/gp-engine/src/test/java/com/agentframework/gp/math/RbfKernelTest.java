package com.agentframework.gp.math;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RbfKernelTest {

    private final RbfKernel kernel = new RbfKernel(1.0, 1.0);

    @Test
    void compute_identicalVectors_returnsSignalVariance() {
        float[] x = {1.0f, 2.0f, 3.0f};
        assertThat(kernel.compute(x, x)).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void compute_distantVectors_approachesZero() {
        float[] x = new float[10];
        float[] y = new float[10];
        for (int i = 0; i < 10; i++) {
            x[i] = 0.0f;
            y[i] = 100.0f; // very far away
        }
        double k = kernel.compute(x, y);
        assertThat(k).isCloseTo(0.0, within(1e-10));
    }

    @Test
    void computeMatrix_isSymmetric() {
        float[][] embeddings = {
                {1.0f, 0.0f},
                {0.0f, 1.0f},
                {1.0f, 1.0f}
        };
        DenseMatrix K = kernel.computeMatrix(embeddings);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertThat(K.get(i, j)).as("K[%d][%d] vs K[%d][%d]", i, j, j, i)
                        .isCloseTo(K.get(j, i), within(1e-10));
            }
        }
    }

    @Test
    void computeMatrix_diagonalEqualsSignalVariance() {
        float[][] embeddings = {{1.0f, 2.0f}, {3.0f, 4.0f}};
        DenseMatrix K = kernel.computeMatrix(embeddings);
        assertThat(K.get(0, 0)).isCloseTo(1.0, within(1e-10));
        assertThat(K.get(1, 1)).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void computeCrossKernel_matchesIndividualComputes() {
        float[] xStar = {1.0f, 0.5f};
        float[][] training = {{0.0f, 0.0f}, {1.0f, 1.0f}};
        double[] kStar = kernel.computeCrossKernel(xStar, training);

        assertThat(kStar[0]).isCloseTo(kernel.compute(xStar, training[0]), within(1e-10));
        assertThat(kStar[1]).isCloseTo(kernel.compute(xStar, training[1]), within(1e-10));
    }

    @Test
    void selfKernel_returnsSignalVariance() {
        var k = new RbfKernel(2.5, 0.3);
        assertThat(k.selfKernel()).isEqualTo(2.5);
    }
}
