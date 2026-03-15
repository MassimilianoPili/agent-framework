package com.agentframework.orchestrator.analytics;

/**
 * Cognitive classification of tasks, based on the type of thinking required.
 *
 * <p>Each type maps to different orchestration behaviour:</p>
 * <ul>
 *   <li><b>STYLE</b> — formatting, naming, linting, cosmetic changes.
 *       Low risk. GP can be aggressive (high sigma tolerance). Review optional.</li>
 *   <li><b>REASONING</b> — algorithms, business logic, security, data models.
 *       High risk. GP should be conservative. Review mandatory.</li>
 *   <li><b>VERIFICATION</b> — tests, validation, review, CI/CD.
 *       Medium risk. Different GP prior (verification tasks have bimodal reward).</li>
 *   <li><b>EXPLORATION</b> — research, PoC, prototyping, spikes.
 *       High uncertainty expected. Wider sigma tolerance. Review optional.</li>
 * </ul>
 */
public enum CognitiveTaskType {

    STYLE(0.8, false),
    REASONING(0.3, true),
    VERIFICATION(0.5, false),
    EXPLORATION(0.9, false);

    private final double sigmaThreshold;
    private final boolean reviewMandatory;

    CognitiveTaskType(double sigmaThreshold, boolean reviewMandatory) {
        this.sigmaThreshold = sigmaThreshold;
        this.reviewMandatory = reviewMandatory;
    }

    /** GP sigma² threshold above which a REVIEW task is triggered. */
    public double sigmaThreshold() {
        return sigmaThreshold;
    }

    /** Whether a REVIEW task is always required regardless of GP confidence. */
    public boolean reviewMandatory() {
        return reviewMandatory;
    }
}
