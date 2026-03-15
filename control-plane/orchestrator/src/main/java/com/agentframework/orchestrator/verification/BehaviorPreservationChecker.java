package com.agentframework.orchestrator.verification;

import com.agentframework.orchestrator.runtime.ExecutionResult;
import com.agentframework.orchestrator.runtime.ExecutionResult.TestResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks behavior preservation for refactoring tasks.
 *
 * <p>Compares pre-refactoring and post-refactoring test results to detect regressions.
 * If post-refactoring introduces new failures that didn't exist before, the refactoring
 * is considered behavior-breaking and should be rolled back.</p>
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>New test failures → regression (rollback recommended)</li>
 *   <li>Fewer passing tests → regression (even if no new failures)</li>
 *   <li>Same or more passing tests, no new failures → behavior preserved</li>
 *   <li>Pre-refactoring had failures that still fail → acceptable (pre-existing)</li>
 * </ul>
 */
public class BehaviorPreservationChecker {

    private static final Logger log = LoggerFactory.getLogger(BehaviorPreservationChecker.class);

    private BehaviorPreservationChecker() {}

    /**
     * Compares pre and post refactoring test results.
     *
     * @param preResult  test results before refactoring
     * @param postResult test results after refactoring
     * @return preservation check result
     */
    public static PreservationResult check(ExecutionResult preResult, ExecutionResult postResult) {
        if (preResult == null || postResult == null) {
            return new PreservationResult(true, "no test results to compare", 0, 0);
        }

        TestResults pre = preResult.testResults();
        TestResults post = postResult.testResults();

        if (pre == null || post == null) {
            return new PreservationResult(true, "test results unavailable for comparison", 0, 0);
        }

        int newFailures = Math.max(0, (post.failed() + post.errors()) - (pre.failed() + pre.errors()));
        int passedDelta = post.passed() - pre.passed();

        // New test failures introduced
        if (newFailures > 0) {
            String reason = String.format("refactoring introduced %d new test failures (pre: %d/%d passed, post: %d/%d passed)",
                    newFailures, pre.passed(), pre.total(), post.passed(), post.total());
            log.warn("Behavior NOT preserved: {}", reason);
            return new PreservationResult(false, reason, newFailures, passedDelta);
        }

        // Fewer passing tests (might indicate deleted or skipped tests)
        if (passedDelta < 0) {
            String reason = String.format("refactoring reduced passing tests by %d (pre: %d, post: %d)",
                    -passedDelta, pre.passed(), post.passed());
            log.warn("Behavior questionable: {}", reason);
            return new PreservationResult(false, reason, 0, passedDelta);
        }

        String reason = String.format("behavior preserved (pre: %d/%d passed, post: %d/%d passed)",
                pre.passed(), pre.total(), post.passed(), post.total());
        return new PreservationResult(true, reason, 0, passedDelta);
    }

    /**
     * Behavior preservation check result.
     *
     * @param preserved    true if behavior is preserved
     * @param reason       explanation
     * @param newFailures  count of new test failures
     * @param passedDelta  change in passing test count (positive = improvement)
     */
    public record PreservationResult(
            boolean preserved,
            String reason,
            int newFailures,
            int passedDelta
    ) {}
}
