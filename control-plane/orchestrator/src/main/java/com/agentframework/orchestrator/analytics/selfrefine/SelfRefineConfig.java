package com.agentframework.orchestrator.analytics.selfrefine;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Configuration for the Self-Refine gate and flip-rate monitor.
 *
 * @param maxIterations       maximum self-refine iterations before escalating to REVIEW
 * @param flipRateThreshold   threshold above which self-refine is aborted (0.0–1.0)
 * @param allowedWorkerTypes  worker types eligible for self-refine (must have objective metrics)
 * @param flipRateWindow      number of recent iterations to consider for flip-rate calculation
 */
@ConfigurationProperties(prefix = "agent-framework.self-refine")
public record SelfRefineConfig(
        int maxIterations,
        double flipRateThreshold,
        Set<String> allowedWorkerTypes,
        int flipRateWindow
) {
    public SelfRefineConfig {
        if (maxIterations <= 0) maxIterations = 3;
        if (flipRateThreshold <= 0 || flipRateThreshold > 1) flipRateThreshold = 0.5;
        if (allowedWorkerTypes == null || allowedWorkerTypes.isEmpty()) {
            allowedWorkerTypes = Set.of("BE", "FE", "CONTRACT", "DBA");
        }
        if (flipRateWindow <= 0) flipRateWindow = 5;
    }

    public static SelfRefineConfig defaults() {
        return new SelfRefineConfig(3, 0.5, Set.of("BE", "FE", "CONTRACT", "DBA"), 5);
    }
}
