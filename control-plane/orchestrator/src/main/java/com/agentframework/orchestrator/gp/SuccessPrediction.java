package com.agentframework.orchestrator.gp;

/**
 * Result of Bayesian success prediction for a task before dispatch.
 *
 * <p>Contains calibrated probability, dispatch recommendation, and the raw feature
 * vector for auditability.</p>
 *
 * @param probability     calibrated P(success) ∈ [0, 1] (after Platt scaling)
 * @param rawProbability  uncalibrated sigmoid output (before Platt scaling)
 * @param shouldDispatch  true if probability ≥ {@link #DISPATCH_THRESHOLD}
 * @param featureVector   1029-dim feature vector used for prediction (debug/audit)
 *
 * @see BayesianSuccessPredictor
 * @see <a href="https://www.stat.columbia.edu/~gelman/book/">
 *     Gelman et al. (2013), Bayesian Data Analysis, 3rd ed.</a>
 */
public record SuccessPrediction(
    double probability,
    double rawProbability,
    boolean shouldDispatch,
    double[] featureVector
) {
    /** Minimum probability for dispatch admission. */
    public static final double DISPATCH_THRESHOLD = 0.3;

    /** Reward threshold above which a task outcome is classified as "success". */
    public static final double SUCCESS_THRESHOLD = 0.5;
}
