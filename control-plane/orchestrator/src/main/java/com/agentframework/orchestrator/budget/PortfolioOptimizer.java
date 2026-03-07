package com.agentframework.orchestrator.budget;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Markowitz Mean-Variance portfolio optimizer for worker-type budget allocation.
 *
 * <p>Finds the weight vector {@code w} that maximizes:
 * {@code obj = riskTolerance × E[R_p] - (1 - riskTolerance) × σ²_p}
 * subject to: {@code Σw_i = 1, w_i ≥ 0, w_i ≤ MAX_WEIGHT}.</p>
 *
 * <p>Uses exhaustive grid search on the N-simplex with step = 1/GRID_STEPS.
 * For N ≤ 5 and GRID_STEPS = 20 this is practical; for N > 8, the combinatorial
 * explosion makes it infeasible — callers must filter to active worker types only.</p>
 *
 * @see <a href="https://doi.org/10.2307/2975974">
 *     Markowitz (1952), Portfolio Selection, Journal of Finance</a>
 */
public class PortfolioOptimizer {

    /** Risk-free rate R_f for Sharpe ratio calculation. */
    public static final double RISK_FREE_RATE = 0.3;

    /** Maximum weight per worker type (prevents over-concentration). */
    public static final double MAX_WEIGHT = 0.6;

    private static final int GRID_STEPS = 20;

    private final CovarianceMatrix covMatrix;
    private final double riskTolerance;

    /**
     * @param covMatrix      empirical covariance matrix of worker-type rewards
     * @param riskTolerance  [0.0, 1.0] — 0 = minimize variance, 1 = maximize return
     */
    public PortfolioOptimizer(CovarianceMatrix covMatrix, double riskTolerance) {
        this.covMatrix = covMatrix;
        this.riskTolerance = Math.max(0, Math.min(1, riskTolerance));
    }

    /**
     * Finds the optimal portfolio weights via grid search on the simplex.
     *
     * @return best portfolio result (weights, return, volatility, Sharpe)
     */
    public PortfolioResult optimize() {
        int n = covMatrix.size();
        if (n == 0) {
            return new PortfolioResult(Map.of(), 0, 0, 0, covMatrix.isDiagonal());
        }
        if (n == 1) {
            return new PortfolioResult(
                Map.of(covMatrix.workerType(0), 1.0),
                covMatrix.mean(0),
                Math.sqrt(covMatrix.covariance(0, 0)),
                sharpeRatio(new double[]{1.0}),
                covMatrix.isDiagonal()
            );
        }

        double[] bestWeights = uniformWeights(n);
        double bestObj = Double.NEGATIVE_INFINITY;

        // Grid search over the simplex
        double step = 1.0 / GRID_STEPS;
        double[] weights = new double[n];
        searchSimplex(weights, 0, n, GRID_STEPS, step, bestWeights, new double[]{bestObj});

        bestObj = objective(bestWeights);

        Map<String, Double> weightMap = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            weightMap.put(covMatrix.workerType(i), bestWeights[i]);
        }

        double ret = portfolioReturn(bestWeights);
        double vol = Math.sqrt(Math.max(0, portfolioVariance(bestWeights)));

        return new PortfolioResult(weightMap, ret, vol, sharpeRatio(bestWeights), covMatrix.isDiagonal());
    }

    /** E[R_p] = Σ(w_i × E[R_i]) */
    double portfolioReturn(double[] weights) {
        double sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += weights[i] * covMatrix.mean(i);
        }
        return sum;
    }

    /** σ²_p = Σ_i Σ_j (w_i × w_j × Cov(i,j)) */
    double portfolioVariance(double[] weights) {
        double sum = 0;
        for (int i = 0; i < weights.length; i++) {
            for (int j = 0; j < weights.length; j++) {
                sum += weights[i] * weights[j] * covMatrix.covariance(i, j);
            }
        }
        return sum;
    }

    /** Sharpe = (E[R_p] - R_f) / σ_p. Returns 0 if σ_p ≈ 0. */
    double sharpeRatio(double[] weights) {
        double ret = portfolioReturn(weights);
        double vol = Math.sqrt(Math.max(0, portfolioVariance(weights)));
        if (vol < 1e-12) return 0;
        return (ret - RISK_FREE_RATE) / vol;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private double objective(double[] weights) {
        double ret = portfolioReturn(weights);
        double var = portfolioVariance(weights);
        return riskTolerance * ret - (1 - riskTolerance) * var;
    }

    /**
     * Recursive grid search over the N-simplex.
     * Each dimension takes values from 0 to remaining/step, capped at MAX_WEIGHT.
     */
    private void searchSimplex(double[] weights, int dim, int n, int remaining,
                                double step, double[] bestWeights, double[] bestObj) {
        if (dim == n - 1) {
            // Last dimension: must use remaining budget
            double w = remaining * step;
            if (w > MAX_WEIGHT) return;
            weights[dim] = w;
            double obj = objective(weights);
            if (obj > bestObj[0]) {
                bestObj[0] = obj;
                System.arraycopy(weights, 0, bestWeights, 0, n);
            }
            return;
        }

        int maxSteps = (int) Math.min(remaining, Math.floor(MAX_WEIGHT / step));
        for (int s = 0; s <= maxSteps; s++) {
            weights[dim] = s * step;
            searchSimplex(weights, dim + 1, n, remaining - s, step, bestWeights, bestObj);
        }
    }

    private static double[] uniformWeights(int n) {
        double[] w = new double[n];
        double val = 1.0 / n;
        for (int i = 0; i < n; i++) w[i] = val;
        return w;
    }

    /**
     * Portfolio optimization result.
     *
     * @param weights         optimal weight per worker type (sums to 1.0)
     * @param expectedReturn  E[R_p]
     * @param volatility      σ_p
     * @param sharpeRatio     (E[R_p] - R_f) / σ_p
     * @param diagonalCov     true if covariance was estimated in diagonal mode (cold-start)
     */
    public record PortfolioResult(
        Map<String, Double> weights,
        double expectedReturn,
        double volatility,
        double sharpeRatio,
        boolean diagonalCov
    ) {}
}
