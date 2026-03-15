package com.agentframework.orchestrator.lifecycle;

import com.agentframework.orchestrator.lifecycle.EpicDecomposer.Epic;
import com.agentframework.orchestrator.lifecycle.SagaPlanSequencer.SagaProgress;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Multi-Plan Project Lifecycle Manager.
 *
 * <p>Manages the lifecycle of projects containing multiple correlated plans.
 * Orchestrates epic decomposition, saga-based plan sequencing, sprint iteration,
 * and release assembly.</p>
 *
 * <p>Lifecycle: PLANNING → ACTIVE → STABILIZING → RELEASED</p>
 *
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>Create projects with epic decomposition</li>
 *   <li>Sequence plans via saga pattern with compensating actions</li>
 *   <li>Track sprint-level progress</li>
 *   <li>Assemble releases with event-sourced release notes</li>
 * </ul>
 *
 * @see <a href="https://www.vldb.org/pvldb/vol18/">SagaLLM (VLDB 2025)</a>
 */
@Service
@ConditionalOnProperty(prefix = "lifecycle", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(LifecycleConfig.class)
public class ProjectLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ProjectLifecycleService.class);

    private final LifecycleConfig config;
    private final ProjectRepository projectRepository;
    private final EpicDecomposer epicDecomposer;
    private final SagaPlanSequencer sagaSequencer;
    private final JdbcTemplate jdbc;
    private final Counter projectsCreated;
    private final Counter sagaCompensations;

    public ProjectLifecycleService(LifecycleConfig config,
                                     ProjectRepository projectRepository,
                                     EpicDecomposer epicDecomposer,
                                     SagaPlanSequencer sagaSequencer,
                                     JdbcTemplate jdbc,
                                     @Nullable MeterRegistry meterRegistry) {
        this.config = config;
        this.projectRepository = projectRepository;
        this.epicDecomposer = epicDecomposer;
        this.sagaSequencer = sagaSequencer;
        this.jdbc = jdbc;

        if (meterRegistry != null) {
            projectsCreated = Counter.builder("lifecycle_projects_created_total")
                    .description("Total projects created")
                    .register(meterRegistry);
            sagaCompensations = Counter.builder("lifecycle_saga_compensations_total")
                    .description("Total saga compensations triggered")
                    .register(meterRegistry);
        } else {
            projectsCreated = null;
            sagaCompensations = null;
        }
    }

    /**
     * Creates a new project from a high-level specification.
     *
     * <p>Decomposes the spec into epics, creates a Project entity,
     * and prepares the saga steps (plans not yet created).</p>
     *
     * @param name        project name
     * @param description project-level specification
     * @return the created project
     */
    public Project createProject(String name, String description) {
        UUID projectId = UUID.randomUUID();
        Project project = new Project(projectId, name, description);

        // Decompose into epics
        List<Epic> epics = epicDecomposer.decompose(description);
        project.setEpicSpecs(serializeEpics(epics));

        projectRepository.save(project);
        if (projectsCreated != null) projectsCreated.increment();

        log.info("Created project {} with {} epics", projectId, epics.size());
        return project;
    }

    /**
     * Activates a project: creates plans from epics and starts saga execution.
     *
     * <p>For each epic, a plan ID is pre-generated and linked via {@code project_plans}.
     * The actual plan creation (via OrchestrationService.createAndStart) would be
     * triggered by the saga sequencer as each step begins.</p>
     *
     * @param projectId the project to activate
     * @return list of plan IDs that will be created
     */
    public List<UUID> activateProject(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        project.transitionTo(ProjectStatus.ACTIVE);

        // Generate plan IDs for each epic
        List<Epic> epics = epicDecomposer.decompose(project.getDescription());
        List<UUID> planIds = new ArrayList<>();
        List<String> compensatingSpecs = new ArrayList<>();

        for (int i = 0; i < epics.size(); i++) {
            Epic epic = epics.get(i);
            UUID planId = UUID.randomUUID();
            planIds.add(planId);
            compensatingSpecs.add(epic.compensatingSpec());

            // Link plan to project
            jdbc.update("""
                INSERT INTO project_plans (id, project_id, plan_id, epic_name, ordinal, sprint_number, created_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 1, NOW())
                """, UUID.randomUUID().toString(), projectId.toString(),
                    planId.toString(), epic.name(), i);
        }

        // Initialize saga
        sagaSequencer.initializeSaga(projectId, planIds, compensatingSpecs);

        projectRepository.save(project);

        log.info("Activated project {} with {} plans", projectId, planIds.size());
        return planIds;
    }

    /**
     * Checks if a project should transition to STABILIZING (all forward plans complete).
     *
     * @param projectId the project to check
     * @return true if transitioned to STABILIZING
     */
    public boolean checkStabilization(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getStatus() != ProjectStatus.ACTIVE) return false;

        SagaProgress progress = sagaSequencer.getProgress(projectId);

        if (progress.isAllCompleted()) {
            project.transitionTo(ProjectStatus.STABILIZING);
            projectRepository.save(project);
            log.info("Project {} entering STABILIZING (all {} plans completed)", projectId, progress.totalSteps());
            return true;
        }

        if (progress.hasFailed()) {
            if (sagaCompensations != null) sagaCompensations.increment();
            log.warn("Project {} has failed plans, saga compensation may trigger", projectId);
        }

        return false;
    }

    /**
     * Releases a project: generates release notes and transitions to RELEASED.
     *
     * @param projectId the project to release
     * @return release notes text
     */
    public String releaseProject(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        if (config.release().requireStabilization() && project.getStatus() != ProjectStatus.STABILIZING) {
            throw new IllegalStateException("Project must be in STABILIZING before release");
        }

        // Generate release notes from completed plans
        String releaseNotes = generateReleaseNotes(projectId);
        project.setReleaseNotes(releaseNotes);
        project.transitionTo(ProjectStatus.RELEASED);
        projectRepository.save(project);

        log.info("Released project {}", projectId);
        return releaseNotes;
    }

    /**
     * Cancels a project.
     */
    public void cancelProject(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        project.transitionTo(ProjectStatus.CANCELLED);
        projectRepository.save(project);
        log.info("Cancelled project {}", projectId);
    }

    /**
     * Returns project status summary for monitoring.
     */
    public Map<String, Object> getProjectStatus(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) return Map.of("error", "not_found");

        SagaProgress progress = sagaSequencer.getProgress(projectId);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("id", project.getId());
        status.put("name", project.getName());
        status.put("status", project.getStatus());
        status.put("saga", Map.of(
                "total", progress.totalSteps(),
                "completed", progress.completed(),
                "running", progress.running(),
                "pending", progress.pending(),
                "failed", progress.failed(),
                "compensating", progress.compensating()));
        status.put("createdAt", project.getCreatedAt());
        status.put("updatedAt", project.getUpdatedAt());

        return status;
    }

    // --- Internal ---

    private String generateReleaseNotes(UUID projectId) {
        // Query completed plans and their results
        List<Map<String, Object>> plans = jdbc.queryForList("""
            SELECT pp.epic_name, pp.ordinal, p.spec, p.status
            FROM project_plans pp
            JOIN plans p ON p.id = pp.plan_id
            WHERE pp.project_id = ?::uuid
            ORDER BY pp.ordinal
            """, projectId.toString());

        StringBuilder notes = new StringBuilder();
        notes.append("# Release Notes\n\n");

        for (Map<String, Object> plan : plans) {
            String epicName = (String) plan.get("epic_name");
            String planStatus = (String) plan.get("status");
            notes.append("## ").append(epicName != null ? epicName : "Plan").append("\n");
            notes.append("Status: ").append(planStatus).append("\n\n");
        }

        return notes.toString();
    }

    private String serializeEpics(List<Epic> epics) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < epics.size(); i++) {
            if (i > 0) sb.append(",");
            Epic e = epics.get(i);
            sb.append("{\"name\":\"").append(escapeJson(e.name()))
              .append("\",\"ordinal\":").append(e.ordinal())
              .append(",\"complexity\":\"").append(e.complexity())
              .append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
