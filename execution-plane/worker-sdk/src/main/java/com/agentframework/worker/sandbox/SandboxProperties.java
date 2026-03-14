package com.agentframework.worker.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for the execution sandbox.
 *
 * <p>Each worker profile maps to a specific Docker image containing the
 * appropriate build toolchain. Profiles without a configured image skip
 * the sandbox build step.</p>
 *
 * <p>Build commands for Node/React/Next use a copy-to-tmp strategy to work
 * around the read-only workspace mount ({@code /code:ro}). The pattern
 * {@code cp -r /code /tmp/build && cd /tmp/build && npm install && npm run build}
 * ensures dependencies can be installed without modifying the source.</p>
 *
 * <pre>
 * agent.worker.sandbox.enabled=true
 * agent.worker.sandbox.max-concurrent=2
 * agent.worker.sandbox.default-timeout-seconds=120
 * agent.worker.sandbox.default-memory-mb=512
 * agent.worker.sandbox.images-by-profile.be-java=agent-sandbox-java:21
 * </pre>
 */
@ConfigurationProperties(prefix = "agent.worker.sandbox")
public class SandboxProperties {

    private boolean enabled = false;
    private int maxConcurrent = 2;
    private int defaultTimeoutSeconds = 120;
    private int defaultMemoryMb = 512;
    private double defaultCpuLimit = 1.0;

    /**
     * Maps worker profile names to sandbox Docker image tags.
     * Profiles not listed here will skip the sandbox build step.
     */
    private Map<String, String> imagesByProfile = new HashMap<>(Map.of(
        "be-java",    "agent-sandbox-java:21",
        "be-go",      "agent-sandbox-go:1.22",
        "be-python",  "agent-sandbox-python:3.12",
        "be-node",    "agent-sandbox-node:22",
        "fe-react",   "agent-sandbox-node:22",
        "fe-nextjs",  "agent-sandbox-node:22",
        "be-rust",    "agent-sandbox-rust:latest",
        "be-cpp",     "agent-sandbox-cpp:latest",
        "be-dotnet",  "agent-sandbox-dotnet:8.0"
    ));

    /**
     * Maps worker profile names to their build commands.
     * Node/React/Next use copy-to-tmp to work with read-only workspace.
     */
    private Map<String, String> buildCommands = new HashMap<>(Map.ofEntries(
        Map.entry("be-java",    "cp -r /code /tmp/build && cd /tmp/build && mvn compile -q -B"),
        Map.entry("be-go",      "cp -r /code /tmp/build && cd /tmp/build && go build ./..."),
        Map.entry("be-python",  "python -m py_compile /code/*.py"),
        Map.entry("be-node",    "cp -r /code /tmp/build && cd /tmp/build && npm install && npm run build"),
        Map.entry("fe-react",   "cp -r /code /tmp/build && cd /tmp/build && npm install && npm run build"),
        Map.entry("fe-nextjs",  "cp -r /code /tmp/build && cd /tmp/build && npm install && npm run build"),
        Map.entry("be-rust",    "cp -r /code /tmp/build && cd /tmp/build && cargo build"),
        Map.entry("be-cpp",     "cp -r /code /tmp/build && cd /tmp/build && cmake . && make"),
        Map.entry("be-dotnet",  "cp -r /code /tmp/build && cd /tmp/build && dotnet build")
    ));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }

    public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) { this.defaultTimeoutSeconds = defaultTimeoutSeconds; }

    public int getDefaultMemoryMb() { return defaultMemoryMb; }
    public void setDefaultMemoryMb(int defaultMemoryMb) { this.defaultMemoryMb = defaultMemoryMb; }

    public double getDefaultCpuLimit() { return defaultCpuLimit; }
    public void setDefaultCpuLimit(double defaultCpuLimit) { this.defaultCpuLimit = defaultCpuLimit; }

    public Map<String, String> getImagesByProfile() { return imagesByProfile; }
    public void setImagesByProfile(Map<String, String> imagesByProfile) { this.imagesByProfile = imagesByProfile; }

    public Map<String, String> getBuildCommands() { return buildCommands; }
    public void setBuildCommands(Map<String, String> buildCommands) { this.buildCommands = buildCommands; }
}
