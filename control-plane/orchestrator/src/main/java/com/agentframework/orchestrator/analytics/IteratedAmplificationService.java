package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implements iterated amplification for scalable oversight of AI workers.
 *
 * <p>Inspired by Christiano et al. (2018) and the "Trust or Escalate" pattern
 * (ICLR 2025), this service decides the appropriate feedback level for each task
 * using a confidence-gated cascade:</p>
 *
 * <ol>
 *   <li><b>AUTO</b> — low complexity, low uncertainty: accept worker output as-is</li>
 *   <li><b>MODEL</b> — medium complexity: use a separate model to review output</li>
 *   <li><b>HUMAN_SPOT_CHECK</b> — probabilistic sampling of AUTO/MODEL decisions</li>
 *   <li><b>HUMAN_FULL</b> — high complexity or high uncertainty: full human review</li>
 * </ol>
 *
 * <p>The majority of tasks resolve at AUTO level (~70-80%), keeping oversight costs low
 * while maintaining quality through selective escalation and spot-checking.</p>
 *
 * @see <a href="https://arxiv.org/abs/1810.08575">
 *     Christiano et al. (2018) — Supervising strong learners by amplifying weak experts</a>
 * @see <a href="https://arxiv.org/abs/2312.09390">
 *     Burns et al. (2023) — Weak-to-Strong Generalization</a>
 */
@Service
@ConditionalOnProperty(prefix = "iterated-amplification", name = "enabled", havingValue = "true", matchIfMissing = false)
public class IteratedAmplificationService {

    private static final Logger log = LoggerFactory.getLogger(IteratedAmplificationService.class);

    @Value("${iterated-amplification.auto-threshold:0.3}")
    private double autoThreshold;

    @Value("${iterated-amplification.model-threshold:0.7}")
    private double modelThreshold;

    @Value("${iterated-amplification.spot-check-ratio:0.1}")
    private double spotCheckRatio;

    /** Per-level accuracy tracking: level → (accurate count, total count). */
    private final ConcurrentHashMap<FeedbackLevel, long[]> accuracyTracking = new ConcurrentHashMap<>();

    /** Per-level request counts. */
    private final ConcurrentHashMap<FeedbackLevel, AtomicLong> levelCounts = new ConcurrentHashMap<>();

    /** Spot-check counter for deterministic spot-check scheduling. */
    private final AtomicLong autoDecisionCounter = new AtomicLong(0);

    /**
     * Feedback levels ordered by cost and thoroughness.
     */
    public enum FeedbackLevel {
        /** Automatic acceptance — lowest cost, suitable for simple tasks. */
        AUTO,
        /** Model-based review — medium cost, for tasks of moderate complexity. */
        MODEL,
        /** Probabilistic human spot-check of AUTO/MODEL decisions. */
        HUMAN_SPOT_CHECK,
        /** Full human review — highest cost, for complex or critical tasks. */
        HUMAN_FULL
    }

    /**
     * Decides the appropriate feedback level for a task.
     *
     * <p>Decision cascade:
     * <ul>
     *   <li>complexity &lt; autoThreshold AND uncertainty &lt; autoThreshold → AUTO</li>
     *   <li>complexity &lt; modelThreshold AND uncertainty &lt; modelThreshold → MODEL</li>
     *   <li>Otherwise → HUMAN_FULL</li>
     *   <li>Spot-check: every 1/spotCheckRatio AUTO decisions get upgraded to HUMAN_SPOT_CHECK</li>
     * </ul></p>
     *
     * @param workerType     the worker type handling this task
     * @param taskComplexity estimated task complexity [0, 1]
     * @param gpUncertainty  GP model uncertainty (sigma²) for this task
     * @return amplification decision with level, confidence, and rationale
     */
    public AmplificationDecision decideFeedbackLevel(String workerType, double taskComplexity,
                                                      double gpUncertainty) {
        FeedbackLevel level;
        double confidence;
        String rationale;

        if (taskComplexity < autoThreshold && gpUncertainty < autoThreshold) {
            // Check spot-check schedule
            long counter = autoDecisionCounter.incrementAndGet();
            int spotCheckInterval = spotCheckRatio > 0 ? (int) Math.round(1.0 / spotCheckRatio) : 0;

            if (spotCheckInterval > 0 && counter % spotCheckInterval == 0) {
                level = FeedbackLevel.HUMAN_SPOT_CHECK;
                confidence = 1.0 - taskComplexity;
                rationale = String.format("Spot-check #%d for %s (complexity=%.2f, uncertainty=%.2f)",
                        counter / spotCheckInterval, workerType, taskComplexity, gpUncertainty);
            } else {
                level = FeedbackLevel.AUTO;
                confidence = 1.0 - taskComplexity;
                rationale = String.format("Auto-accept for %s: low complexity (%.2f) and uncertainty (%.2f)",
                        workerType, taskComplexity, gpUncertainty);
            }
        } else if (taskComplexity < modelThreshold && gpUncertainty < modelThreshold) {
            level = FeedbackLevel.MODEL;
            confidence = 1.0 - (taskComplexity + gpUncertainty) / 2.0;
            rationale = String.format("Model review for %s: medium complexity (%.2f) or uncertainty (%.2f)",
                    workerType, taskComplexity, gpUncertainty);
        } else {
            level = FeedbackLevel.HUMAN_FULL;
            confidence = Math.max(0.0, 1.0 - Math.max(taskComplexity, gpUncertainty));
            rationale = String.format("Human review for %s: high complexity (%.2f) or uncertainty (%.2f)",
                    workerType, taskComplexity, gpUncertainty);
        }

        levelCounts.computeIfAbsent(level, k -> new AtomicLong(0)).incrementAndGet();

        log.debug("Amplification decision: {} → {} (confidence={})", workerType, level, confidence);

        return new AmplificationDecision(level, confidence, rationale);
    }

    /**
     * Records the accuracy outcome of an oversight decision.
     *
     * @param taskKey  the task identifier
     * @param level    the feedback level that was used
     * @param accurate whether the oversight decision was correct
     */
    public void recordOversightOutcome(String taskKey, FeedbackLevel level, boolean accurate) {
        accuracyTracking.compute(level, (k, counts) -> {
            if (counts == null) counts = new long[]{0, 0};
            if (accurate) counts[0]++;
            counts[1]++;
            return counts;
        });

        log.debug("Oversight outcome for {}: level={}, accurate={}", taskKey, level, accurate);
    }

    /**
     * Returns oversight statistics aggregated across all levels.
     *
     * @return stats including distribution, accuracy, and cost metrics per level
     */
    public OversightStats getOversightStats() {
        Map<FeedbackLevel, Long> distribution = new LinkedHashMap<>();
        Map<FeedbackLevel, Double> accuracy = new LinkedHashMap<>();
        long totalRequests = 0;

        for (FeedbackLevel level : FeedbackLevel.values()) {
            long count = levelCounts.containsKey(level)
                    ? levelCounts.get(level).get() : 0;
            distribution.put(level, count);
            totalRequests += count;

            long[] counts = accuracyTracking.get(level);
            if (counts != null && counts[1] > 0) {
                accuracy.put(level, (double) counts[0] / counts[1]);
            } else {
                accuracy.put(level, Double.NaN);
            }
        }

        // Cost model: AUTO=1, MODEL=5, SPOT_CHECK=10, HUMAN_FULL=20
        double totalCost = distribution.getOrDefault(FeedbackLevel.AUTO, 0L) * 1.0
                + distribution.getOrDefault(FeedbackLevel.MODEL, 0L) * 5.0
                + distribution.getOrDefault(FeedbackLevel.HUMAN_SPOT_CHECK, 0L) * 10.0
                + distribution.getOrDefault(FeedbackLevel.HUMAN_FULL, 0L) * 20.0;
        double avgCost = totalRequests > 0 ? totalCost / totalRequests : 0.0;

        return new OversightStats(totalRequests, distribution, accuracy, avgCost);
    }

    /**
     * Amplification decision for a specific task.
     *
     * @param level      the feedback level selected
     * @param confidence confidence in the decision [0, 1]
     * @param rationale  human-readable explanation
     */
    public record AmplificationDecision(
            FeedbackLevel level,
            double confidence,
            String rationale
    ) {}

    /**
     * Aggregated oversight statistics.
     *
     * @param totalRequests  total number of amplification decisions made
     * @param distribution   count of decisions per feedback level
     * @param accuracy       accuracy rate per feedback level (NaN if no data)
     * @param avgCostPerTask average cost per task (weighted by level cost)
     */
    public record OversightStats(
            long totalRequests,
            Map<FeedbackLevel, Long> distribution,
            Map<FeedbackLevel, Double> accuracy,
            double avgCostPerTask
    ) {}
}
