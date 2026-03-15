package com.agentframework.orchestrator.runtime;

import java.util.List;
import java.util.Map;

/**
 * Structured result of a sandbox execution session.
 *
 * <p>Captures the full lifecycle of a single execution: raw output, error classification,
 * parsed test results, and resource usage metrics for cost accounting.</p>
 *
 * @param exitCode           process exit code (0 = success)
 * @param stdout             captured standard output (truncated to max output bytes)
 * @param stderr             captured standard error (truncated to max output bytes)
 * @param timedOut           true if execution was killed due to timeout
 * @param durationMs         wall-clock execution time in milliseconds
 * @param errorClassification 3-level error classification (null if exit code 0)
 * @param parsedErrors       structured errors extracted from output (empty if none)
 * @param testResults        parsed test results (null if no tests detected)
 * @param resourceUsage      cgroups v2 resource metrics (null if unavailable)
 */
public record ExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        long durationMs,
        ErrorClassification errorClassification,
        List<ParsedError> parsedErrors,
        TestResults testResults,
        ResourceUsage resourceUsage
) {

    public boolean success() {
        return exitCode == 0 && !timedOut;
    }

    /**
     * Creates an unavailable result when the execution environment is not ready.
     */
    public static ExecutionResult unavailable(String reason) {
        return new ExecutionResult(-1, "", "Execution unavailable: " + reason,
                false, 0, ErrorClassification.NONE, List.of(), null, null);
    }

    /**
     * Error classification (level 2 of the 3-level output parsing pipeline).
     */
    public enum ErrorClassification {
        COMPILATION,
        RUNTIME,
        TEST_FAILURE,
        TIMEOUT,
        RESOURCE_EXCEEDED,
        NONE
    }

    /**
     * Structured error extracted from compiler/runtime output (level 3 parsing).
     *
     * @param file       source file path (null if not determinable)
     * @param line       line number (-1 if not determinable)
     * @param column     column number (-1 if not determinable)
     * @param message    error message
     * @param severity   ERROR, WARNING, INFO
     * @param stackTrace stack trace snippet (null if not a runtime error)
     */
    public record ParsedError(
            String file,
            int line,
            int column,
            String message,
            String severity,
            String stackTrace
    ) {}

    /**
     * Aggregated test execution results parsed from JUnit XML / pytest JSON / Jest JSON.
     */
    public record TestResults(
            int total,
            int passed,
            int failed,
            int errors,
            int skipped,
            double coveragePercent,
            List<TestFailure> failures
    ) {
        public boolean allPassed() {
            return failed == 0 && errors == 0;
        }
    }

    /**
     * Individual test failure detail.
     */
    public record TestFailure(
            String testClass,
            String testMethod,
            String message,
            String stackTrace
    ) {}

    /**
     * Resource usage metrics collected via cgroups v2 (Linux) or process accounting.
     *
     * @param cpuSeconds    total CPU time consumed
     * @param peakMemoryMb  peak resident memory in MB
     * @param ioReadBytes   bytes read from disk
     * @param ioWriteBytes  bytes written to disk
     */
    public record ResourceUsage(
            double cpuSeconds,
            long peakMemoryMb,
            long ioReadBytes,
            long ioWriteBytes
    ) {}
}
