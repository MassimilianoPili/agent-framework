package com.agentframework.orchestrator.gp;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Training data point for the GP engine.
 *
 * <p>One row per dispatched task. The GP prediction (mu, sigma2) and embedding
 * are recorded at dispatch time; the actual reward is updated after completion.</p>
 *
 * <p>The {@code task_embedding} column uses pgvector's {@code vector(1024)} type.
 * JPA cannot map {@code float[]} to pgvector natively; the repository uses
 * native queries with explicit casts for read/write.</p>
 */
@Entity
@Table(name = "task_outcomes")
public class TaskOutcome {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "plan_item_id")
    private UUID planItemId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "task_key", nullable = false, length = 20)
    private String taskKey;

    @Column(name = "worker_type", nullable = false, length = 20)
    private String workerType;

    @Column(name = "worker_profile", length = 50)
    private String workerProfile;

    // pgvector column — handled via native queries (JPA does not map float[] to vector)
    @Transient
    private float[] taskEmbedding;

    @Column(name = "elo_at_dispatch")
    private Double eloAtDispatch;

    @Column(name = "gp_mu")
    private Double gpMu;

    @Column(name = "gp_sigma2")
    private Double gpSigma2;

    @Column(name = "actual_reward")
    private Double actualReward;

    @Column(name = "context_quality_score")
    private Double contextQualityScore;

    @Column(name = "created_at")
    private Instant createdAt;

    protected TaskOutcome() {} // JPA

    public TaskOutcome(UUID id, UUID planItemId, UUID planId, String taskKey,
                       String workerType, String workerProfile, float[] taskEmbedding,
                       Double eloAtDispatch, Double gpMu, Double gpSigma2) {
        this.id = id;
        this.planItemId = planItemId;
        this.planId = planId;
        this.taskKey = taskKey;
        this.workerType = workerType;
        this.workerProfile = workerProfile;
        this.taskEmbedding = taskEmbedding;
        this.eloAtDispatch = eloAtDispatch;
        this.gpMu = gpMu;
        this.gpSigma2 = gpSigma2;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPlanItemId() { return planItemId; }
    public UUID getPlanId() { return planId; }
    public String getTaskKey() { return taskKey; }
    public String getWorkerType() { return workerType; }
    public String getWorkerProfile() { return workerProfile; }
    public float[] getTaskEmbedding() { return taskEmbedding; }
    public Double getEloAtDispatch() { return eloAtDispatch; }
    public Double getGpMu() { return gpMu; }
    public Double getGpSigma2() { return gpSigma2; }
    public Double getActualReward() { return actualReward; }
    public Instant getCreatedAt() { return createdAt; }

    public Double getContextQualityScore() { return contextQualityScore; }

    public void setActualReward(Double actualReward) { this.actualReward = actualReward; }
    public void setContextQualityScore(Double contextQualityScore) { this.contextQualityScore = contextQualityScore; }
    public void setTaskEmbedding(float[] taskEmbedding) { this.taskEmbedding = taskEmbedding; }
}
