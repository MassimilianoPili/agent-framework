package com.agentframework.orchestrator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Records a single dispatch attempt for a PlanItem (Command pattern).
 *
 * <p>Each time a task is dispatched to a worker, a new DispatchAttempt is created.
 * When the worker reports back, the attempt is completed with success/failure.
 * This provides a full audit trail and enables controlled retries.</p>
 */
@Entity
@Table(name = "dispatch_attempts")
public class DispatchAttempt {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private PlanItem item;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "dispatched_at", nullable = false)
    private Instant dispatchedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected DispatchAttempt() {}

    public DispatchAttempt(UUID id, PlanItem item, int attemptNumber) {
        this.id = id;
        this.item = item;
        this.attemptNumber = attemptNumber;
        this.dispatchedAt = Instant.now();
        this.success = false;
    }

    public void complete(boolean success, String failureReason, long durationMs) {
        this.completedAt = Instant.now();
        this.success = success;
        this.failureReason = failureReason;
        this.durationMs = durationMs;
    }

    public void failImmediately(String reason) {
        this.completedAt = Instant.now();
        this.success = false;
        this.failureReason = reason;
        this.durationMs = 0L;
    }

    public UUID getId() { return id; }
    public PlanItem getItem() { return item; }
    public int getAttemptNumber() { return attemptNumber; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public boolean isSuccess() { return success; }
    public String getFailureReason() { return failureReason; }
    public Long getDurationMs() { return durationMs; }
}
