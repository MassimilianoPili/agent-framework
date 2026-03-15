package com.agentframework.orchestrator.analytics.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Explainable Decision Trace.
 *
 * @param maxAlternatives maximum number of alternative workers to include in trace
 * @param traceDepth      explanation depth: 1=what, 2=why, 3=counterfactual
 */
@ConfigurationProperties(prefix = "agent-framework.decision-trace")
public record DecisionTraceConfig(
        int maxAlternatives,
        int traceDepth
) {
    public DecisionTraceConfig {
        if (maxAlternatives <= 0) maxAlternatives = 5;
        if (traceDepth <= 0 || traceDepth > 3) traceDepth = 3;
    }

    public static DecisionTraceConfig defaults() {
        return new DecisionTraceConfig(5, 3);
    }
}
