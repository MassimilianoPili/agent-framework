package com.agentframework.orchestrator.benchmark;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the Longitudinal Effectiveness Benchmark.
 *
 * @param enabled   feature toggle (default false)
 * @param snapshot  snapshot collection settings
 * @param trend     trend analysis settings
 */
@ConfigurationProperties(prefix = "benchmark")
public record BenchmarkConfig(
        @DefaultValue("false") boolean enabled,
        SnapshotConfig snapshot,
        TrendConfig trend
) {
    /**
     * @param intervalMinutes  snapshot collection interval (default 60 = hourly)
     * @param retentionDays    how long to keep snapshots (default 90)
     */
    public record SnapshotConfig(
            @DefaultValue("60") int intervalMinutes,
            @DefaultValue("90") int retentionDays
    ) {}

    /**
     * @param windowSize           number of snapshots for trend regression (default 30)
     * @param regressionMinPoints  minimum data points before computing trend (default 5)
     * @param slopeThreshold       below this absolute slope, trend is STABLE (default 0.001)
     */
    public record TrendConfig(
            @DefaultValue("30") int windowSize,
            @DefaultValue("5") int regressionMinPoints,
            @DefaultValue("0.001") double slopeThreshold
    ) {}
}
