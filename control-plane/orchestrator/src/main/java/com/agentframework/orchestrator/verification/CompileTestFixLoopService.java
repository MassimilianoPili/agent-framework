package com.agentframework.orchestrator.verification;

import com.agentframework.orchestrator.runtime.ExecutionResult;
import com.agentframework.orchestrator.runtime.ExecutionResult.ErrorClassification;
import com.agentframework.orchestrator.runtime.ExecutionRuntimeOrchestrator;
import com.agentframework.orchestrator.verification.BehaviorPreservationChecker.PreservationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Iterative Compile → Test → Feedback → Fix verification loop.
 *
 * <p>Runs a bounded loop of up to {@link CompileTestFixConfig#maxIterations()} iterations,
 * executing code in a sandbox, parsing results, and generating structured feedback
 * for the worker to fix issues.</p>
 *
 * <p>Loop phases per iteration:</p>
 * <ol>
 *   <li><b>Compile</b>: attempt compilation, parse errors</li>
 *   <li><b>Test</b>: run tests if compilation succeeded</li>
 *   <li><b>Feedback</b>: generate structured feedback from failures</li>
 *   <li><b>Fix</b>: worker applies fixes (external — this service generates the feedback)</li>
 * </ol>
 *
 * <p>Gating per task type:</p>
 * <ul>
 *   <li>CODE → full loop (compile + test + fix)</li>
 *   <li>REASONING → skip (no execution)</li>
 *   <li>INFRASTRUCTURE → dry-run only</li>
 * </ul>
 *
 * <p>Hard cap at 5 iterations. Research: Shukla showed +37.6% critical vulnerabilities
 * after 5 fix iterations — more isn't better.</p>
 */
@Service
@ConditionalOnProperty(prefix = "compile-test-fix", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(CompileTestFixConfig.class)
public class CompileTestFixLoopService {

    private static final Logger log = LoggerFactory.getLogger(CompileTestFixLoopService.class);

    private final ExecutionRuntimeOrchestrator executionRuntime;
    private final CompileTestFixConfig config;
    private final JdbcTemplate jdbcTemplate;

    public CompileTestFixLoopService(ExecutionRuntimeOrchestrator executionRuntime,
                                      CompileTestFixConfig config,
                                      JdbcTemplate jdbcTemplate) {
        this.executionRuntime = executionRuntime;
        this.config = config;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs the compile-test-fix loop for a task.
     *
     * @param sessionId     execution session identifier
     * @param language      programming language
     * @param workspacePath host path to workspace
     * @param taskType      task type for gating (CODE, REASONING, INFRASTRUCTURE)
     * @param planId        plan UUID (nullable, for tracking)
     * @param itemId        plan item UUID (nullable, for tracking)
     * @return loop result with all iterations and final status
     */
    public LoopResult run(UUID sessionId, String language, String workspacePath,
                           String taskType, @Nullable UUID planId, @Nullable UUID itemId) {
        String gate = config.gateFor(taskType);

        if ("skip".equals(gate)) {
            log.debug("CTF loop skipped for task type {}", taskType);
            return new LoopResult(sessionId, 0, LoopStatus.SKIPPED,
                    "task type " + taskType + " does not require execution", List.of());
        }

        List<IterationResult> iterations = new ArrayList<>();
        ExecutionResult preRefactoringResult = null;
        boolean isDryRun = "dry-run".equals(gate);

        for (int i = 1; i <= config.maxIterations(); i++) {
            log.debug("CTF loop iteration {}/{} for session {}", i, config.maxIterations(), sessionId);

            // Execute
            Map<String, String> env = isDryRun
                    ? Map.of("DRY_RUN", "true")
                    : Map.of();

            ExecutionResult result = executionRuntime.execute(
                    language, null, workspacePath, planId, itemId, env);

            // Record iteration
            boolean compilationOk = result.errorClassification() != ErrorClassification.COMPILATION;
            int testsPassed = result.testResults() != null ? result.testResults().passed() : 0;
            int testsFailed = result.testResults() != null
                    ? result.testResults().failed() + result.testResults().errors() : 0;

            recordIteration(sessionId, i, compilationOk, testsPassed, testsFailed);

            // Generate feedback
            String feedback = generateFeedback(result);
            iterations.add(new IterationResult(i, result, compilationOk, testsPassed, testsFailed, feedback));

            // Save first successful result for behavior preservation check
            if (i == 1 && result.success()) {
                preRefactoringResult = result;
            }

            // Success — all tests pass
            if (result.success() && (result.testResults() == null || result.testResults().allPassed())) {
                log.info("CTF loop succeeded after {} iterations for session {}", i, sessionId);
                return new LoopResult(sessionId, i, LoopStatus.SUCCESS,
                        "all tests passed after " + i + " iterations", iterations);
            }

            // Dry-run: single iteration only
            if (isDryRun) {
                return new LoopResult(sessionId, 1, compilationOk ? LoopStatus.SUCCESS : LoopStatus.FAILED,
                        "dry-run: compilation " + (compilationOk ? "succeeded" : "failed"), iterations);
            }

            // Optimal iterations reached — warn if still failing
            if (i >= config.optimalIterations() && !result.success()) {
                log.warn("CTF loop at optimal limit ({}) but still failing for session {}",
                        config.optimalIterations(), sessionId);
            }
        }

        // Max iterations exhausted
        log.warn("CTF loop exhausted {} iterations for session {}", config.maxIterations(), sessionId);

        // Behavior preservation check on final result
        if (preRefactoringResult != null && !iterations.isEmpty()) {
            ExecutionResult lastResult = iterations.get(iterations.size() - 1).result();
            PreservationResult preservation = BehaviorPreservationChecker.check(preRefactoringResult, lastResult);
            if (!preservation.preserved()) {
                return new LoopResult(sessionId, config.maxIterations(), LoopStatus.REGRESSION,
                        "behavior regression: " + preservation.reason(), iterations);
            }
        }

        return new LoopResult(sessionId, config.maxIterations(), LoopStatus.FAILED,
                "max iterations exhausted without full success", iterations);
    }

    // --- Private helpers ---

    private String generateFeedback(ExecutionResult result) {
        StringBuilder fb = new StringBuilder();

        if (result.errorClassification() == ErrorClassification.COMPILATION) {
            fb.append("COMPILATION ERRORS:\n");
            for (var error : result.parsedErrors()) {
                fb.append(String.format("  %s:%d:%d — %s\n",
                        error.file() != null ? error.file() : "?",
                        error.line(), error.column(), error.message()));
            }
        } else if (result.errorClassification() == ErrorClassification.TEST_FAILURE && result.testResults() != null) {
            fb.append(String.format("TEST FAILURES: %d/%d tests failed\n",
                    result.testResults().failed(), result.testResults().total()));
            for (var failure : result.testResults().failures()) {
                fb.append(String.format("  %s.%s: %s\n",
                        failure.testClass(), failure.testMethod(), failure.message()));
            }
        } else if (result.errorClassification() == ErrorClassification.TIMEOUT) {
            fb.append("TIMEOUT: execution exceeded time limit\n");
        } else if (result.errorClassification() == ErrorClassification.RESOURCE_EXCEEDED) {
            fb.append("RESOURCE EXCEEDED: out of memory or CPU limit\n");
        } else if (result.errorClassification() == ErrorClassification.RUNTIME) {
            fb.append("RUNTIME ERROR:\n");
            if (result.stderr() != null && !result.stderr().isBlank()) {
                fb.append("  ").append(result.stderr().lines().findFirst().orElse("unknown error")).append('\n');
            }
        }

        return fb.toString().trim();
    }

    private void recordIteration(UUID sessionId, int iteration, boolean compilationOk,
                                  int testsPassed, int testsFailed) {
        try {
            jdbcTemplate.update("""
                INSERT INTO compile_test_iterations (id, session_id, iteration, compilation_ok, tests_passed, tests_failed)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?)
                """,
                    UUID.randomUUID().toString(), sessionId.toString(),
                    iteration, compilationOk, testsPassed, testsFailed);
        } catch (Exception e) {
            log.debug("Failed to record CTF iteration: {}", e.getMessage());
        }
    }

    // --- Public types ---

    public enum LoopStatus { SUCCESS, FAILED, REGRESSION, SKIPPED }

    /**
     * Result of a single iteration.
     */
    public record IterationResult(
            int iteration,
            ExecutionResult result,
            boolean compilationOk,
            int testsPassed,
            int testsFailed,
            String feedback
    ) {}

    /**
     * Result of the complete compile-test-fix loop.
     *
     * @param sessionId       execution session UUID
     * @param iterationsRun   number of iterations completed
     * @param status          final loop status
     * @param summary         human-readable summary
     * @param iterations      per-iteration results
     */
    public record LoopResult(
            UUID sessionId,
            int iterationsRun,
            LoopStatus status,
            String summary,
            List<IterationResult> iterations
    ) {}
}
