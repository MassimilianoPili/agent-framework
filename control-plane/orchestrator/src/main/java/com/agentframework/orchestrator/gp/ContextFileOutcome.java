package com.agentframework.orchestrator.gp;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Associates a file path suggested by the Context Manager with a task outcome
 * that had high GP residual (|actual_reward - gp_mu|).
 *
 * <p>Used for serendipity ranking: when a new task arrives, we find similar
 * past task outcomes (via pgvector cosine similarity on task_outcomes.task_embedding)
 * and retrieve the files associated with those outcomes. Files weighted by
 * similarity × residual surface historically-surprising context.</p>
 */
@Entity
@Table(name = "context_file_outcomes")
public class ContextFileOutcome {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "task_outcome_id", nullable = false)
    private UUID taskOutcomeId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "domain_task_key", nullable = false, length = 20)
    private String domainTaskKey;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(nullable = false)
    private float residual;

    @Column(name = "created_at")
    private Instant createdAt;

    protected ContextFileOutcome() {} // JPA

    public ContextFileOutcome(UUID id, UUID taskOutcomeId, UUID planId,
                               String domainTaskKey, String filePath, float residual) {
        this.id = id;
        this.taskOutcomeId = taskOutcomeId;
        this.planId = planId;
        this.domainTaskKey = domainTaskKey;
        this.filePath = filePath;
        this.residual = residual;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTaskOutcomeId() { return taskOutcomeId; }
    public UUID getPlanId() { return planId; }
    public String getDomainTaskKey() { return domainTaskKey; }
    public String getFilePath() { return filePath; }
    public float getResidual() { return residual; }
    public Instant getCreatedAt() { return createdAt; }
}
