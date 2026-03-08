package com.agentframework.orchestrator.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for {@link AuditEvent}.
 *
 * <p>Derived query names follow Spring Data conventions — no SQL needed:</p>
 * <ul>
 *   <li>{@code findByTaskKeyOrderByOccurredAtDesc} → WHERE task_key = ? ORDER BY occurred_at DESC</li>
 *   <li>{@code deleteByOccurredAtBefore} → DELETE WHERE occurred_at < ? (used by cleanup job)</li>
 * </ul>
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByTaskKeyOrderByOccurredAtDesc(String taskKey);

    /** Bulk delete for cleanup scheduler — requires its own transaction. */
    @Transactional
    void deleteByOccurredAtBefore(Instant cutoff);
}
