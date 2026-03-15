package com.agentframework.orchestrator.runtime;

import com.agentframework.orchestrator.runtime.ExecutionResult.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 3-level output parsing pipeline for sandbox execution results.
 *
 * <ol>
 *   <li><b>Level 1</b>: Exit code → success / failure signal</li>
 *   <li><b>Level 2</b>: Error classification (COMPILATION, RUNTIME, TEST_FAILURE, TIMEOUT, RESOURCE_EXCEEDED)</li>
 *   <li><b>Level 3</b>: Structured error extraction (file, line, column, message, stack trace)</li>
 * </ol>
 *
 * <p>Supports output from: javac/Maven, gcc/clang, Go, Python, Node.js/Jest, Rust/cargo.</p>
 */
public class OutputParser {

    // --- Level 2: Classification patterns ---

    private static final Pattern COMPILATION_JAVA = Pattern.compile(
            "\\[ERROR\\].*\\.java:\\[\\d+,\\d+\\]|error: (?:cannot find symbol|incompatible types)");
    private static final Pattern COMPILATION_GCC = Pattern.compile(
            ":\\d+:\\d+: (?:error|fatal error):");
    private static final Pattern COMPILATION_GO = Pattern.compile(
            "\\.go:\\d+:\\d+:.*(?:undefined|cannot|expected)");
    private static final Pattern COMPILATION_RUST = Pattern.compile(
            "error\\[E\\d{4}\\]:");
    private static final Pattern COMPILATION_PYTHON = Pattern.compile(
            "SyntaxError:|IndentationError:");

    private static final Pattern TEST_FAILURE_JUNIT = Pattern.compile(
            "Tests run: \\d+, Failures: [1-9]|Tests run: \\d+, Errors: [1-9]|BUILD FAILURE.*(?:Test|Surefire)");
    private static final Pattern TEST_FAILURE_PYTEST = Pattern.compile(
            "FAILED|\\d+ failed");
    private static final Pattern TEST_FAILURE_JEST = Pattern.compile(
            "Tests:\\s+\\d+ failed|FAIL\\s+\\w+");
    private static final Pattern TEST_FAILURE_GO = Pattern.compile(
            "--- FAIL:|FAIL\\s+\\w+/");

    private static final Pattern RUNTIME_EXCEPTION = Pattern.compile(
            "Exception in thread|panic:|Traceback \\(most recent call last\\)|" +
            "at\\s+\\S+\\.\\S+\\(\\S+\\.java:\\d+\\)|segmentation fault|SIGSEGV");

    private static final Pattern RESOURCE_OOM = Pattern.compile(
            "OutOfMemoryError|Killed|oom-kill|Cannot allocate memory|ENOMEM");

    // --- Level 3: Structured error extraction patterns ---

    /** Java: src/Foo.java:[12,5] error message */
    private static final Pattern JAVA_ERROR = Pattern.compile(
            "\\[ERROR\\]\\s+(/?.+\\.java):\\[(\\d+),(\\d+)\\]\\s+(.+)");

    /** GCC/Clang: file.c:12:5: error: message */
    private static final Pattern GCC_ERROR = Pattern.compile(
            "(/?.+\\.(?:c|cc|cpp|h|hpp)):(\\d+):(\\d+):\\s+(error|warning|fatal error):\\s+(.+)");

    /** Go: file.go:12:5: message */
    private static final Pattern GO_ERROR = Pattern.compile(
            "(/?.+\\.go):(\\d+):(\\d+):\\s+(.+)");

    /** Python traceback: File "foo.py", line 12 */
    private static final Pattern PYTHON_TB = Pattern.compile(
            "File \"(/?.+\\.py)\", line (\\d+)");

    /** Rust: error[E0308]: file.rs:12:5 */
    private static final Pattern RUST_ERROR = Pattern.compile(
            "-->\\s+(/?.+\\.rs):(\\d+):(\\d+)");

    /** Node/TypeScript: file.ts(12,5): error */
    private static final Pattern TS_ERROR = Pattern.compile(
            "(/?.+\\.(?:ts|js|tsx|jsx))\\((\\d+),(\\d+)\\):\\s+(error|warning)\\s+(.+)");

    private OutputParser() {}

    /**
     * Parses raw sandbox output into a structured {@link ExecutionResult}.
     *
     * @param exitCode  process exit code
     * @param stdout    captured stdout
     * @param stderr    captured stderr
     * @param timedOut  whether the process was killed due to timeout
     * @param durationMs wall-clock execution time
     * @return fully parsed execution result
     */
    public static ExecutionResult parse(int exitCode, String stdout, String stderr,
                                         boolean timedOut, long durationMs) {
        // Level 1: exit code
        if (exitCode == 0 && !timedOut) {
            return new ExecutionResult(
                    exitCode, stdout, stderr, false, durationMs,
                    ErrorClassification.NONE, List.of(), null, null);
        }

        String combined = (stdout != null ? stdout : "") + "\n" + (stderr != null ? stderr : "");

        // Level 2: classify error
        ErrorClassification classification = classify(combined, timedOut);

        // Level 3: extract structured errors
        List<ParsedError> errors = extractErrors(combined);

        // Parse test results if test failure
        TestResults testResults = classification == ErrorClassification.TEST_FAILURE
                ? parseTestSummary(combined)
                : null;

        return new ExecutionResult(
                exitCode, stdout, stderr, timedOut, durationMs,
                classification, errors, testResults, null);
    }

    /**
     * Level 2: Classifies the error type from combined output.
     */
    static ErrorClassification classify(String output, boolean timedOut) {
        if (timedOut) {
            return ErrorClassification.TIMEOUT;
        }
        if (matches(output, RESOURCE_OOM)) {
            return ErrorClassification.RESOURCE_EXCEEDED;
        }
        // Test failure before compilation — Maven reports "BUILD FAILURE" for both,
        // but test failures include "Tests run:" lines
        if (matches(output, TEST_FAILURE_JUNIT) || matches(output, TEST_FAILURE_PYTEST)
                || matches(output, TEST_FAILURE_JEST) || matches(output, TEST_FAILURE_GO)) {
            return ErrorClassification.TEST_FAILURE;
        }
        if (matches(output, COMPILATION_JAVA) || matches(output, COMPILATION_GCC)
                || matches(output, COMPILATION_GO) || matches(output, COMPILATION_RUST)
                || matches(output, COMPILATION_PYTHON)) {
            return ErrorClassification.COMPILATION;
        }
        if (matches(output, RUNTIME_EXCEPTION)) {
            return ErrorClassification.RUNTIME;
        }
        return ErrorClassification.RUNTIME; // default for non-zero exit
    }

    /**
     * Level 3: Extracts structured errors from output using language-specific patterns.
     */
    static List<ParsedError> extractErrors(String output) {
        List<ParsedError> errors = new ArrayList<>();
        String[] lines = output.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Java (Maven)
            Matcher m = JAVA_ERROR.matcher(line);
            if (m.find()) {
                errors.add(new ParsedError(m.group(1), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), m.group(4), "ERROR", null));
                continue;
            }

            // GCC/Clang
            m = GCC_ERROR.matcher(line);
            if (m.find()) {
                errors.add(new ParsedError(m.group(1), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), m.group(5),
                        m.group(4).toUpperCase().contains("WARNING") ? "WARNING" : "ERROR", null));
                continue;
            }

            // Go
            m = GO_ERROR.matcher(line);
            if (m.find()) {
                errors.add(new ParsedError(m.group(1), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), m.group(4), "ERROR", null));
                continue;
            }

            // Python traceback
            m = PYTHON_TB.matcher(line);
            if (m.find()) {
                String message = (i + 2 < lines.length) ? lines[i + 2].trim() : "unknown";
                String stackSnippet = extractStackSnippet(lines, i, 5);
                errors.add(new ParsedError(m.group(1), Integer.parseInt(m.group(2)),
                        -1, message, "ERROR", stackSnippet));
                continue;
            }

            // Rust
            m = RUST_ERROR.matcher(line);
            if (m.find()) {
                errors.add(new ParsedError(m.group(1), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), line.trim(), "ERROR", null));
                continue;
            }

            // TypeScript/Node
            m = TS_ERROR.matcher(line);
            if (m.find()) {
                errors.add(new ParsedError(m.group(1), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), m.group(5),
                        m.group(4).equalsIgnoreCase("warning") ? "WARNING" : "ERROR", null));
            }
        }

        return errors;
    }

    /**
     * Parses test summary lines (JUnit/Surefire format).
     *
     * <p>Matches: {@code Tests run: 42, Failures: 2, Errors: 1, Skipped: 3}</p>
     */
    static TestResults parseTestSummary(String output) {
        Pattern summary = Pattern.compile(
                "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");
        Matcher m = summary.matcher(output);

        int total = 0, failures = 0, errors = 0, skipped = 0;
        while (m.find()) {
            total += Integer.parseInt(m.group(1));
            failures += Integer.parseInt(m.group(2));
            errors += Integer.parseInt(m.group(3));
            skipped += Integer.parseInt(m.group(4));
        }

        if (total == 0) {
            // Try pytest format: "X passed, Y failed"
            Pattern pytest = Pattern.compile("(\\d+) passed(?:.*?(\\d+) failed)?");
            m = pytest.matcher(output);
            if (m.find()) {
                int passed = Integer.parseInt(m.group(1));
                failures = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                total = passed + failures;
            }
        }

        if (total == 0) {
            return null; // could not parse
        }

        int passed = total - failures - errors - skipped;
        return new TestResults(total, passed, failures, errors, skipped, -1.0, List.of());
    }

    // --- Helpers ---

    private static boolean matches(String text, Pattern pattern) {
        return pattern.matcher(text).find();
    }

    private static String extractStackSnippet(String[] lines, int start, int maxLines) {
        int end = Math.min(start + maxLines, lines.length);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }
}
