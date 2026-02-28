package com.agentframework.orchestrator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Memento pattern: captures the full state of a Plan at a point in time.
 *
 * <p>The {@code planData} column stores a JSON serialization of the plan and
 * all its items (statuses, results, failure reasons). This allows restoring
 * the plan to a known-good checkpoint if something goes wrong.</p>
 */
@Entity
@Table(name = "plan_snapshots")
public class PlanSnapshot {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "plan_data", nullable = false, columnDefinition = "TEXT")
    private String planData;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlanSnapshot() {}

    public PlanSnapshot(UUID id, Plan plan, String label, String planData) {
        this.id = id;
        this.plan = plan;
        this.label = label;
        this.planData = planData;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Plan getPlan() { return plan; }
    public String getLabel() { return label; }
    public String getPlanData() { return planData; }
    public Instant getCreatedAt() { return createdAt; }
}
