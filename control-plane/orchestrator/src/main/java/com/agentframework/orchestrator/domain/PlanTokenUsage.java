package com.agentframework.orchestrator.domain;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Running token consumption tally for a given (plan, workerType) pair.
 *
 * Incremented atomically via a single UPDATE statement by {@link
 * com.agentframework.orchestrator.repository.PlanTokenUsageRepository#incrementTokensUsed}
 * after each completed task — no application-level locking needed.
 *
 * Row is created on first use (upsert via INSERT ... ON CONFLICT DO UPDATE in
 * the service layer, or simply via findOrCreate).
 */
@Entity
@Table(name = "plan_token_usage",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_plan_token_usage",
               columnNames = {"plan_id", "worker_type"}))
public class PlanTokenUsage {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "worker_type", nullable = false, length = 50)
    private String workerType;

    @Column(name = "tokens_used", nullable = false)
    private long tokensUsed = 0L;

    protected PlanTokenUsage() {}

    public PlanTokenUsage(UUID planId, String workerType) {
        this.id = UUID.randomUUID();
        this.planId = planId;
        this.workerType = workerType;
        this.tokensUsed = 0L;
    }

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public String getWorkerType() { return workerType; }
    public long getTokensUsed() { return tokensUsed; }
}
