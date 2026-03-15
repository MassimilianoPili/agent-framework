package com.agentframework.orchestrator.benchmark;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of one effectiveness metric for a time bucket.
 *
 * <p>Each row captures aggregate statistics (mean, p50, p95, stddev) for a
 * specific metric over a time window. The {@code rawDetail} field holds
 * metric-specific JSONB data (e.g. per-worker breakdowns).</p>
 *
 * @param id          snapshot UUID
 * @param bucketStart start of the time bucket (inclusive)
 * @param bucketEnd   end of the time bucket (exclusive)
 * @param metricName  metric identifier (REWARD_MEAN, COMPLETION_RATE, etc.)
 * @param sampleCount number of data points in this bucket
 * @param mean        arithmetic mean
 * @param p50         50th percentile (median)
 * @param p95         95th percentile
 * @param stddev      standard deviation
 * @param rawDetail   JSONB string with metric-specific detail
 * @param createdAt   when this snapshot was taken
 */
public record EffectivenessSnapshot(
        UUID id,
        Instant bucketStart,
        Instant bucketEnd,
        String metricName,
        int sampleCount,
        Double mean,
        Double p50,
        Double p95,
        Double stddev,
        String rawDetail,
        Instant createdAt
) {
    /** Known metric names. */
    public static final String REWARD_MEAN = "REWARD_MEAN";
    public static final String COMPLETION_RATE = "COMPLETION_RATE";
    public static final String DURATION_P95 = "DURATION_P95";
    public static final String GP_ACCURACY = "GP_ACCURACY";
    public static final String ELO_PROGRESSION = "ELO_PROGRESSION";
}
