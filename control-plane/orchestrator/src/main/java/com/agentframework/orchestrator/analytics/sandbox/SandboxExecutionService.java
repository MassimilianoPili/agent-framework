package com.agentframework.orchestrator.analytics.sandbox;

import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-based sandbox for executing and verifying worker output in isolation.
 *
 * <p>Provides execution-based evaluation following SWE-bench methodology
 * (Jimenez et al. ICLR 2024). Uses Docker with security constraints:</p>
 * <ul>
 *   <li>{@code --network none}: no network access (prevents exfiltration)</li>
 *   <li>{@code --read-only}: read-only root filesystem</li>
 *   <li>{@code --memory}: bounded memory</li>
 *   <li>{@code --cpus 1}: single CPU core</li>
 *   <li>Seccomp default profile (blocks dangerous syscalls)</li>
 * </ul>
 *
 * <p>Uses Docker CLI via ProcessBuilder (no Docker Java SDK dependency — minimal footprint).</p>
 *
 * @see <a href="https://arxiv.org/abs/2310.06770">Jimenez et al., SWE-bench (ICLR 2024)</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.sandbox", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(SandboxConfig.class)
public class SandboxExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutionService.class);

    private final WorkspaceManager workspaceManager;
    private final SandboxConfig config;

    public SandboxExecutionService(WorkspaceManager workspaceManager, SandboxConfig config) {
        this.workspaceManager = workspaceManager;
        this.config = config;
    }

    /**
     * Executes a command in an isolated Docker container.
     *
     * @param spec sandbox specification (image, command, files, env, limits)
     * @return execution result with exit code, output, and timing
     */
    public SandboxResult execute(SandboxSpec spec) {
        List<String> dockerCmd = buildDockerCommand(spec);
        long startMs = System.currentTimeMillis();

        try {
            ProcessBuilder pb = new ProcessBuilder(dockerCmd);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            boolean completed = process.waitFor(
                    spec.timeoutMs() > 0 ? spec.timeoutMs() : config.timeoutSeconds() * 1000L,
                    TimeUnit.MILLISECONDS);

            String stdout = readStream(process.getInputStream(), config.maxOutputBytes());
            String stderr = readStream(process.getErrorStream(), config.maxOutputBytes());
            long durationMs = System.currentTimeMillis() - startMs;

            if (!completed) {
                process.destroyForcibly();
                log.warn("Sandbox timeout: spec={} timeout={}ms", spec.image(), spec.timeoutMs());
                return new SandboxResult(false, -1, stdout, stderr + "\n[TIMEOUT]", durationMs, true);
            }

            int exitCode = process.exitValue();
            log.debug("Sandbox executed: image={} exitCode={} duration={}ms",
                    spec.image(), exitCode, durationMs);

            return new SandboxResult(exitCode == 0, exitCode, stdout, stderr, durationMs, false);

        } catch (IOException | InterruptedException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Sandbox execution failed: {}", e.getMessage());
            return new SandboxResult(false, -1, "", e.getMessage(), durationMs, false);
        }
    }

    /**
     * Shortcut: compile and test Java code in a plan item's workspace.
     *
     * @param item          the plan item whose output to verify
     * @param workspacePath path to the workspace directory
     * @return sandbox result with compile+test outcome
     */
    public SandboxResult compileAndTest(PlanItem item, String workspacePath) {
        SandboxSpec spec = new SandboxSpec(
                "maven:3.9-eclipse-temurin-21-alpine",
                "mvn clean test -q -B",
                List.of(),
                Map.of("MAVEN_OPTS", "-Xmx384m"),
                config.timeoutSeconds() * 1000L,
                config.memoryLimitMb() * 1024L * 1024L
        );
        // Override workspace path
        return execute(spec.withWorkspacePath(workspacePath));
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private List<String> buildDockerCommand(SandboxSpec spec) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");

        // Security constraints
        if (!config.networkEnabled()) {
            cmd.add("--network");
            cmd.add("none");
        }
        cmd.add("--read-only");
        cmd.add("--memory");
        cmd.add(spec.memoryLimitBytes() > 0
                ? spec.memoryLimitBytes() + ""
                : config.memoryLimitMb() + "m");
        cmd.add("--cpus");
        cmd.add("1");

        // Tmpfs for writable directories
        cmd.add("--tmpfs");
        cmd.add("/tmp:rw,noexec,nosuid,size=128m");
        cmd.add("--tmpfs");
        cmd.add("/root/.m2:rw,size=256m");

        // Workspace mount (read-only)
        if (spec.workspacePath() != null) {
            cmd.add("-v");
            cmd.add(spec.workspacePath() + ":/workspace:ro");
            cmd.add("-w");
            cmd.add("/workspace");
        }

        // Environment variables
        for (Map.Entry<String, String> env : spec.env().entrySet()) {
            cmd.add("-e");
            cmd.add(env.getKey() + "=" + env.getValue());
        }

        // Image and command
        cmd.add(spec.image());
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(spec.command());

        return cmd;
    }

    private static String readStream(InputStream is, int maxBytes) {
        try {
            byte[] bytes = is.readNBytes(maxBytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[read error: " + e.getMessage() + "]";
        }
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * Sandbox execution result.
     *
     * @param success    true if exit code is 0
     * @param exitCode   process exit code (-1 for errors)
     * @param stdout     captured stdout (truncated to maxOutputBytes)
     * @param stderr     captured stderr (truncated to maxOutputBytes)
     * @param durationMs execution duration in milliseconds
     * @param timedOut   true if execution was terminated due to timeout
     */
    public record SandboxResult(
            boolean success,
            int exitCode,
            String stdout,
            String stderr,
            long durationMs,
            boolean timedOut
    ) {}

    /**
     * Sandbox execution specification.
     *
     * @param image            Docker image to use
     * @param command          shell command to execute
     * @param files            files to copy into the container (unused in current impl — workspace mount instead)
     * @param env              environment variables
     * @param timeoutMs        maximum execution time in milliseconds (0 = use config default)
     * @param memoryLimitBytes memory limit in bytes (0 = use config default)
     */
    public record SandboxSpec(
            String image,
            String command,
            List<String> files,
            Map<String, String> env,
            long timeoutMs,
            long memoryLimitBytes
    ) {
        public SandboxSpec withWorkspacePath(String workspacePath) {
            return new SandboxSpecWithWorkspace(image, command, files, env, timeoutMs, memoryLimitBytes, workspacePath);
        }

        public String workspacePath() { return null; }
    }

    /** Internal extension of SandboxSpec that carries a workspace path. */
    static final class SandboxSpecWithWorkspace extends SandboxSpec {
        private final String workspace;

        SandboxSpecWithWorkspace(String image, String command, List<String> files,
                                 Map<String, String> env, long timeoutMs, long memoryLimitBytes,
                                 String workspace) {
            super(image, command, files, env, timeoutMs, memoryLimitBytes);
            this.workspace = workspace;
        }

        @Override
        public String workspacePath() { return workspace; }
    }
}
