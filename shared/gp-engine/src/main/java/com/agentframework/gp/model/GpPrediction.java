package com.agentframework.gp.model;

/**
 * A single GP prediction: posterior mean and variance.
 *
 * @param mu     posterior mean — expected reward for the task+profile combination
 * @param sigma2 posterior variance — uncertainty. High sigma2 = "GP has not seen similar tasks"
 */
public record GpPrediction(double mu, double sigma2) {

    /** Standard deviation (convenience). */
    public double sigma() {
        return Math.sqrt(Math.max(0.0, sigma2));
    }

    /**
     * Upper Confidence Bound: mu + kappa * sigma.
     * Used for exploration-exploitation tradeoff in worker selection.
     * kappa &gt; 0 encourages selecting profiles with high uncertainty.
     */
    public double ucb(double kappa) {
        return mu + kappa * sigma();
    }
}
