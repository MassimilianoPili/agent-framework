package com.agentframework.orchestrator.verification;

import com.agentframework.orchestrator.runtime.ExecutionResult.TestFailure;
import com.agentframework.orchestrator.runtime.ExecutionResult.TestResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses test results from JUnit XML, pytest JSON, and Jest JSON output files.
 *
 * <p>Supports 3 formats:</p>
 * <ul>
 *   <li><b>JUnit XML</b> (Surefire/Failsafe): {@code target/surefire-reports/TEST-*.xml}</li>
 *   <li><b>pytest</b>: stdout summary or {@code --json-report}</li>
 *   <li><b>Jest</b>: stdout summary or {@code --json} output</li>
 * </ul>
 *
 * <p>Falls back to stdout parsing when report files are unavailable (sandbox = read-only).</p>
 */
public class TestResultParser {

    private static final Logger log = LoggerFactory.getLogger(TestResultParser.class);

    // --- JUnit XML patterns ---
    private static final Pattern JUNIT_TESTSUITE = Pattern.compile(
            "<testsuite[^>]*\\btests=\"(\\d+)\"[^>]*\\bfailures=\"(\\d+)\"[^>]*\\berrors=\"(\\d+)\"[^>]*(?:\\bskipped=\"(\\d+)\")?");
    private static final Pattern JUNIT_TESTCASE_FAILURE = Pattern.compile(
            "<testcase[^>]*\\bclassname=\"([^\"]+)\"[^>]*\\bname=\"([^\"]+)\"[^>]*>\\s*<failure[^>]*message=\"([^\"]*)\">([^<]*)</failure>",
            Pattern.DOTALL);

    // --- Maven Surefire stdout patterns ---
    private static final Pattern SUREFIRE_SUMMARY = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    // --- pytest stdout patterns ---
    private static final Pattern PYTEST_SUMMARY = Pattern.compile(
            "(\\d+) passed(?:.*?(\\d+) failed)?(?:.*?(\\d+) error)?(?:.*?(\\d+) skipped)?");
    private static final Pattern PYTEST_FAILURE = Pattern.compile(
            "FAILED (\\S+)::(\\S+)\\s*-\\s*(.+)");

    // --- Jest stdout patterns ---
    private static final Pattern JEST_SUMMARY = Pattern.compile(
            "Tests:\\s+(?:(\\d+) failed,\\s+)?(\\d+) passed(?:,\\s+(\\d+) total)?");
    private static final Pattern JEST_FAILURE = Pattern.compile(
            "FAIL\\s+(\\S+)\\n\\s*.*?\\u25CF\\s+(.+?)\\n\\s*(.+?)(?=\\n\\n)", Pattern.DOTALL);

    private TestResultParser() {}

    /**
     * Parses test results from stdout/stderr output.
     *
     * @param stdout captured stdout
     * @param stderr captured stderr
     * @return parsed test results, or null if no test output detected
     */
    @Nullable
    public static TestResults parseFromOutput(String stdout, String stderr) {
        String combined = (stdout != null ? stdout : "") + "\n" + (stderr != null ? stderr : "");

        // Try JUnit/Surefire format first
        TestResults surefire = parseSurefire(combined);
        if (surefire != null) return surefire;

        // Try pytest format
        TestResults pytest = parsePytest(combined);
        if (pytest != null) return pytest;

        // Try Jest format
        return parseJest(combined);
    }

    /**
     * Parses test results from a JUnit XML file.
     *
     * @param xmlPath path to JUnit XML report
     * @return parsed test results, or null if file unreadable
     */
    @Nullable
    public static TestResults parseJunitXml(Path xmlPath) {
        try {
            String content = Files.readString(xmlPath);
            return parseJunitXmlContent(content);
        } catch (IOException e) {
            log.debug("Cannot read JUnit XML: {}", xmlPath);
            return null;
        }
    }

    // --- Format-specific parsers ---

    @Nullable
    static TestResults parseSurefire(String output) {
        Matcher m = SUREFIRE_SUMMARY.matcher(output);

        int total = 0, failures = 0, errors = 0, skipped = 0;
        boolean found = false;

        while (m.find()) {
            total += Integer.parseInt(m.group(1));
            failures += Integer.parseInt(m.group(2));
            errors += Integer.parseInt(m.group(3));
            skipped += Integer.parseInt(m.group(4));
            found = true;
        }

        if (!found) return null;

        int passed = total - failures - errors - skipped;
        return new TestResults(total, passed, failures, errors, skipped, -1.0, List.of());
    }

    @Nullable
    static TestResults parsePytest(String output) {
        Matcher m = PYTEST_SUMMARY.matcher(output);
        if (!m.find()) return null;

        int passed = Integer.parseInt(m.group(1));
        int failed = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        int errored = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        int skipped = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
        int total = passed + failed + errored + skipped;

        List<TestFailure> testFailures = new ArrayList<>();
        Matcher fm = PYTEST_FAILURE.matcher(output);
        while (fm.find()) {
            testFailures.add(new TestFailure(fm.group(1), fm.group(2), fm.group(3), null));
        }

        return new TestResults(total, passed, failed, errored, skipped, -1.0, testFailures);
    }

    @Nullable
    static TestResults parseJest(String output) {
        Matcher m = JEST_SUMMARY.matcher(output);
        if (!m.find()) return null;

        int failed = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
        int passed = Integer.parseInt(m.group(2));
        int total = m.group(3) != null ? Integer.parseInt(m.group(3)) : (passed + failed);
        int skipped = total - passed - failed;

        List<TestFailure> testFailures = new ArrayList<>();
        Matcher fm = JEST_FAILURE.matcher(output);
        while (fm.find()) {
            testFailures.add(new TestFailure(fm.group(1), fm.group(2), fm.group(3), null));
        }

        return new TestResults(total, passed, failed, 0, skipped, -1.0, testFailures);
    }

    @Nullable
    static TestResults parseJunitXmlContent(String xml) {
        Matcher m = JUNIT_TESTSUITE.matcher(xml);
        if (!m.find()) return null;

        int total = Integer.parseInt(m.group(1));
        int failures = Integer.parseInt(m.group(2));
        int errors = Integer.parseInt(m.group(3));
        int skipped = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
        int passed = total - failures - errors - skipped;

        List<TestFailure> testFailures = new ArrayList<>();
        Matcher fm = JUNIT_TESTCASE_FAILURE.matcher(xml);
        while (fm.find()) {
            testFailures.add(new TestFailure(fm.group(1), fm.group(2), fm.group(3), fm.group(4)));
        }

        return new TestResults(total, passed, failures, errors, skipped, -1.0, testFailures);
    }
}
