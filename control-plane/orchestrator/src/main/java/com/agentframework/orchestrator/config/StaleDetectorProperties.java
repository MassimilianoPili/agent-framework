package com.agentframework.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Per-workerType stale task detection timeouts (#29 Fase 1).
 *
 * <p>Replaces the global {@code @Value("${stale.timeout-minutes:30}")} with a richer
 * configuration that lets each workerType declare its own timeout. This matters because
 * AI_TASK workers may need 2 hours for complex code generation, while fast deterministic
 * workers (HOOK_MANAGER, CONTEXT_MANAGER) should time out in 10-15 minutes.</p>
 *
 * <p>Example YAML:
 * <pre>
 * orchestrator:
 *   stale-detection:
 *     default-timeout-minutes: 30
 *     worker-timeouts:
 *       AI_TASK: 120
 *       CONTEXT_MANAGER: 15
 * </pre>
 */
@ConfigurationProperties(prefix = "orchestrator.stale-detection")
public record StaleDetectorProperties(
    int defaultTimeoutMinutes,
    Map<String, Integer> workerTimeouts
) {
    public StaleDetectorProperties {
        if (defaultTimeoutMinutes <= 0) defaultTimeoutMinutes = 30;
        if (workerTimeouts == null) workerTimeouts = Map.of();
    }

    /** Returns the configured timeout for {@code workerType}, or the default if not specified. */
    public int timeoutFor(String workerType) {
        return workerTimeouts.getOrDefault(workerType, defaultTimeoutMinutes);
    }

    /** Returns the maximum timeout across all configured workerTypes (used to set the query window). */
    public int maxTimeoutMinutes() {
        if (workerTimeouts.isEmpty()) return defaultTimeoutMinutes;
        return Math.max(defaultTimeoutMinutes,
            workerTimeouts.values().stream().mapToInt(Integer::intValue).max().orElse(defaultTimeoutMinutes));
    }
}
