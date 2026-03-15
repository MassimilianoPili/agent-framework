package com.agentframework.orchestrator.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Configuration for the Compile-Test-Fix verification loop.
 *
 * <pre>
 * compile-test-fix:
 *   enabled: false
 *   max-iterations: 5
 *   optimal-iterations: 3
 *   task-type-gates:
 *     CODE: full
 *     REASONING: skip
 *     INFRASTRUCTURE: dry-run
 * </pre>
 *
 * @param maxIterations     hard cap on fix iterations (Shukla: +37.6% vulns after 5)
 * @param optimalIterations target iteration count (2-3 is optimal sweet spot)
 * @param taskTypeGates     gating per task type: "full", "dry-run", "skip"
 */
@ConfigurationProperties(prefix = "compile-test-fix")
public record CompileTestFixConfig(
        int maxIterations,
        int optimalIterations,
        Map<String, String> taskTypeGates
) {
    public CompileTestFixConfig {
        if (maxIterations <= 0) maxIterations = 5;
        if (optimalIterations <= 0) optimalIterations = 3;
        if (taskTypeGates == null) taskTypeGates = Map.of(
                "CODE", "full",
                "REASONING", "skip",
                "INFRASTRUCTURE", "dry-run"
        );
    }

    /**
     * Returns the gate for a task type.
     *
     * @param taskType the task type (CODE, REASONING, INFRASTRUCTURE, etc.)
     * @return gate: "full", "dry-run", or "skip"
     */
    public String gateFor(String taskType) {
        if (taskType == null) return "full";
        return taskTypeGates.getOrDefault(taskType.toUpperCase(), "full");
    }
}
