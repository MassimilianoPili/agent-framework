package com.agentframework.orchestrator.analytics.sycophancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Sycophancy Detection in Council.
 *
 * @param cosineLower      lower bound of cosine similarity range for sycophancy detection
 * @param cosineUpper      upper bound of cosine similarity range
 * @param entropyThreshold normalized entropy below which entropy collapse is flagged
 * @param kendallThreshold average Kendall tau above which ranking similarity is flagged
 * @param anchoringWindow  number of initial responses to check for first-mover anchoring
 */
@ConfigurationProperties(prefix = "agent-framework.sycophancy")
public record SycophancyConfig(
        double cosineLower,
        double cosineUpper,
        double entropyThreshold,
        double kendallThreshold,
        int anchoringWindow
) {
    public SycophancyConfig {
        if (cosineLower <= 0 || cosineLower >= 1) cosineLower = 0.85;
        if (cosineUpper <= 0 || cosineUpper >= 1) cosineUpper = 0.90;
        if (entropyThreshold <= 0 || entropyThreshold >= 1) entropyThreshold = 0.3;
        if (kendallThreshold <= 0 || kendallThreshold >= 1) kendallThreshold = 0.7;
        if (anchoringWindow <= 0) anchoringWindow = 3;
    }

    public static SycophancyConfig defaults() {
        return new SycophancyConfig(0.85, 0.90, 0.3, 0.7, 3);
    }
}
