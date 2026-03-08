package com.agentframework.orchestrator.audit;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for persisted audit events received from workers.
 *
 * <p>Workers call {@code POST /audit/events} asynchronously (fire-and-forget) via
 * {@code audit-log.sh}. Events are stored here permanently — no eviction, no cap.
 * Cleanup is handled by a scheduled job ({@link com.agentframework.orchestrator.hooks.AuditManagerService#cleanupOldEvents()})
 * that deletes events older than 30 days.</p>
 *
 * <p>Design: like PlanEvent, fields are immutable after construction (no setters).
 * The {@code raw} column holds the full original JSON payload as JSONB for ad-hoc queries.</p>
 */
@Entity
@Table(name = "audit_events",
       indexes = {
           @Index(name = "idx_audit_task_key", columnList = "task_key"),
           @Index(name = "idx_audit_occurred_at", columnList = "occurred_at")
       })
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_key")
    private String taskKey;

    @Column(name = "tool")
    private String tool;

    @Column(name = "worker")
    private String worker;

    @Column(name = "session")
    private String session;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Full JSON payload from the worker — stored as JSONB for ad-hoc filtering. */
    @Column(name = "raw", columnDefinition = "jsonb")
    private String raw;

    /** Required by JPA. */
    protected AuditEvent() {}

    public AuditEvent(String taskKey, String tool, String worker, String session,
                      Instant occurredAt, String raw) {
        this.taskKey = taskKey;
        this.tool = tool;
        this.worker = worker;
        this.session = session;
        this.occurredAt = occurredAt;
        this.raw = raw;
    }

    public Long getId()            { return id; }
    public String getTaskKey()     { return taskKey; }
    public String getTool()        { return tool; }
    public String getWorker()      { return worker; }
    public String getSession()     { return session; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getRaw()         { return raw; }
}
