package com.agentframework.orchestrator.analytics.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Execution Sandbox.
 *
 * @param timeoutSeconds  maximum execution time per sandbox run
 * @param memoryLimitMb   memory limit for sandbox containers
 * @param networkEnabled  whether sandbox containers have network access (default: false for security)
 * @param maxOutputBytes  maximum bytes to capture from stdout/stderr
 */
@ConfigurationProperties(prefix = "agent-framework.sandbox")
public record SandboxConfig(
        int timeoutSeconds,
        int memoryLimitMb,
        boolean networkEnabled,
        int maxOutputBytes
) {
    public SandboxConfig {
        if (timeoutSeconds <= 0) timeoutSeconds = 60;
        if (memoryLimitMb <= 0) memoryLimitMb = 512;
        if (maxOutputBytes <= 0) maxOutputBytes = 65536; // 64KB
    }

    public static SandboxConfig defaults() {
        return new SandboxConfig(60, 512, false, 65536);
    }
}
