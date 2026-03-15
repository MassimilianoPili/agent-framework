package com.agentframework.orchestrator.analytics.bocpd;

import java.time.Instant;
import java.util.*;

/**
 * Multiscale changepoint aggregation for reducing false positive rate.
 *
 * <p>Runs multiple BOCPD detectors at different time scales (e.g. 1min, 5min, 15min)
 * and uses majority voting to confirm a changepoint. A changepoint is confirmed
 * only when ≥ {@code quorum} detectors agree within a temporal window.</p>
 *
 * <h3>Rationale</h3>
 * <p>Single-scale BOCPD can produce false positives from short-lived transients.
 * Multiscale aggregation (inspired by wavelet-based methods, Mallat 1999) filters
 * out transient noise: a genuine regime change appears at multiple scales,
 * while a noise spike only triggers the finest scale.</p>
 *
 * <p>Each scale aggregates raw observations into windows of the specified duration
 * (in seconds), feeding the mean of each window as a single observation to its
 * BOCPD detector.</p>
 *
 * @see BocpdDetector
 */
public class BocpdMultiscaleAggregator {

    /** Result of multiscale aggregation. */
    public record MultiscaleResult(
            boolean confirmed,
            int votesFor,
            int totalScales,
            List<BocpdDetector.Changepoint> scaleChangepoints
    ) {}

    private final List<ScaleState> scales;
    private final int quorum;

    /**
     * Creates a multiscale aggregator.
     *
     * @param sliName  SLI stream name (shared across scales)
     * @param config   BOCPD configuration (provides scale durations)
     * @param quorum   minimum number of scales that must agree for confirmation
     */
    public BocpdMultiscaleAggregator(String sliName, BocpdConfig config, int quorum) {
        this.quorum = quorum;
        this.scales = new ArrayList<>();
        for (int scaleSeconds : config.scales()) {
            scales.add(new ScaleState(
                    new BocpdDetector(sliName + "@" + scaleSeconds + "s", config),
                    scaleSeconds,
                    sliName
            ));
        }
    }

    /**
     * Creates a multiscale aggregator with default quorum (majority).
     */
    public BocpdMultiscaleAggregator(String sliName, BocpdConfig config) {
        this(sliName, config, (config.scales().length / 2) + 1);
    }

    /**
     * Feeds a raw observation to all scales and checks for consensus.
     *
     * <p>Each scale accumulates observations until its window duration is reached,
     * then feeds the window mean to its BOCPD detector.</p>
     *
     * @param value     the observed value
     * @param timestamp the observation timestamp
     * @return aggregation result with vote count and confirmation status
     */
    public MultiscaleResult observe(double value, Instant timestamp) {
        List<BocpdDetector.Changepoint> changepoints = new ArrayList<>();

        for (ScaleState scale : scales) {
            Optional<BocpdDetector.Changepoint> cp = scale.addObservation(value, timestamp);
            cp.ifPresent(changepoints::add);
        }

        boolean confirmed = changepoints.size() >= quorum;
        return new MultiscaleResult(confirmed, changepoints.size(), scales.size(), changepoints);
    }

    /** Returns the number of configured scales. */
    public int scaleCount() {
        return scales.size();
    }

    // ── Per-scale state ──────────────────────────────────────────────────────

    /**
     * Manages observation windowing and BOCPD detection for a single time scale.
     *
     * <p>Accumulates raw observations over a window of {@code scaleDurationSeconds},
     * computes the mean, and feeds it to the BOCPD detector as a single observation.</p>
     */
    static class ScaleState {
        final BocpdDetector detector;
        final int scaleDurationSeconds;
        final String sliName;

        // Windowing state
        private double windowSum;
        private int windowCount;
        private Instant windowStart;

        ScaleState(BocpdDetector detector, int scaleDurationSeconds, String sliName) {
            this.detector = detector;
            this.scaleDurationSeconds = scaleDurationSeconds;
            this.sliName = sliName;
        }

        Optional<BocpdDetector.Changepoint> addObservation(double value, Instant timestamp) {
            if (windowStart == null) {
                windowStart = timestamp;
            }

            windowSum += value;
            windowCount++;

            // Check if window is complete
            long elapsed = java.time.Duration.between(windowStart, timestamp).getSeconds();
            if (elapsed >= scaleDurationSeconds && windowCount > 0) {
                double mean = windowSum / windowCount;
                Optional<BocpdDetector.Changepoint> result =
                        detector.observe(new BocpdDetector.Observation(mean, timestamp));

                // Reset window
                windowSum = 0;
                windowCount = 0;
                windowStart = timestamp;

                return result;
            }

            return Optional.empty();
        }
    }
}
