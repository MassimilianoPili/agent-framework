package com.agentframework.orchestrator.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JdbcTemplate-based repository for {@link EffectivenessSnapshot}.
 */
@Repository
@ConditionalOnProperty(prefix = "benchmark", name = "enabled", havingValue = "true", matchIfMissing = false)
public class BenchmarkRepository {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRepository.class);

    private final JdbcTemplate jdbc;

    public BenchmarkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists a snapshot.
     */
    public void saveSnapshot(EffectivenessSnapshot snap) {
        jdbc.update("""
            INSERT INTO effectiveness_snapshots
                (id, bucket_start, bucket_end, metric_name, sample_count,
                 mean, p50, p95, stddev, raw_detail, created_at)
            VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """,
                snap.id().toString(),
                Timestamp.from(snap.bucketStart()),
                Timestamp.from(snap.bucketEnd()),
                snap.metricName(),
                snap.sampleCount(),
                snap.mean(),
                snap.p50(),
                snap.p95(),
                snap.stddev(),
                snap.rawDetail() != null ? snap.rawDetail() : "{}",
                Timestamp.from(snap.createdAt()));
    }

    /**
     * Finds snapshots for a metric within a time range, ordered by bucket_start descending.
     */
    public List<EffectivenessSnapshot> findByMetricAndRange(String metricName, Instant from, Instant to, int limit) {
        return jdbc.queryForList("""
            SELECT * FROM effectiveness_snapshots
            WHERE metric_name = ? AND bucket_start >= ? AND bucket_start <= ?
            ORDER BY bucket_start DESC
            LIMIT ?
            """, metricName, Timestamp.from(from), Timestamp.from(to), limit)
                .stream().map(this::mapRow).toList();
    }

    /**
     * Finds the N most recent snapshots for a metric (for trend analysis).
     */
    public List<EffectivenessSnapshot> findLatestByMetric(String metricName, int limit) {
        return jdbc.queryForList("""
            SELECT * FROM effectiveness_snapshots
            WHERE metric_name = ?
            ORDER BY bucket_start DESC
            LIMIT ?
            """, metricName, limit)
                .stream().map(this::mapRow).toList();
    }

    /**
     * Deletes snapshots older than the given number of days.
     *
     * @return number of rows deleted
     */
    public int deleteOlderThan(int retentionDays) {
        int deleted = jdbc.update("""
            DELETE FROM effectiveness_snapshots
            WHERE created_at < NOW() - INTERVAL '1 day' * ?
            """, retentionDays);
        if (deleted > 0) {
            log.info("Benchmark retention cleanup: deleted {} snapshots older than {} days", deleted, retentionDays);
        }
        return deleted;
    }

    private EffectivenessSnapshot mapRow(Map<String, Object> row) {
        return new EffectivenessSnapshot(
                UUID.fromString(row.get("id").toString()),
                ((Timestamp) row.get("bucket_start")).toInstant(),
                ((Timestamp) row.get("bucket_end")).toInstant(),
                (String) row.get("metric_name"),
                ((Number) row.get("sample_count")).intValue(),
                row.get("mean") != null ? ((Number) row.get("mean")).doubleValue() : null,
                row.get("p50") != null ? ((Number) row.get("p50")).doubleValue() : null,
                row.get("p95") != null ? ((Number) row.get("p95")).doubleValue() : null,
                row.get("stddev") != null ? ((Number) row.get("stddev")).doubleValue() : null,
                row.get("raw_detail") != null ? row.get("raw_detail").toString() : "{}",
                ((Timestamp) row.get("created_at")).toInstant()
        );
    }
}
