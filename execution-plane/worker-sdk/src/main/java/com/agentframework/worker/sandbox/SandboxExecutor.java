package com.agentframework.worker.sandbox;

import com.agentframework.common.sandbox.SandboxRequest;
import com.agentframework.common.sandbox.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Executes commands inside isolated Docker containers for build/test verification.
 *
 * <p>Uses {@link ProcessBuilder} to invoke {@code docker run} — no docker-java dependency.
 * Each invocation creates a new container with strict security constraints:</p>
 * <ul>
 *   <li>{@code --network none} — no network access (prevents exfiltration)</li>
 *   <li>{@code --read-only} — read-only root filesystem</li>
 *   <li>{@code --user 1000:1000} — non-root execution</li>
 *   <li>{@code --memory / --cpus} — resource limits</li>
 *   <li>{@code --tmpfs /tmp} — writable temp (exec allowed for build tools)</li>
 *   <li>{@code --tmpfs /home/sandbox/.m2} — Maven local repo cache</li>
 *   <li>{@code --tmpfs /home/sandbox/.cache} — generic build cache</li>
 * </ul>
 *
 * <p>Concurrency is limited by a {@link Semaphore} to prevent OOM on the host
 * (16 GB RAM, 512 MB per sandbox). Default: max 2 concurrent sandbox containers.</p>
 *
 * <p>Created as a bean by {@link SandboxAutoConfiguration} when
 * {@code agent.worker.sandbox.enabled=true}.</p>
 */
public class SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutor.class);
    private static final long SEMAPHORE_ACQUIRE_TIMEOUT_SECONDS = 30;

    private final Semaphore semaphore;

    public SandboxExecutor(int maxConcurrent) {
        this.semaphore = new Semaphore(maxConcurrent, true);
        log.info("SandboxExecutor initialized with maxConcurrent={}", maxConcurrent);
    }

    /**
     * Executes the given sandbox request and returns the result.
     * Blocks if the concurrency limit is reached, failing after timeout.
     *
     * @param request sandbox execution parameters
     * @return result containing exit code, stdout, stderr, duration, and timeout status
     */
    public SandboxResult execute(SandboxRequest request) {
        long startMs = Instant.now().toEpochMilli();

        try {
            if (!semaphore.tryAcquire(SEMAPHORE_ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Sandbox queue full (no permit within {}s) for image={}",
                         SEMAPHORE_ACQUIRE_TIMEOUT_SECONDS, request.sandboxImage());
                long durationMs = Instant.now().toEpochMilli() - startMs;
                return new SandboxResult(-1, "", "Sandbox queue full — max concurrent limit reached", durationMs, false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = Instant.now().toEpochMilli() - startMs;
            return new SandboxResult(-1, "", "Sandbox interrupted while waiting for permit", durationMs, false);
        }

        try {
            return doExecute(request, startMs);
        } finally {
            semaphore.release();
        }
    }

    private SandboxResult doExecute(SandboxRequest request, long startMs) {
        List<String> cmd = buildDockerCommand(request);
        log.info("Sandbox execute: image={}, workspace={}, timeout={}s",
                 request.sandboxImage(), request.workspacePath(), request.timeoutSeconds());
        log.debug("Sandbox command: {}", cmd);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Read stdout and stderr concurrently to avoid blocking on full pipe buffers
            StreamCapture stdoutCapture = new StreamCapture(process.getInputStream());
            StreamCapture stderrCapture = new StreamCapture(process.getErrorStream());
            stdoutCapture.start();
            stderrCapture.start();

            boolean finished = process.waitFor(request.timeoutSeconds(), TimeUnit.SECONDS);
            long durationMs = Instant.now().toEpochMilli() - startMs;

            if (!finished) {
                log.warn("Sandbox timed out after {}s for image={}", request.timeoutSeconds(), request.sandboxImage());
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS); // grace period for cleanup
            }

            stdoutCapture.join(3000);
            stderrCapture.join(3000);

            int exitCode = finished ? process.exitValue() : 137; // SIGKILL
            String stdout = truncate(stdoutCapture.getOutput());
            String stderr = truncate(stderrCapture.getOutput());

            log.info("Sandbox completed: exit={}, timedOut={}, duration={}ms, stdout={}B, stderr={}B",
                     exitCode, !finished, durationMs, stdout.length(), stderr.length());

            return new SandboxResult(exitCode, stdout, stderr, durationMs, !finished);

        } catch (IOException e) {
            log.error("Failed to start sandbox process: {}", e.getMessage());
            long durationMs = Instant.now().toEpochMilli() - startMs;
            return new SandboxResult(-1, "", "Failed to start sandbox: " + e.getMessage(), durationMs, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = Instant.now().toEpochMilli() - startMs;
            return new SandboxResult(-1, "", "Sandbox interrupted", durationMs, false);
        }
    }

    /**
     * Builds the {@code docker run} command with security flags.
     *
     * <p>{@code /tmp} tmpfs allows exec — build tools (Maven, Go, Rust) write
     * executables there. Additional tmpfs mounts provide writable caches for
     * build tool dependencies ({@code .m2}, {@code .cache}).</p>
     */
    List<String> buildDockerCommand(SandboxRequest request) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");

        // Security constraints
        if (request.networkDisabled()) {
            cmd.add("--network");
            cmd.add("none");
        }
        cmd.add("--read-only");
        cmd.add("--user");
        cmd.add("1000:1000");

        // Resource limits
        cmd.add("--memory");
        cmd.add(request.memoryLimitMb() + "m");
        cmd.add("--cpus");
        cmd.add(String.valueOf(request.cpuLimit()));

        // Writable /tmp — exec allowed (Go, Rust, Maven write executables here)
        cmd.add("--tmpfs");
        cmd.add("/tmp:rw,nosuid,size=256m");

        // Build tool caches (Maven .m2, npm cache, Go modules, Cargo, etc.)
        cmd.add("--tmpfs");
        cmd.add("/home/sandbox/.m2:rw,size=256m");
        cmd.add("--tmpfs");
        cmd.add("/home/sandbox/.cache:rw,size=128m");

        // Volume mounts: workspace as read-only, output as read-write
        cmd.add("-v");
        cmd.add(request.workspacePath() + ":/code:ro");
        if (request.outputPath() != null) {
            cmd.add("-v");
            cmd.add(request.outputPath() + ":/out:rw");
        }

        // Working directory inside container
        cmd.add("-w");
        cmd.add("/code");

        // Image
        cmd.add(request.sandboxImage());

        // Command
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(String.join(" ", request.command()));

        return cmd;
    }

    /**
     * Truncates output to {@link SandboxResult#MAX_OUTPUT_BYTES}.
     */
    private static String truncate(String output) {
        if (output == null) return "";
        if (output.length() <= SandboxResult.MAX_OUTPUT_BYTES) return output;
        return output.substring(0, SandboxResult.MAX_OUTPUT_BYTES)
               + "\n... [truncated at " + SandboxResult.MAX_OUTPUT_BYTES + " bytes]";
    }

    /**
     * Background thread that reads an InputStream into a String.
     * Prevents pipe buffer deadlocks when both stdout and stderr produce output.
     */
    private static class StreamCapture extends Thread {
        private final InputStream inputStream;
        private String output = "";

        StreamCapture(InputStream inputStream) {
            this.inputStream = inputStream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                byte[] bytes = inputStream.readAllBytes();
                output = new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                output = "[stream read error: " + e.getMessage() + "]";
            }
        }

        String getOutput() {
            return output;
        }
    }
}
