package com.agentframework.orchestrator.runtime;

import com.agentframework.orchestrator.analytics.sandbox.SandboxExecutionService;
import com.agentframework.orchestrator.analytics.sandbox.SandboxExecutionService.SandboxResult;
import com.agentframework.orchestrator.analytics.sandbox.SandboxExecutionService.SandboxSpec;
import com.agentframework.orchestrator.domain.PlanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates execution sessions: container pool management, timeout escalation,
 * output parsing, and resource accounting.
 *
 * <p>This is the integration layer that connects the existing {@link SandboxExecutionService}
 * (Docker CLI) with the runtime subsystem: {@link ContainerPool} (warm images),
 * {@link OutputParser} (3-level parsing), and {@link ResourceAccountingService} (metrics).</p>
 *
 * <p>Execution flow:</p>
 * <ol>
 *   <li>Resolve Docker image from {@link ContainerPool} for the target language</li>
 *   <li>Build {@link SandboxSpec} with timeout escalation (soft → hard)</li>
 *   <li>Execute via {@link SandboxExecutionService}</li>
 *   <li>Parse output via {@link OutputParser} into structured {@link ExecutionResult}</li>
 *   <li>Record metrics via {@link ResourceAccountingService}</li>
 * </ol>
 *
 * @see <a href="https://arxiv.org/abs/2310.06770">SWE-bench (Jimenez et al. ICLR 2024)</a>
 */
@Service
@ConditionalOnProperty(prefix = "execution-runtime", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ExecutionRuntimeConfig.class)
public class ExecutionRuntimeOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRuntimeOrchestrator.class);

    /** Default build commands per language. */
    private static final Map<String, String> DEFAULT_COMMANDS = Map.of(
            "java", "mvn clean test -q -B",
            "go", "go test ./...",
            "python", "python -m pytest -q",
            "node", "npm test --silent",
            "rust", "cargo test --quiet",
            "cpp", "make test",
            "dotnet", "dotnet test --verbosity quiet"
    );

    private final SandboxExecutionService sandboxService;
    private final ContainerPool containerPool;
    private final ResourceAccountingService resourceAccounting;
    private final ExecutionRuntimeConfig config;

    public ExecutionRuntimeOrchestrator(SandboxExecutionService sandboxService,
                                         ContainerPool containerPool,
                                         ResourceAccountingService resourceAccounting,
                                         ExecutionRuntimeConfig config) {
        this.sandboxService = sandboxService;
        this.containerPool = containerPool;
        this.resourceAccounting = resourceAccounting;
        this.config = config;
    }

    /**
     * Executes code in an isolated sandbox and returns a fully parsed result.
     *
     * @param language      programming language (java, go, python, node, etc.)
     * @param command       shell command to execute (null = use language default)
     * @param workspacePath host path to mount as /workspace
     * @param planId        associated plan UUID (for tracking, nullable)
     * @param itemId        associated plan item UUID (for tracking, nullable)
     * @param env           additional environment variables
     * @return structured execution result with parsed errors and resource usage
     */
    public ExecutionResult execute(String language, @Nullable String command,
                                    String workspacePath, @Nullable UUID planId,
                                    @Nullable UUID itemId, Map<String, String> env) {
        UUID sessionId = UUID.randomUUID();
        String image = containerPool.getImage(language);

        if (image == null) {
            log.warn("No image available for language '{}' — cannot execute", language);
            return ExecutionResult.unavailable(language);
        }

        String cmd = command != null ? command : DEFAULT_COMMANDS.getOrDefault(language, "echo 'no default command'");

        // Build spec with soft timeout (hard timeout handled by process escalation)
        long timeoutMs = config.timeout().softSeconds() * 1000L;
        long memoryBytes = config.resources().maxMemoryMb() * 1024L * 1024L;

        SandboxSpec spec = new SandboxSpec(image, cmd, List.of(), env != null ? env : Map.of(),
                timeoutMs, memoryBytes, workspacePath);

        log.debug("Execution session {}: language={}, image={}, command='{}'",
                sessionId, language, image, cmd);

        // Execute
        SandboxResult raw = sandboxService.execute(spec);

        // Parse output (3-level pipeline)
        ExecutionResult result = OutputParser.parse(
                raw.exitCode(), raw.stdout(), raw.stderr(),
                raw.timedOut(), raw.durationMs());

        // Record metrics
        resourceAccounting.recordSession(sessionId, planId, itemId,
                language, raw.exitCode(), raw.durationMs(), result.resourceUsage());

        // Record structured errors
        for (ExecutionResult.ParsedError error : result.parsedErrors()) {
            resourceAccounting.recordError(sessionId, error);
        }

        log.info("Execution session {} completed: exitCode={}, classification={}, errors={}, duration={}ms",
                sessionId, result.exitCode(), result.errorClassification(),
                result.parsedErrors().size(), result.durationMs());

        return result;
    }

    /**
     * Shortcut: compile and test a plan item's workspace.
     *
     * @param item          the plan item to verify
     * @param workspacePath host path to the workspace
     * @param language      programming language
     * @return execution result
     */
    public ExecutionResult compileAndTest(PlanItem item, String workspacePath, String language) {
        return execute(language, null, workspacePath,
                item.getPlan() != null ? item.getPlan().getId() : null,
                item.getId(), Map.of("MAVEN_OPTS", "-Xmx384m"));
    }

    /**
     * Returns runtime status for monitoring endpoints.
     */
    public Map<String, Object> status() {
        return Map.of(
                "enabled", true,
                "pool", containerPool.status(),
                "warmImages", containerPool.warmCount(),
                "config", Map.of(
                        "softTimeoutSec", config.timeout().softSeconds(),
                        "hardTimeoutSec", config.timeout().hardSeconds(),
                        "maxMemoryMb", config.resources().maxMemoryMb(),
                        "maxCpuSec", config.resources().maxCpuSeconds()
                ));
    }
}
