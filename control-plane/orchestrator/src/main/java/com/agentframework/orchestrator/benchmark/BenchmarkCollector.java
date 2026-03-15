package com.agentframework.orchestrator.benchmark;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled collector for longitudinal effectiveness snapshots.
 *
 * <p>At each interval, queries {@code task_outcomes}, {@code plan_items},
 * and {@code worker_elo_stats} to compute aggregate metrics for the most
 * recent time bucket. Each metric produces one {@link EffectivenessSnapshot}.</p>
 *
 * <p>Metrics collected:</p>
 * <ul>
 *   <li><b>REWARD_MEAN</b>: aggregated_reward stats from plan_items</li>
 *   <li><b>COMPLETION_RATE</b>: ratio of DONE items over total dispatched</li>
 *   <li><b>DURATION_P95</b>: task duration percentiles (dispatched→completed)</li>
 *   <li><b>GP_ACCURACY</b>: |actual_reward − gp_mu| / max(actual_reward, 0.01)</li>
 *   <li><b>ELO_PROGRESSION</b>: delta of top-5 worker ELO ratings</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "benchmark", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(BenchmarkConfig.class)
public class BenchmarkCollector {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkCollector.class);

    private final BenchmarkConfig config;
    private final BenchmarkRepository repository;
    private final JdbcTemplate jdbc;
    private final Counter snapshotsCollected;

    public BenchmarkCollector(BenchmarkConfig config,
                              BenchmarkRepository repository,
                              JdbcTemplate jdbc,
                              @Nullable MeterRegistry meterRegistry) {
        this.config = config;
        this.repository = repository;
        this.jdbc = jdbc;

        this.snapshotsCollected = meterRegistry != null
                ? Counter.builder("benchmark_snapshots_collected_total")
                    .description("Total effectiveness snapshots collected")
                    .register(meterRegistry)
                : null;
    }

    /**
     * Collects effectiveness snapshots at the configured interval.
     */
    @Scheduled(fixedDelayString = "${benchmark.snapshot.interval-minutes:60}",
               timeUnit = java.util.concurrent.TimeUnit.MINUTES,
               initialDelayString = "${benchmark.snapshot.interval-minutes:60}")
    public void collectSnapshots() {
        Instant now = Instant.now();
        int intervalMinutes = config.snapshot().intervalMinutes();
        Instant bucketEnd = now.truncatedTo(ChronoUnit.MINUTES);
        Instant bucketStart = bucketEnd.minus(Duration.ofMinutes(intervalMinutes));

        log.debug("Collecting benchmark snapshots for bucket [{}, {})", bucketStart, bucketEnd);

        int collected = 0;
        collected += collectRewardMean(bucketStart, bucketEnd);
        collected += collectCompletionRate(bucketStart, bucketEnd);
        collected += collectDurationP95(bucketStart, bucketEnd);
        collected += collectGpAccuracy(bucketStart, bucketEnd);
        collected += collectEloProgression(bucketStart, bucketEnd);

        // Retention cleanup
        repository.deleteOlderThan(config.snapshot().retentionDays());

        if (snapshotsCollected != null) snapshotsCollected.increment(collected);
        log.info("Benchmark collection complete: {} snapshots for bucket [{}, {})",
                collected, bucketStart, bucketEnd);
    }

    /**
     * Manual snapshot trigger (for testing and on-demand collection).
     */
    public int triggerManualSnapshot() {
        collectSnapshots();
        return 5; // max metrics collected
    }

    // --- Metric collectors ---

    private int collectRewardMean(Instant start, Instant end) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT COUNT(*) AS cnt,
                       AVG(aggregated_reward) AS mean,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY aggregated_reward) AS p50,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY aggregated_reward) AS p95,
                       STDDEV(aggregated_reward) AS stddev
                FROM plan_items
                WHERE completed_at >= ? AND completed_at < ?
                  AND aggregated_reward IS NOT NULL
                """, Timestamp.from(start), Timestamp.from(end));

            if (rows.isEmpty() || toInt(rows.getFirst().get("cnt")) == 0) return 0;
            Map<String, Object> r = rows.getFirst();

            repository.saveSnapshot(new EffectivenessSnapshot(
                    UUID.randomUUID(), start, end,
                    EffectivenessSnapshot.REWARD_MEAN,
                    toInt(r.get("cnt")),
                    toDouble(r.get("mean")),
                    toDouble(r.get("p50")),
                    toDouble(r.get("p95")),
                    toDouble(r.get("stddev")),
                    null, Instant.now()));
            return 1;
        } catch (Exception e) {
            log.debug("Failed to collect REWARD_MEAN: {}", e.getMessage());
            return 0;
        }
    }

    private int collectCompletionRate(Instant start, Instant end) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'DONE') AS done
                FROM plan_items
                WHERE dispatched_at >= ? AND dispatched_at < ?
                """, Timestamp.from(start), Timestamp.from(end));

            if (rows.isEmpty()) return 0;
            Map<String, Object> r = rows.getFirst();
            int total = toInt(r.get("total"));
            if (total == 0) return 0;

            int done = toInt(r.get("done"));
            double rate = (double) done / total;

            repository.saveSnapshot(new EffectivenessSnapshot(
                    UUID.randomUUID(), start, end,
                    EffectivenessSnapshot.COMPLETION_RATE,
                    total, rate, null, null, null,
                    "{\"done\":" + done + ",\"total\":" + total + "}",
                    Instant.now()));
            return 1;
        } catch (Exception e) {
            log.debug("Failed to collect COMPLETION_RATE: {}", e.getMessage());
            return 0;
        }
    }

    private int collectDurationP95(Instant start, Instant end) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT COUNT(*) AS cnt,
                       AVG(EXTRACT(EPOCH FROM (completed_at - dispatched_at))) AS mean_sec,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - dispatched_at))) AS p50_sec,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - dispatched_at))) AS p95_sec,
                       STDDEV(EXTRACT(EPOCH FROM (completed_at - dispatched_at))) AS stddev_sec
                FROM plan_items
                WHERE completed_at >= ? AND completed_at < ?
                  AND dispatched_at IS NOT NULL AND completed_at IS NOT NULL
                """, Timestamp.from(start), Timestamp.from(end));

            if (rows.isEmpty() || toInt(rows.getFirst().get("cnt")) == 0) return 0;
            Map<String, Object> r = rows.getFirst();

            repository.saveSnapshot(new EffectivenessSnapshot(
                    UUID.randomUUID(), start, end,
                    EffectivenessSnapshot.DURATION_P95,
                    toInt(r.get("cnt")),
                    toDouble(r.get("mean_sec")),
                    toDouble(r.get("p50_sec")),
                    toDouble(r.get("p95_sec")),
                    toDouble(r.get("stddev_sec")),
                    null, Instant.now()));
            return 1;
        } catch (Exception e) {
            log.debug("Failed to collect DURATION_P95: {}", e.getMessage());
            return 0;
        }
    }

    private int collectGpAccuracy(Instant start, Instant end) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT COUNT(*) AS cnt,
                       AVG(ABS(actual_reward - gp_mu) / GREATEST(ABS(actual_reward), 0.01)) AS mean,
                       PERCENTILE_CONT(0.5) WITHIN GROUP (
                           ORDER BY ABS(actual_reward - gp_mu) / GREATEST(ABS(actual_reward), 0.01)
                       ) AS p50,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (
                           ORDER BY ABS(actual_reward - gp_mu) / GREATEST(ABS(actual_reward), 0.01)
                       ) AS p95
                FROM task_outcomes
                WHERE created_at >= ? AND created_at < ?
                  AND actual_reward IS NOT NULL AND gp_mu IS NOT NULL
                """, Timestamp.from(start), Timestamp.from(end));

            if (rows.isEmpty() || toInt(rows.getFirst().get("cnt")) == 0) return 0;
            Map<String, Object> r = rows.getFirst();

            repository.saveSnapshot(new EffectivenessSnapshot(
                    UUID.randomUUID(), start, end,
                    EffectivenessSnapshot.GP_ACCURACY,
                    toInt(r.get("cnt")),
                    toDouble(r.get("mean")),
                    toDouble(r.get("p50")),
                    toDouble(r.get("p95")),
                    null, null, Instant.now()));
            return 1;
        } catch (Exception e) {
            log.debug("Failed to collect GP_ACCURACY: {}", e.getMessage());
            return 0;
        }
    }

    private int collectEloProgression(Instant start, Instant end) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT worker_profile,
                       elo_rating,
                       cumulative_reward,
                       match_count
                FROM worker_elo_stats
                ORDER BY elo_rating DESC
                LIMIT 5
                """);

            if (rows.isEmpty()) return 0;

            double avgElo = rows.stream()
                    .mapToDouble(r -> ((Number) r.get("elo_rating")).doubleValue())
                    .average().orElse(0);

            double maxElo = rows.stream()
                    .mapToDouble(r -> ((Number) r.get("elo_rating")).doubleValue())
                    .max().orElse(0);

            double minElo = rows.stream()
                    .mapToDouble(r -> ((Number) r.get("elo_rating")).doubleValue())
                    .min().orElse(0);

            StringBuilder detail = new StringBuilder("[");
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) detail.append(",");
                Map<String, Object> r = rows.get(i);
                detail.append("{\"profile\":\"").append(r.get("worker_profile"))
                      .append("\",\"elo\":").append(r.get("elo_rating"))
                      .append(",\"matches\":").append(r.get("match_count")).append("}");
            }
            detail.append("]");

            repository.saveSnapshot(new EffectivenessSnapshot(
                    UUID.randomUUID(), start, end,
                    EffectivenessSnapshot.ELO_PROGRESSION,
                    rows.size(), avgElo, null, null,
                    maxElo - minElo, // stddev field reused as ELO spread
                    detail.toString(), Instant.now()));
            return 1;
        } catch (Exception e) {
            log.debug("Failed to collect ELO_PROGRESSION: {}", e.getMessage());
            return 0;
        }
    }

    // --- Helpers ---

    private static int toInt(Object o) {
        return o != null ? ((Number) o).intValue() : 0;
    }

    private static Double toDouble(Object o) {
        return o != null ? ((Number) o).doubleValue() : null;
    }
}
