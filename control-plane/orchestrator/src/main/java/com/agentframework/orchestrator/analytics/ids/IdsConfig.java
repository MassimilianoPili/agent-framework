package com.agentframework.orchestrator.analytics.ids;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Information-Directed Sampling.
 *
 * @param minDataPoints minimum historical data points before IDS activates (below → TS fallback)
 * @param regretWeight  scaling factor for regret squared in information ratio
 * @param tsFallback    whether to fall back to Thompson Sampling when data is insufficient
 */
@ConfigurationProperties(prefix = "agent-framework.ids")
public record IdsConfig(
        int minDataPoints,
        double regretWeight,
        boolean tsFallback
) {
    public IdsConfig {
        if (minDataPoints <= 0) minDataPoints = 20;
        if (regretWeight <= 0) regretWeight = 1.0;
    }

    public static IdsConfig defaults() {
        return new IdsConfig(20, 1.0, true);
    }
}
