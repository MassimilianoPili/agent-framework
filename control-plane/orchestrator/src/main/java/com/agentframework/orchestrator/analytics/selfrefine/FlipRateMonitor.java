package com.agentframework.orchestrator.analytics.selfrefine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors the flip-rate of self-refine iterations to detect non-convergence.
 *
 * <p>A "flip" occurs when consecutive iterations change direction — e.g., iteration N
 * says "add error handling" but iteration N+1 says "remove error handling". This is
 * the primary failure mode of self-refine without external feedback, as documented
 * by Huang et al. (2023): the model oscillates between alternatives without converging.</p>
 *
 * <h3>Flip detection</h3>
 * <p>Each iteration records a score (e.g., test pass rate, lint score). A flip is
 * detected when the score direction reverses between consecutive iterations:</p>
 * <pre>
 *   flip = sign(score[i] - score[i-1]) ≠ sign(score[i-1] - score[i-2])
 * </pre>
 *
 * <p>When the flip rate exceeds the configured threshold, self-refine should be
 * aborted and the task escalated to external REVIEW.</p>
 *
 * @see SelfRefineGateService
 * @see <a href="https://arxiv.org/abs/2310.01798">
 *     Huang et al. (2023) — Large Language Models Cannot Self-Correct Reasoning
 *     Yet Without External Feedback</a>
 */
@Component
public class FlipRateMonitor {

    private static final Logger log = LoggerFactory.getLogger(FlipRateMonitor.class);

    /** Per-task iteration history: taskKey → list of (score, feedback) pairs. */
    private final Map<String, List<IterationRecord>> history = new ConcurrentHashMap<>();

    /** Cached flip rates for quick lookup. */
    private final Map<String, Double> cachedFlipRates = new ConcurrentHashMap<>();

    /**
     * Records a self-refine iteration for a task.
     *
     * @param taskKey  the task being refined
     * @param feedback the textual feedback from the refine step (for logging)
     * @param score    an objective quality score (e.g., test pass rate 0.0–1.0)
     */
    public void recordIteration(String taskKey, String feedback, double score) {
        history.computeIfAbsent(taskKey, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new IterationRecord(score, feedback));

        // Recompute flip rate
        double flipRate = computeFlipRate(taskKey);
        cachedFlipRates.put(taskKey, flipRate);

        List<IterationRecord> records = history.get(taskKey);
        log.debug("Self-refine iteration {}: task={} score={} flipRate={}",
                records.size(), taskKey, String.format("%.4f", score),
                String.format("%.4f", flipRate));
    }

    /**
     * Returns the current flip rate for a task.
     *
     * @param taskKey the task to check
     * @return flip rate in [0.0, 1.0] (ratio of direction changes), or 0.0 if insufficient data
     */
    public double getFlipRate(String taskKey) {
        return cachedFlipRates.getOrDefault(taskKey, 0.0);
    }

    /**
     * Returns the number of self-refine iterations recorded for a task.
     */
    public int iterationCount(String taskKey) {
        List<IterationRecord> records = history.get(taskKey);
        return records != null ? records.size() : 0;
    }

    /**
     * Checks if a task's self-refine is oscillating (flip rate above threshold).
     *
     * @param taskKey   the task to check
     * @param threshold the maximum acceptable flip rate
     * @return true if the task is oscillating and should be escalated
     */
    public boolean isOscillating(String taskKey, double threshold) {
        return getFlipRate(taskKey) > threshold;
    }

    /**
     * Returns the score trend for a task: positive if improving, negative if degrading.
     *
     * <p>Computed as the slope of a linear regression on the last N scores.</p>
     *
     * @param taskKey the task to check
     * @return trend value (positive = improving, negative = degrading, 0 = stable/no data)
     */
    public double scoreTrend(String taskKey) {
        List<IterationRecord> records = history.get(taskKey);
        if (records == null || records.size() < 2) return 0.0;

        // Simple linear regression slope: Σ(i - mean_i)(y_i - mean_y) / Σ(i - mean_i)²
        int n = records.size();
        double meanI = (n - 1) / 2.0;
        double meanY = records.stream().mapToDouble(r -> r.score).average().orElse(0);

        double num = 0, den = 0;
        for (int i = 0; i < n; i++) {
            double di = i - meanI;
            num += di * (records.get(i).score - meanY);
            den += di * di;
        }

        return den > 0 ? num / den : 0.0;
    }

    /**
     * Clears history for a completed task.
     */
    public void clear(String taskKey) {
        history.remove(taskKey);
        cachedFlipRates.remove(taskKey);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private double computeFlipRate(String taskKey) {
        List<IterationRecord> records = history.get(taskKey);
        if (records == null || records.size() < 3) {
            return 0.0; // Need at least 3 data points to detect a flip
        }

        int flips = 0;
        int comparisons = 0;

        for (int i = 2; i < records.size(); i++) {
            double delta1 = records.get(i - 1).score - records.get(i - 2).score;
            double delta2 = records.get(i).score - records.get(i - 1).score;

            // A flip occurs when the direction reverses (signs differ)
            if ((delta1 > 0 && delta2 < 0) || (delta1 < 0 && delta2 > 0)) {
                flips++;
            }
            comparisons++;
        }

        return comparisons > 0 ? (double) flips / comparisons : 0.0;
    }

    /** Internal record of a single self-refine iteration. */
    record IterationRecord(double score, String feedback) {}
}
