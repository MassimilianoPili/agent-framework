package com.agentframework.worker.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Configuration properties for the execution sandbox.
 *
 * <p>Each worker profile maps to a specific Docker image containing the
 * appropriate build toolchain. Profiles without a configured image skip
 * the sandbox build step.</p>
 *
 * <pre>
 * agent.worker.sandbox.enabled=true
 * agent.worker.sandbox.default-timeout-seconds=120
 * agent.worker.sandbox.default-memory-mb=512
 * agent.worker.sandbox.images-by-profile.be-java=agent-sandbox-java:21
 * </pre>
 */
@ConfigurationProperties(prefix = "agent.worker.sandbox")
public class SandboxProperties {

    private boolean enabled = false;
    private int defaultTimeoutSeconds = 120;
    private int defaultMemoryMb = 512;
    private double defaultCpuLimit = 1.0;

    /**
     * Maps worker profile names to sandbox Docker image tags.
     * Profiles not listed here will skip the sandbox build step.
     */
    private Map<String, String> imagesByProfile = Map.of(
        "be-java",    "agent-sandbox-java:21",
        "be-go",      "agent-sandbox-go:1.22",
        "be-python",  "agent-sandbox-python:3.12",
        "be-node",    "agent-sandbox-node:22",
        "fe-react",   "agent-sandbox-node:22",
        "fe-nextjs",  "agent-sandbox-node:22",
        "be-rust",    "agent-sandbox-rust:latest"
    );

    /**
     * Maps worker profile names to their build commands.
     * Each entry is a single string that will be split by whitespace.
     */
    private Map<String, String> buildCommands = Map.of(
        "be-java",    "mvn compile -q -B",
        "be-go",      "go build ./...",
        "be-python",  "python -m py_compile *.py",
        "be-node",    "npm install && npm run build",
        "fe-react",   "npm install && npm run build",
        "fe-nextjs",  "npm install && npm run build",
        "be-rust",    "cargo build"
    );

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

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
