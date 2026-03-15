package com.agentframework.orchestrator.safety;

import com.agentframework.orchestrator.safety.CommandClassifier.ClassificationResult;
import com.agentframework.orchestrator.safety.CommandClassifier.RiskLevel;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Analyzes cumulative risk of git command sequences.
 *
 * <p>Individual commands can be safe or moderate, but sequences may be dangerous.
 * For example: {@code git checkout -b temp && git push --force origin main}
 * — each part looks benign, but the sequence is a force-push disguised.</p>
 *
 * <p>Inspired by STAC (Li 2025): ASR >90% on SOTA agents when sequence analysis
 * is absent. Uses a sliding window of recent commands to detect escalation patterns.</p>
 */
public class SequenceAnalyzer {

    /** Maximum commands retained in the sliding window. */
    private static final int WINDOW_SIZE = 20;

    /** Time window: commands older than this are evicted (5 minutes). */
    private static final long WINDOW_MS = 5 * 60 * 1000L;

    private final Deque<CommandEntry> history = new ArrayDeque<>();

    /**
     * Records a command and analyzes cumulative risk.
     *
     * @param command  the raw git command
     * @param classification individual command classification
     * @return sequence risk assessment
     */
    public SequenceRisk analyze(String command, ClassificationResult classification) {
        evictStale();

        history.addLast(new CommandEntry(command, classification, Instant.now()));
        while (history.size() > WINDOW_SIZE) {
            history.pollFirst();
        }

        return assessCumulativeRisk();
    }

    /**
     * Returns the current command history (for logging / audit).
     */
    public List<CommandEntry> recentHistory() {
        evictStale();
        return List.copyOf(history);
    }

    /**
     * Clears the command history (e.g., on session reset).
     */
    public void clear() {
        history.clear();
    }

    // --- Private helpers ---

    private SequenceRisk assessCumulativeRisk() {
        int dangerousCount = 0;
        int moderateCount = 0;
        boolean hasResetHard = false;
        boolean hasPushForce = false;
        boolean hasCheckoutDot = false;
        boolean hasCleanF = false;

        for (CommandEntry entry : history) {
            switch (entry.classification().level()) {
                case DANGEROUS -> dangerousCount++;
                case MODERATE -> moderateCount++;
                default -> {}
            }

            String cmd = entry.command().toLowerCase();
            if (cmd.contains("reset --hard")) hasResetHard = true;
            if (cmd.contains("push") && (cmd.contains("--force") || cmd.contains("-f"))) hasPushForce = true;
            if (cmd.contains("checkout") && cmd.contains("-- .")) hasCheckoutDot = true;
            if (cmd.contains("clean") && cmd.contains("-f")) hasCleanF = true;
        }

        // Pattern: multiple dangerous commands in short window → critical
        if (dangerousCount >= 2) {
            return new SequenceRisk(RiskLevel.DANGEROUS,
                    dangerousCount + " dangerous commands in window — possible attack sequence",
                    cumulativeScore());
        }

        // Pattern: reset --hard followed by push --force → data loss
        if (hasResetHard && hasPushForce) {
            return new SequenceRisk(RiskLevel.DANGEROUS,
                    "reset --hard + push --force sequence — irreversible remote data loss",
                    1.0);
        }

        // Pattern: checkout -- . + clean -f → complete workspace wipe
        if (hasCheckoutDot && hasCleanF) {
            return new SequenceRisk(RiskLevel.DANGEROUS,
                    "checkout -- . + clean -f sequence — complete workspace wipe",
                    0.9);
        }

        // Escalation: many moderate commands may indicate automation probing
        if (moderateCount >= 8) {
            return new SequenceRisk(RiskLevel.MODERATE,
                    "high volume of state-changing commands (" + moderateCount + ")",
                    0.5);
        }

        // Single dangerous command
        if (dangerousCount == 1) {
            return new SequenceRisk(RiskLevel.DANGEROUS,
                    "single dangerous command in window",
                    0.7);
        }

        return new SequenceRisk(RiskLevel.SAFE, "no concerning patterns", cumulativeScore());
    }

    private double cumulativeScore() {
        double score = 0.0;
        for (CommandEntry entry : history) {
            score += switch (entry.classification().level()) {
                case DANGEROUS -> 0.5;
                case MODERATE -> 0.1;
                case SAFE -> 0.0;
            };
        }
        return Math.min(score, 1.0);
    }

    private void evictStale() {
        Instant cutoff = Instant.now().minusMillis(WINDOW_MS);
        while (!history.isEmpty() && history.peekFirst().timestamp().isBefore(cutoff)) {
            history.pollFirst();
        }
    }

    /**
     * A command entry in the sliding window.
     */
    public record CommandEntry(String command, ClassificationResult classification, Instant timestamp) {}

    /**
     * Cumulative sequence risk assessment.
     *
     * @param level     effective risk level considering the full sequence
     * @param reason    human-readable explanation
     * @param riskScore cumulative score [0.0, 1.0] (higher = more risk)
     */
    public record SequenceRisk(RiskLevel level, String reason, double riskScore) {}
}
