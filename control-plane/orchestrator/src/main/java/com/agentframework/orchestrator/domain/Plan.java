package com.agentframework.orchestrator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "plans")
public class Plan {

    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String spec;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlanStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant completedAt;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    @Column
    private Instant pausedAt;

    /** JSON-serialized token budget (nullable — no budget enforced if null). */
    @Column(columnDefinition = "TEXT")
    private String budgetJson;

    /**
     * JSON-serialized {@code CouncilReport} produced by the pre-planning council session.
     *
     * <p>Null when the council feature is disabled ({@code council.enabled=false})
     * or when the plan was created before the council feature was introduced.</p>
     *
     * <p>Exposed via {@code GET /api/v1/plans/{id}/council-report}. Injected into
     * each dispatched {@code AgentTask} as {@code councilContext} (relevant sections only).</p>
     */
    @Column(name = "council_report", columnDefinition = "TEXT")
    private String councilReport;

    /**
     * Git commit SHA at the time the plan was created (nullable — not all plans have git context).
     * Used as part of the context cache key: same commit + same issue → identical CONTEXT_MANAGER output.
     */
    @Column(length = 64)
    private String sourceCommit;

    /**
     * SHA-256 of the working-tree diff against {@link #sourceCommit} at plan creation time.
     * If the working tree is clean, this equals the commit hash. Included in the cache key
     * to invalidate cached context when there are uncommitted changes.
     */
    @Column(length = 64)
    private String workingTreeDiffHash;

    /**
     * Nesting depth of this plan. Root plans have depth=0.
     * Sub-plans spawned via a SUB_PLAN item have depth = parent.depth + 1.
     * Used by the recursion guard to enforce the maxDepth limit.
     */
    @Column(name = "depth", nullable = false)
    private int depth = 0;

    /**
     * ID of the plan that spawned this one via a SUB_PLAN item (nullable for root plans).
     */
    @Column(name = "parent_plan_id")
    private UUID parentPlanId;

    /**
     * Base project path for the target codebase (e.g. "/data/project/backend").
     * Propagated to workers via AgentTask.dynamicOwnsPaths so path ownership
     * enforcement can resolve relative ownsPaths against the actual project location.
     */
    @Column(name = "project_path", length = 500)
    private String projectPath;

    @Version
    private Long version;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordinal ASC")
    private List<PlanItem> items = new ArrayList<>();

    protected Plan() {}

    public Plan(UUID id, String spec) {
        this.id = id;
        this.spec = spec;
        this.status = PlanStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /** Creates a child plan (depth + 1) linked to the given parent plan. */
    public Plan(UUID id, String spec, UUID parentPlanId, int parentDepth) {
        this(id, spec);
        this.parentPlanId = parentPlanId;
        this.depth = parentDepth + 1;
    }

    public UUID getId() { return id; }

    public String getSpec() { return spec; }

    public PlanStatus getStatus() { return status; }

    /**
     * Transitions to the given status, enforcing the state machine.
     * @throws IllegalStateTransitionException if the transition is not allowed
     */
    public void transitionTo(PlanStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException("Plan", id, this.status, target);
        }
        this.status = target;
    }

    /**
     * Bypasses the state machine to set an arbitrary status.
     * Reserved for snapshot restore and data migration — normal code must use {@link #transitionTo}.
     */
    public void forceStatus(PlanStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Instant getPausedAt() { return pausedAt; }
    public void setPausedAt(Instant pausedAt) { this.pausedAt = pausedAt; }

    public String getBudgetJson() { return budgetJson; }
    public void setBudgetJson(String budgetJson) { this.budgetJson = budgetJson; }

    public String getCouncilReport() { return councilReport; }
    public void setCouncilReport(String councilReport) { this.councilReport = councilReport; }

    public String getSourceCommit() { return sourceCommit; }
    public void setSourceCommit(String sourceCommit) { this.sourceCommit = sourceCommit; }

    public String getWorkingTreeDiffHash() { return workingTreeDiffHash; }
    public void setWorkingTreeDiffHash(String workingTreeDiffHash) { this.workingTreeDiffHash = workingTreeDiffHash; }

    public int getDepth() { return depth; }

    public UUID getParentPlanId() { return parentPlanId; }

    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

    public Long getVersion() { return version; }

    public List<PlanItem> getItems() { return items; }

    public void addItem(PlanItem item) {
        items.add(item);
        item.setPlan(this);
    }
}
