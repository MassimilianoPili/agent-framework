package com.agentframework.orchestrator.budget;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link PortfolioOptimizer} — Markowitz Mean-Variance optimization.
 *
 * @see <a href="https://doi.org/10.2307/2975974">
 *     Markowitz (1952), Portfolio Selection, Journal of Finance</a>
 */
class PortfolioOptimizerTest {

    @Test
    void optimize_equalMeans_nearEqualWeights() {
        // Two assets with identical means and variances → near-equal weights
        double[] data = generate(200, 0.5, 0.01);
        String[] types = {"BE", "FE"};
        double[][] history = {data, data.clone()};

        CovarianceMatrix cov = new CovarianceMatrix(types, history);
        PortfolioOptimizer optimizer = new PortfolioOptimizer(cov, 0.5);
        PortfolioOptimizer.PortfolioResult result = optimizer.optimize();

        assertThat(result.weights()).containsKeys("BE", "FE");
        double wBE = result.weights().get("BE");
        double wFE = result.weights().get("FE");
        assertThat(wBE + wFE).isCloseTo(1.0, within(0.01));
        // Should be roughly equal (within grid step = 0.05)
        assertThat(Math.abs(wBE - wFE)).isLessThan(0.15);
    }

    @Test
    void optimize_oneDominant_cappedAt06() {
        // One asset with high return, others low → weight should be capped at MAX_WEIGHT=0.6
        String[] types = {"HIGH", "LOW1", "LOW2"};
        double[][] history = {
            generate(200, 0.9, 0.01),  // HIGH: consistently high reward
            generate(200, 0.1, 0.01),  // LOW1: consistently low reward
            generate(200, 0.1, 0.01)   // LOW2: consistently low reward
        };

        CovarianceMatrix cov = new CovarianceMatrix(types, history);
        PortfolioOptimizer optimizer = new PortfolioOptimizer(cov, 1.0); // max return
        PortfolioOptimizer.PortfolioResult result = optimizer.optimize();

        assertThat(result.weights().get("HIGH")).isLessThanOrEqualTo(PortfolioOptimizer.MAX_WEIGHT + 0.01);
    }

    @Test
    void portfolioReturn_formula_correct() {
        // E[R_p] = Σ(w_i × μ_i)
        // weights = [0.6, 0.4], means = [0.8, 0.3]
        // E[R_p] = 0.6*0.8 + 0.4*0.3 = 0.48 + 0.12 = 0.60
        String[] types = {"A", "B"};
        double[][] history = {
            generate(200, 0.8, 0.01),
            generate(200, 0.3, 0.01)
        };

        CovarianceMatrix cov = new CovarianceMatrix(types, history);
        PortfolioOptimizer optimizer = new PortfolioOptimizer(cov, 0.5);

        double ret = optimizer.portfolioReturn(new double[]{0.6, 0.4});
        // Allow small tolerance due to sampling noise
        assertThat(ret).isCloseTo(0.60, within(0.02));
    }

    @Test
    void portfolioVariance_diagonal_sumWeightedVariances() {
        // With diagonal covariance (< 100 records), σ²_p = Σ(w_i² × σ²_i)
        String[] types = {"A", "B"};
        double[][] history = {
            {0.5, 0.6, 0.4, 0.5, 0.5},  // A: low variance
            {0.1, 0.9, 0.1, 0.9, 0.5}   // B: high variance
        };

        CovarianceMatrix cov = new CovarianceMatrix(types, history);
        assertThat(cov.isDiagonal()).isTrue();

        PortfolioOptimizer optimizer = new PortfolioOptimizer(cov, 0.5);
        double var = optimizer.portfolioVariance(new double[]{0.5, 0.5});

        // With diagonal Cov: σ²_p = 0.25 × Var(A) + 0.25 × Var(B)
        double expected = 0.25 * cov.covariance(0, 0) + 0.25 * cov.covariance(1, 1);
        assertThat(var).isCloseTo(expected, within(1e-9));
    }

    @Test
    void sharpeRatio_formula_correct() {
        // Sharpe = (E[R_p] - R_f) / σ_p
        String[] types = {"A"};
        double[][] history = {generate(200, 0.7, 0.01)};

        CovarianceMatrix cov = new CovarianceMatrix(types, history);
        PortfolioOptimizer optimizer = new PortfolioOptimizer(cov, 0.5);

        double ret = optimizer.portfolioReturn(new double[]{1.0});
        double vol = Math.sqrt(optimizer.portfolioVariance(new double[]{1.0}));
        double sharpe = optimizer.sharpeRatio(new double[]{1.0});

        assertThat(sharpe).isCloseTo((ret - PortfolioOptimizer.RISK_FREE_RATE) / vol, within(1e-9));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Generates n data points with given mean + small noise. */
    private static double[] generate(int n, double mean, double noise) {
        double[] data = new double[n];
        for (int i = 0; i < n; i++) {
            // Deterministic "noise" using index — avoids Random for test reproducibility
            data[i] = mean + noise * Math.sin(i * 0.7);
        }
        return data;
    }
}
