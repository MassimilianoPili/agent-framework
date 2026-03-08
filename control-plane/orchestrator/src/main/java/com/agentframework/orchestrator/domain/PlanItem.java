package com.agentframework.orchestrator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "plan_items",
       uniqueConstraints = @UniqueConstraint(columnNames = {"plan_id", "task_key"}))
public class PlanItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "task_key", nullable = false, length = 20)
    private String taskKey;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "worker_type", nullable = false, length = 20)
    private WorkerType workerType;

    @Column(name = "worker_profile", length = 50)
    private String workerProfile;

    @ElementCollection
    @CollectionTable(name = "plan_item_deps",
                     joinColumns = @JoinColumn(name = "item_id"))
    @Column(name = "depends_on_key")
    private List<String> dependsOn = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "plan_item_tool_hints",
                     joinColumns = @JoinColumn(name = "item_id"))
    @Column(name = "tool_hint")
    private List<String> toolHints = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItemStatus status;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column
    private Instant dispatchedAt;

    @Column
    private Instant completedAt;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    /** Number of times this item has been re-queued due to missing_context. */
    @Column(name = "context_retry_count", nullable = false)
    private int contextRetryCount = 0;

    /** Earliest time at which AutoRetryScheduler may retry this FAILED item. Null = not scheduled. */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    /**
     * JSON snapshot of the issue from the external tracker, populated by the
     * TASK_MANAGER worker via PUT /api/v1/plans/{planId}/items/{itemId}/issue-snapshot.
     * Null until TASK_MANAGER completes.
     */
    @Column(name = "issue_snapshot", columnDefinition = "TEXT")
    private String issueSnapshot;

    @Version
    private Long version;

    protected PlanItem() {}

    public PlanItem(UUID id, int ordinal, String taskKey, String title,
                    String description, WorkerType workerType, String workerProfile,
                    List<String> dependsOn, List<String> toolHints) {
        this.id = id;
        this.ordinal = ordinal;
        this.taskKey = taskKey;
        this.title = title;
        this.description = description;
        this.workerType = workerType;
        this.workerProfile = workerProfile;
        this.dependsOn = dependsOn != null ? new ArrayList<>(dependsOn) : new ArrayList<>();
        this.toolHints = toolHints != null ? new ArrayList<>(toolHints) : new ArrayList<>();
        this.status = ItemStatus.WAITING;
    }

    public UUID getId() { return id; }

    public Plan getPlan() { return plan; }
    void setPlan(Plan plan) { this.plan = plan; }

    public int getOrdinal() { return ordinal; }

    public String getTaskKey() { return taskKey; }

    public String getTitle() { return title; }

    public String getDescription() { return description; }

    public WorkerType getWorkerType() { return workerType; }

    public String getWorkerProfile() { return workerProfile; }
    public void setWorkerProfile(String workerProfile) { this.workerProfile = workerProfile; }

    public List<String> getDependsOn() { return dependsOn; }

    public List<String> getToolHints() { return toolHints; }

    public ItemStatus getStatus() { return status; }

    /**
     * Transitions to the given status, enforcing the state machine.
     * @throws IllegalStateTransitionException if the transition is not allowed
     */
    public void transitionTo(ItemStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException("PlanItem", id, this.status, target);
        }
        this.status = target;
    }

    /**
     * Bypasses the state machine to set an arbitrary status.
     * Reserved for snapshot restore and data migration — normal code must use {@link #transitionTo}.
     */
    public void forceStatus(ItemStatus status) { this.status = status; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public Instant getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(Instant dispatchedAt) { this.dispatchedAt = dispatchedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public int getContextRetryCount() { return contextRetryCount; }
    public void incrementContextRetryCount() { this.contextRetryCount++; }

    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public String getIssueSnapshot() { return issueSnapshot; }
    public void setIssueSnapshot(String issueSnapshot) { this.issueSnapshot = issueSnapshot; }

    // ── SUB_PLAN fields ────────────────────────────────────────────────────────

    /**
     * ID of the child Plan spawned when this item has workerType=SUB_PLAN.
     * Null until the sub-plan is actually created.
     */
    @Column(name = "child_plan_id")
    private UUID childPlanId;

    /**
     * If true (default), the item stays DISPATCHED until the child plan completes.
     * If false, the item transitions to DONE immediately after spawning the child plan.
     */
    @Column(name = "await_completion", nullable = false)
    private boolean awaitCompletion = true;

    /**
     * Natural-language specification for the sub-plan.
     * Used as the {@code spec} argument to {@code createAndStart()}.
     */
    @Column(name = "sub_plan_spec", columnDefinition = "TEXT")
    private String subPlanSpec;

    public UUID getChildPlanId() { return childPlanId; }
    public void setChildPlanId(UUID childPlanId) { this.childPlanId = childPlanId; }

    public boolean isAwaitCompletion() { return awaitCompletion; }
    public void setAwaitCompletion(boolean awaitCompletion) { this.awaitCompletion = awaitCompletion; }

    public String getSubPlanSpec() { return subPlanSpec; }
    public void setSubPlanSpec(String subPlanSpec) { this.subPlanSpec = subPlanSpec; }

    // ─────────────────────────────────────────────────────────────────────────

    // ── Reward signal fields ───────────────────────────────────────────────

    /**
     * Review score in [-1.0, +1.0] assigned by the REVIEW worker via per-task feedback.
     * Null until a REVIEW task completes and references this item.
     */
    @Column(name = "review_score")
    private Float reviewScore;

    /**
     * Process score in [0.0, 1.0] derived deterministically from Provenance metrics:
     * tokenEfficiency, retryPenalty, durationEfficiency. Zero additional LLM cost.
     * Null until the item transitions to DONE.
     */
    @Column(name = "process_score")
    private Float processScore;

    /**
     * Bayesian aggregate of all available reward sources. Range is [-1.0, +1.0].
     * Recomputed whenever a new source becomes available. Null until at least one source is present.
     */
    @Column(name = "aggregated_reward")
    private Float aggregatedReward;

    /**
     * JSON snapshot of the individual reward source values and their weights.
     * Example: {"review":0.8,"process":0.6,"quality_gate":null,"weights":{"review":0.50,...}}
     * Stored as TEXT for DPO export and auditability.
     */
    @Column(name = "reward_sources", columnDefinition = "TEXT")
    private String rewardSources;

    public Float getReviewScore() { return reviewScore; }
    public void setReviewScore(Float reviewScore) { this.reviewScore = reviewScore; }

    public Float getProcessScore() { return processScore; }
    public void setProcessScore(Float processScore) { this.processScore = processScore; }

    public Float getAggregatedReward() { return aggregatedReward; }
    public void setAggregatedReward(Float aggregatedReward) { this.aggregatedReward = aggregatedReward; }

    public String getRewardSources() { return rewardSources; }
    public void setRewardSources(String rewardSources) { this.rewardSources = rewardSources; }

    // ─────────────────────────────────────────────────────────────────────────

    // ── Ralph-Loop fields ──────────────────────────────────────────────────

    /**
     * Number of times this item has been re-queued by the ralph-loop (quality gate feedback).
     * Separate from contextRetryCount which tracks missing_context retries.
     */
    @Column(name = "ralph_loop_count", nullable = false)
    private int ralphLoopCount = 0;

    /**
     * Last quality gate feedback for this item, set by RalphLoopService when
     * re-queuing. Appended to the task description on re-dispatch so the worker
     * knows what to fix.
     */
    @Column(name = "last_quality_gate_feedback", columnDefinition = "TEXT")
    private String lastQualityGateFeedback;

    public int getRalphLoopCount() { return ralphLoopCount; }
    public void incrementRalphLoopCount() { this.ralphLoopCount++; }

    public String getLastQualityGateFeedback() { return lastQualityGateFeedback; }
    public void setLastQualityGateFeedback(String feedback) { this.lastQualityGateFeedback = feedback; }

    // ─────────────────────────────────────────────────────────────────────────

    // ── Bayesian Success Prediction fields ────────────────────────────────────

    /**
     * Predicted probability of success from Bayesian logistic regression.
     * Set by {@link com.agentframework.orchestrator.gp.BayesianSuccessPredictorService}
     * before dispatch admission control. Range [0.0, 1.0]. Null if not predicted.
     */
    @Column(name = "predicted_success_probability")
    private Float predictedSuccessProbability;

    public Float getPredictedSuccessProbability() { return predictedSuccessProbability; }
    public void setPredictedSuccessProbability(Float p) { this.predictedSuccessProbability = p; }

    // ─────────────────────────────────────────────────────────────────────────

    // ── Content-Addressable Storage (#48) ───────────────────────────────────

    /** SHA-256 hash linking to the artifact_store CAS table. Null for items without CAS. */
    @Column(name = "result_hash", length = 64)
    private String resultHash;

    public String getResultHash() { return resultHash; }
    public void setResultHash(String resultHash) { this.resultHash = resultHash; }

    // ─────────────────────────────────────────────────────────────────────────

    // ── Cost tracking fields (#26L1) ────────────────────────────────────────

    /** Input tokens consumed by this task. Null if the worker did not report token usage. */
    @Column(name = "input_tokens")
    private Long inputTokens;

    /** Output tokens consumed by this task. Null if the worker did not report token usage. */
    @Column(name = "output_tokens")
    private Long outputTokens;

    /** Estimated cost in USD based on model pricing. Null if tokens are unknown. */
    @Column(name = "estimated_cost_usd")
    private java.math.BigDecimal estimatedCostUsd;

    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }

    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }

    public java.math.BigDecimal getEstimatedCostUsd() { return estimatedCostUsd; }
    public void setEstimatedCostUsd(java.math.BigDecimal estimatedCostUsd) { this.estimatedCostUsd = estimatedCostUsd; }

    // ─────────────────────────────────────────────────────────────────────────

    public Long getVersion() { return version; }

    /** Dynamically adds a dependency (used by the missing_context feedback loop). */
    public void addDependency(String taskKey) {
        if (!dependsOn.contains(taskKey)) {
            dependsOn.add(taskKey);
        }
    }
}
