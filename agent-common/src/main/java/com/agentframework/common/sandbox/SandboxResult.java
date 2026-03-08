package com.agentframework.common.sandbox;

/**
 * Result of a sandboxed Docker container execution.
 *
 * @param exitCode   process exit code (0 = success)
 * @param stdout     captured stdout (truncated to {@link #MAX_OUTPUT_BYTES})
 * @param stderr     captured stderr (truncated to {@link #MAX_OUTPUT_BYTES})
 * @param durationMs wall-clock execution time in milliseconds
 * @param timedOut   true if the process was killed due to timeout
 */
public record SandboxResult(
    int exitCode,
    String stdout,
    String stderr,
    long durationMs,
    boolean timedOut
) {
    /** Maximum bytes captured per stream (stdout/stderr) — 50 KB. */
    public static final int MAX_OUTPUT_BYTES = 50 * 1024;

    /** Returns true if the sandbox command completed successfully (exit code 0, no timeout). */
    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }
}
