package com.agentframework.orchestrator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Structural outcome record for a completed plan.
 *
 * <p>Captures 5 structural features used as a feature vector for the
 * {@link com.agentframework.orchestrator.gp.PlanDecompositionPredictor} GP model:
 * <ol>
 *   <li>{@code nTasks}        — total number of plan items</li>
 *   <li>{@code hasContextTask} — whether a CONTEXT_MANAGER task was present</li>
 *   <li>{@code hasReviewTask}  — whether a REVIEW task was present</li>
 *   <li>{@code nBeTasks}      — number of BE worker tasks</li>
 *   <li>{@code nFeTasks}      — number of FE worker tasks</li>
 * </ol>
 * {@code actualReward} is the mean reward across all completed plan items.
 * It may be null for plans with no outcome data (e.g. failed plans without GP scores).
 */
@Entity
@Table(name = "plan_outcomes")
public class PlanOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "n_tasks", nullable = false)
    private int nTasks;

    @Column(name = "has_context_task", nullable = false)
    private boolean hasContextTask;

    @Column(name = "has_review_task", nullable = false)
    private boolean hasReviewTask;

    @Column(name = "n_be_tasks", nullable = false)
    private int nBeTasks;

    @Column(name = "n_fe_tasks", nullable = false)
    private int nFeTasks;

    @Column(name = "actual_reward")
    private Double actualReward;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PlanOutcome() {}

    public PlanOutcome(UUID planId, int nTasks, boolean hasContextTask, boolean hasReviewTask,
                       int nBeTasks, int nFeTasks, double actualReward) {
        this.planId       = planId;
        this.nTasks       = nTasks;
        this.hasContextTask = hasContextTask;
        this.hasReviewTask  = hasReviewTask;
        this.nBeTasks     = nBeTasks;
        this.nFeTasks     = nFeTasks;
        this.actualReward = actualReward;
    }

    public UUID getId()              { return id; }
    public UUID getPlanId()          { return planId; }
    public int getNTasks()           { return nTasks; }
    public boolean isHasContextTask(){ return hasContextTask; }
    public boolean isHasReviewTask() { return hasReviewTask; }
    public int getNBeTasks()         { return nBeTasks; }
    public int getNFeTasks()         { return nFeTasks; }
    public Double getActualReward()  { return actualReward; }
    public Instant getCreatedAt()    { return createdAt; }
}
