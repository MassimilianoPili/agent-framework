package com.agentframework.orchestrator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "quality_gate_reports")
public class QualityGateReport {

    @Id
    private UUID id;

    @OneToOne
    @JoinColumn(name = "plan_id", nullable = false, unique = true)
    private Plan plan;

    @Column(nullable = false)
    private boolean passed;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @ElementCollection
    @CollectionTable(name = "quality_gate_findings",
                     joinColumns = @JoinColumn(name = "report_id"))
    @Column(name = "finding", columnDefinition = "TEXT")
    private List<String> findings = new ArrayList<>();

    @Column(nullable = false)
    private Instant generatedAt;

    protected QualityGateReport() {}

    public QualityGateReport(UUID id, Plan plan, boolean passed, String summary, List<String> findings) {
        this.id = id;
        this.plan = plan;
        this.passed = passed;
        this.summary = summary;
        this.findings = findings != null ? new ArrayList<>(findings) : new ArrayList<>();
        this.generatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Plan getPlan() { return plan; }
    public boolean isPassed() { return passed; }
    public String getSummary() { return summary; }
    public List<String> getFindings() { return findings; }
    public Instant getGeneratedAt() { return generatedAt; }

    /** Updates this report with new quality gate results (ralph-loop upsert). */
    public void update(boolean passed, String summary, List<String> findings) {
        this.passed = passed;
        this.summary = summary;
        this.findings = findings != null ? new ArrayList<>(findings) : new ArrayList<>();
        this.generatedAt = Instant.now();
    }
}
