package com.agentframework.orchestrator.analytics.mast;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for MAST failure classification and self-healing.
 *
 * @param oscillationWindow   number of iterations to check for oscillation (FM11)
 * @param cascadeDepth        max dependency chain depth for cascading failure detection (FM10)
 * @param minSpecLength       minimum spec length in characters (below = FM1 ambiguous)
 * @param eloCollapseThreshold ELO score below which trust degradation is detected (FM9)
 * @param partialCompletionThreshold fraction of DONE items above which partial completion is detected (FM13)
 */
@ConfigurationProperties(prefix = "agent-framework.mast")
public record MastConfig(
        int oscillationWindow,
        int cascadeDepth,
        int minSpecLength,
        double eloCollapseThreshold,
        double partialCompletionThreshold
) {
    public MastConfig {
        if (oscillationWindow <= 0) oscillationWindow = 5;
        if (cascadeDepth <= 0) cascadeDepth = 3;
        if (minSpecLength <= 0) minSpecLength = 30;
        if (eloCollapseThreshold <= 0) eloCollapseThreshold = 800.0;
        if (partialCompletionThreshold <= 0 || partialCompletionThreshold > 1) {
            partialCompletionThreshold = 0.3;
        }
    }

    public static MastConfig defaults() {
        return new MastConfig(5, 3, 30, 800.0, 0.3);
    }
}
