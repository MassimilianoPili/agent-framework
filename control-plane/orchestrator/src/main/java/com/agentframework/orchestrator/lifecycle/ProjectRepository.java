package com.agentframework.orchestrator.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Project} entities.
 */
@Repository
@ConditionalOnProperty(prefix = "lifecycle", name = "enabled", havingValue = "true", matchIfMissing = false)
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /**
     * Finds projects by status.
     */
    List<Project> findByStatusOrderByCreatedAtDesc(ProjectStatus status);

    /**
     * Finds active projects (PLANNING, ACTIVE, STABILIZING).
     */
    @Query("SELECT p FROM Project p WHERE p.status IN ('PLANNING', 'ACTIVE', 'STABILIZING') ORDER BY p.updatedAt DESC")
    List<Project> findActiveProjects();

    /**
     * Counts projects by status.
     */
    long countByStatus(ProjectStatus status);
}
