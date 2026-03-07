package com.agentframework.orchestrator.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link CovarianceMatrix} — empirical covariance of reward streams.
 *
 * @see <a href="https://doi.org/10.2307/2975974">
 *     Markowitz (1952), Portfolio Selection, Journal of Finance</a>
 */
class CovarianceMatrixTest {

    @Test
    void computeCovariance_twoAssets_correctValue() {
        // Generate 200 data points (> MIN_RECORDS_FOR_FULL_COV=100) with known correlation
        // X_i = i, Y_i = 2*i → perfect linear correlation
        int n = 200;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = i + 1;
            y[i] = 2 * (i + 1);
        }
        // E[X] = (1+200)/2 = 100.5, E[Y] = 201.0
        // Cov(X,Y) = (1/(n-1)) × Σ((x_i - μ_x)(y_i - μ_y))
        // Since Y = 2X: Cov(X,Y) = 2 × Var(X) = 2 × (n²-1)/12 × ... well, let's just verify > 0
        String[] types = {"BE", "FE"};
        double[][] history = {x, y};

        CovarianceMatrix cov = new CovarianceMatrix(types, history);

        assertThat(cov.isDiagonal()).isFalse();
        assertThat(cov.covariance(0, 1)).isGreaterThan(0);
        // Cov(X,Y) should be exactly 2 × Var(X) for Y=2X
        assertThat(cov.covariance(0, 1)).isCloseTo(2.0 * cov.covariance(0, 0), within(1e-9));
        assertThat(cov.mean(0)).isCloseTo(100.5, within(1e-9));
        assertThat(cov.mean(1)).isCloseTo(201.0, within(1e-9));
        assertThat(cov.size()).isEqualTo(2);
        assertThat(cov.workerType(0)).isEqualTo("BE");
    }

    @Test
    void perfectCorrelation_covEqualsVariance() {
        // Cov(X, X) = Var(X) for any X
        // X = [2, 4, 6], E[X] = 4, Var(X) = (1/2)[(2-4)² + (4-4)² + (6-4)²] = 4.0
        String[] types = {"BE"};
        double[][] history = {{2, 4, 6}};

        CovarianceMatrix cov = new CovarianceMatrix(types, history);

        assertThat(cov.covariance(0, 0)).isCloseTo(4.0, within(1e-9));
    }

    @Test
    void belowMinRecords_diagonalMode() {
        // With only 10 observations (< MIN_RECORDS_FOR_FULL_COV=100), should use diagonal mode
        double[] x = new double[10];
        double[] y = new double[10];
        for (int i = 0; i < 10; i++) {
            x[i] = i;
            y[i] = 10 - i;
        }

        String[] types = {"BE", "FE"};
        double[][] history = {x, y};

        CovarianceMatrix cov = new CovarianceMatrix(types, history);

        assertThat(cov.isDiagonal()).isTrue();
        // Diagonal: Var(X) should be non-zero
        assertThat(cov.covariance(0, 0)).isGreaterThan(0);
        // Off-diagonal should be 0 in diagonal mode
        assertThat(cov.covariance(0, 1)).isEqualTo(0.0);
    }
}
