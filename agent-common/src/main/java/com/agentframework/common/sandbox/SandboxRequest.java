package com.agentframework.common.sandbox;

import java.util.List;

/**
 * Immutable request for executing a command inside a sandboxed Docker container.
 *
 * <p>The sandbox mounts the workspace read-only at {@code /code} and provides
 * a writable output directory at {@code /out}. Network access is disabled by default.</p>
 *
 * @param sandboxImage     Docker image to use (e.g. "agent-sandbox-java:21")
 * @param command          command to execute inside the container (e.g. ["mvn", "compile"])
 * @param workspacePath    host path to the workspace directory (mounted as /code:ro)
 * @param outputPath       host path for build output (mounted as /out:rw)
 * @param memoryLimitMb    container memory limit in MB (default 512)
 * @param cpuLimit         CPU limit (e.g. 1.0 = 1 core, default 1.0)
 * @param timeoutSeconds   max execution time before forceful termination (default 120)
 * @param networkDisabled  whether to disable network access (default true)
 */
public record SandboxRequest(
    String sandboxImage,
    List<String> command,
    String workspacePath,
    String outputPath,
    int memoryLimitMb,
    double cpuLimit,
    int timeoutSeconds,
    boolean networkDisabled
) {
    /** Convenience constructor with sensible defaults. */
    public SandboxRequest(String sandboxImage, List<String> command,
                          String workspacePath, String outputPath) {
        this(sandboxImage, command, workspacePath, outputPath, 512, 1.0, 120, true);
    }
}
