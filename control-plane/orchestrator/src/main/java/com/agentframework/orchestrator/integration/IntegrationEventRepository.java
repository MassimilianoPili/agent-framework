package com.agentframework.orchestrator.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repository for integration events stored in the {@code integration_events} table.
 *
 * <p>Supports querying events by plan, system type, direction, and status.
 * Uses JdbcTemplate directly to avoid JPA entity overhead for this
 * high-throughput event log table.</p>
 */
@Repository
@ConditionalOnProperty(prefix = "integration-hub", name = "enabled", havingValue = "true", matchIfMissing = false)
public class IntegrationEventRepository {

    private final JdbcTemplate jdbc;

    public IntegrationEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists an outbound integration event.
     */
    public void saveOutbound(UUID id, String systemType, String eventType,
                              String payload, @Nullable UUID planId) {
        jdbc.update("""
            INSERT INTO integration_events (id, direction, system_type, event_type,
                payload, plan_id, status, created_at)
            VALUES (?::uuid, 'OUTBOUND', ?, ?, ?::jsonb, ?::uuid, 'PENDING', NOW())
            """, id.toString(), systemType, eventType, payload,
                planId != null ? planId.toString() : null);
    }

    /**
     * Updates event status after delivery attempt.
     */
    public void updateStatus(UUID eventId, String status) {
        jdbc.update("UPDATE integration_events SET status = ?, processed_at = NOW() WHERE id = ?::uuid",
                status, eventId.toString());
    }

    /**
     * Updates retry count after failed delivery.
     */
    public void incrementRetry(UUID eventId) {
        jdbc.update("UPDATE integration_events SET retry_count = retry_count + 1 WHERE id = ?::uuid",
                eventId.toString());
    }

    /**
     * Finds pending outbound events for retry.
     *
     * @param maxRetries maximum retry attempts
     * @param limit      batch size
     * @return list of event maps
     */
    public List<Map<String, Object>> findPendingRetries(int maxRetries, int limit) {
        return jdbc.queryForList("""
            SELECT id, system_type, event_type, payload, plan_id, retry_count
            FROM integration_events
            WHERE direction = 'OUTBOUND' AND status = 'FAILED' AND retry_count < ?
            ORDER BY created_at ASC
            LIMIT ?
            """, maxRetries, limit);
    }

    /**
     * Checks if an idempotency key already exists (dedup check).
     *
     * @param idempotencyKey the key to check
     * @return true if already processed
     */
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integration_events WHERE idempotency_key = ?",
                Integer.class, idempotencyKey);
        return count != null && count > 0;
    }

    /**
     * Finds events by plan ID for audit trail.
     */
    public List<Map<String, Object>> findByPlanId(UUID planId, int limit) {
        return jdbc.queryForList("""
            SELECT id, direction, system_type, event_type, status, created_at, processed_at
            FROM integration_events
            WHERE plan_id = ?::uuid
            ORDER BY created_at DESC
            LIMIT ?
            """, planId.toString(), limit);
    }

    /**
     * Cleans up old idempotency keys beyond TTL.
     *
     * @param ttlHours hours to retain
     * @return number of deleted rows
     */
    public int cleanupOldEvents(int ttlHours) {
        return jdbc.update("""
            DELETE FROM integration_events
            WHERE direction = 'INBOUND' AND created_at < NOW() - INTERVAL '1 hour' * ?
            """, ttlHours);
    }
}
