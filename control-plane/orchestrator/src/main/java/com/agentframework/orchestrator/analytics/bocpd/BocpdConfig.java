package com.agentframework.orchestrator.analytics.bocpd;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Bayesian Online Changepoint Detection.
 *
 * @param hazardLambda    expected run length between changepoints (higher = fewer expected changes)
 * @param threshold       posterior probability P(r_t=0) above which a changepoint is declared
 * @param maxRunLength    truncation of the run-length distribution (memory bound)
 * @param pollIntervalMs  polling interval for SLI streams (milliseconds)
 * @param scales          time scales in seconds for multiscale aggregation (e.g. 60, 300, 900)
 */
@ConfigurationProperties(prefix = "agent-framework.bocpd")
public record BocpdConfig(
        double hazardLambda,
        double threshold,
        int maxRunLength,
        long pollIntervalMs,
        int[] scales
) {
    public BocpdConfig {
        if (hazardLambda <= 0) hazardLambda = 200.0;
        if (threshold <= 0 || threshold >= 1) threshold = 0.6;
        if (maxRunLength <= 0) maxRunLength = 500;
        if (pollIntervalMs <= 0) pollIntervalMs = 30_000;
        if (scales == null || scales.length == 0) scales = new int[]{60, 300, 900};
    }

    /** Default configuration for unit testing and fallback. */
    public static BocpdConfig defaults() {
        return new BocpdConfig(200.0, 0.6, 500, 30_000, new int[]{60, 300, 900});
    }
}
