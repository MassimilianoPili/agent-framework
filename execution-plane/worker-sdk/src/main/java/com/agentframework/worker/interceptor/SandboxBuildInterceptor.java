package com.agentframework.worker.interceptor;

import com.agentframework.common.sandbox.SandboxRequest;
import com.agentframework.common.sandbox.SandboxResult;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.dto.AgentTask;
import com.agentframework.worker.sandbox.SandboxExecutor;
import com.agentframework.worker.sandbox.SandboxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Post-execution interceptor that runs a compilation/build step in an isolated
 * Docker sandbox after a domain worker generates code.
 *
 * <p>Only triggers when all conditions are met:</p>
 * <ol>
 *   <li>Sandbox is enabled ({@code SandboxExecutor} bean exists)</li>
 *   <li>The task has a workspace path (Fase 2 workspace feature)</li>
 *   <li>The worker type is a code-producing domain worker (BE, FE, MOBILE)</li>
 *   <li>A sandbox image is configured for the worker's profile</li>
 * </ol>
 *
 * <p>The build result (exit code, stdout, stderr) is injected into the worker's
 * result JSON so the review-worker can see the compilation output.</p>
 */
@Component
@ConditionalOnBean(SandboxExecutor.class)
public class SandboxBuildInterceptor implements WorkerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SandboxBuildInterceptor.class);

    /** Worker types that produce source code files. */
    private static final Set<String> DOMAIN_WORKER_TYPES = Set.of("BE", "FE", "MOBILE");

    private final SandboxExecutor sandboxExecutor;
    private final SandboxProperties properties;
    private final ObjectMapper objectMapper;

    public SandboxBuildInterceptor(SandboxExecutor sandboxExecutor,
                                   SandboxProperties properties,
                                   ObjectMapper objectMapper) {
        this.sandboxExecutor = sandboxExecutor;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String afterExecute(AgentContext ctx, String result, AgentTask task) {
        // Skip if no workspace or not a domain worker
        if (ctx.workspacePath() == null || ctx.workspacePath().isBlank()) {
            return result;
        }
        if (!DOMAIN_WORKER_TYPES.contains(task.workerType())) {
            return result;
        }

        // Skip if no sandbox image configured for this profile
        String image = properties.getImagesByProfile().get(task.workerProfile());
        if (image == null) {
            log.debug("No sandbox image configured for profile '{}', skipping build", task.workerProfile());
            return result;
        }

        // Resolve build command
        String buildCmdStr = properties.getBuildCommands().get(task.workerProfile());
        if (buildCmdStr == null || buildCmdStr.isBlank()) {
            log.debug("No build command configured for profile '{}', skipping build", task.workerProfile());
            return result;
        }
        List<String> buildCommand = Arrays.asList(buildCmdStr.split("\\s+"));

        String outputPath = ctx.workspacePath() + "/out";

        SandboxRequest request = new SandboxRequest(
            image,
            buildCommand,
            ctx.workspacePath(),
            outputPath,
            properties.getDefaultMemoryMb(),
            properties.getDefaultCpuLimit(),
            properties.getDefaultTimeoutSeconds(),
            true  // network disabled
        );

        try {
            SandboxResult buildResult = sandboxExecutor.execute(request);
            return enrichResultWithBuildOutput(result, buildResult);
        } catch (Exception e) {
            log.warn("Sandbox build failed for task {} (profile={}): {}",
                     task.taskKey(), task.workerProfile(), e.getMessage());
            return result; // return original result on sandbox failure
        }
    }

    /**
     * Enriches the worker's result JSON with build output fields.
     *
     * <p>Adds the following fields to the result JSON:</p>
     * <ul>
     *   <li>{@code build_exit_code} — process exit code (0 = success)</li>
     *   <li>{@code build_success} — boolean success flag</li>
     *   <li>{@code build_stdout} — captured stdout</li>
     *   <li>{@code build_stderr} — captured stderr</li>
     *   <li>{@code build_duration_ms} — wall-clock time</li>
     *   <li>{@code build_timed_out} — timeout flag</li>
     * </ul>
     */
    private String enrichResultWithBuildOutput(String result, SandboxResult build) {
        try {
            ObjectNode root;
            if (result != null && result.startsWith("{")) {
                root = (ObjectNode) objectMapper.readTree(result);
            } else {
                root = objectMapper.createObjectNode();
                if (result != null) {
                    root.put("worker_output", result);
                }
            }

            root.put("build_exit_code", build.exitCode());
            root.put("build_success", build.isSuccess());
            root.put("build_duration_ms", build.durationMs());
            root.put("build_timed_out", build.timedOut());

            if (build.stdout() != null && !build.stdout().isBlank()) {
                root.put("build_stdout", build.stdout());
            }
            if (build.stderr() != null && !build.stderr().isBlank()) {
                root.put("build_stderr", build.stderr());
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to enrich result with build output: {}", e.getMessage());
            return result;
        }
    }

    @Override
    public int getOrder() {
        // Run after other interceptors (metrics, validation) but the order is flexible
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
