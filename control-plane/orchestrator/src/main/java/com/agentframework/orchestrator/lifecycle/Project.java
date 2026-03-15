package com.agentframework.orchestrator.lifecycle;

import jakarta.persistence.*;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Multi-plan project entity.
 *
 * <p>A Project is a first-class aggregate that owns an ordered collection
 * of plans via the {@code project_plans} join table. Plans that are not part
 * of a project simply have no row in the join table — no modification to
 * {@code Plan.java} is needed.</p>
 *
 * <p>State machine enforced via {@link ProjectStatus#canTransitionTo}.</p>
 *
 * @see ProjectStatus
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    private UUID id;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(columnDefinition = "TEXT")
    @Nullable
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.PLANNING;

    @Column(name = "epic_specs", columnDefinition = "JSONB")
    private String epicSpecs = "[]";

    @Column(name = "release_notes", columnDefinition = "TEXT")
    @Nullable
    private String releaseNotes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    @Nullable
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Project() {}

    public Project(UUID id, String name, @Nullable String description) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Transitions to a new status, enforcing the state machine.
     *
     * @param target the target status
     * @throws IllegalStateException if transition is not allowed
     */
    public void transitionTo(ProjectStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Cannot transition project from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();

        if (target == ProjectStatus.RELEASED || target == ProjectStatus.CANCELLED) {
            this.completedAt = Instant.now();
        }
    }

    // --- Accessors ---

    public UUID getId() { return id; }
    public String getName() { return name; }
    @Nullable public String getDescription() { return description; }
    public ProjectStatus getStatus() { return status; }
    public String getEpicSpecs() { return epicSpecs; }
    @Nullable public String getReleaseNotes() { return releaseNotes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    @Nullable public Instant getCompletedAt() { return completedAt; }
    public long getVersion() { return version; }

    public void setEpicSpecs(String epicSpecs) {
        this.epicSpecs = epicSpecs;
        this.updatedAt = Instant.now();
    }

    public void setReleaseNotes(String releaseNotes) {
        this.releaseNotes = releaseNotes;
        this.updatedAt = Instant.now();
    }

    public void setDescription(String description) {
        this.description = description;
        this.updatedAt = Instant.now();
    }
}
