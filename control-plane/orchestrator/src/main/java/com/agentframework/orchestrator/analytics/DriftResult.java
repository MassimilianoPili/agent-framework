package com.agentframework.orchestrator.analytics;

/**
 * Drift detection result for a single worker profile.
 *
 * <p>Compares the recent reward distribution against the historical baseline
 * using Wasserstein-1 distance. A distance above the configured threshold
 * indicates distribution shift (drift).</p>
 *
 * @param profile         worker profile identifier (e.g. "be-java")
 * @param w1Distance      Wasserstein-1 distance between recent and historical distributions
 * @param recentMean      mean reward in the recent window
 * @param historicalMean  mean reward in the historical window
 * @param recentCount     sample count in the recent window
 * @param historicalCount sample count in the historical window
 * @param driftDetected   true if w1Distance exceeds the configured threshold
 *
 * @see WassersteinDistance
 * @see <a href="https://doi.org/10.1007/978-3-540-71050-9">
 *     Villani (2009), Optimal Transport: Old and New</a>
 */
public record DriftResult(
        String profile,
        double w1Distance,
        double recentMean,
        double historicalMean,
        int recentCount,
        int historicalCount,
        boolean driftDetected
) {}
