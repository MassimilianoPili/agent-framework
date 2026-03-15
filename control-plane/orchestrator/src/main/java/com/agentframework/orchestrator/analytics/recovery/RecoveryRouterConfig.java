package com.agentframework.orchestrator.analytics.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Graph-Based Recovery Router.
 *
 * @param maxPathLength      maximum number of hops in a recovery path
 * @param infinityWeight     weight assigned to failed edges (effectively infinite)
 * @param llmFallbackEnabled whether to allow LLM-based recovery when no feasible path exists
 */
@ConfigurationProperties(prefix = "agent-framework.recovery-router")
public record RecoveryRouterConfig(
        int maxPathLength,
        double infinityWeight,
        boolean llmFallbackEnabled
) {
    public RecoveryRouterConfig {
        if (maxPathLength <= 0) maxPathLength = 10;
        if (infinityWeight <= 0) infinityWeight = 1.0E9;
    }

    public static RecoveryRouterConfig defaults() {
        return new RecoveryRouterConfig(10, 1.0E9, false);
    }
}
