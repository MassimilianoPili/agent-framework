package com.agentframework.orchestrator.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saga-based plan sequencer for multi-plan projects.
 *
 * <p>Each plan in a project saga has a corresponding compensating spec
 * (natural language). If a plan fails and {@code compensateOnFailure=true},
 * the sequencer triggers compensating plans in reverse order.</p>
 *
 * <p>Saga advancement is poll-based via {@code @Scheduled} to avoid
 * modifying {@code OrchestrationService}. The poller checks for completed
 * saga steps and advances to the next step, or triggers compensation
 * on failure.</p>
 *
 * <p>Step states:</p>
 * <pre>
 * PENDING → RUNNING → COMPLETED | FAILED
 * FAILED → COMPENSATING → COMPENSATED
 * </pre>
 *
 * @see <a href="https://www.vldb.org/pvldb/vol18/">SagaLLM (VLDB 2025)</a>
 */
@Component
@ConditionalOnProperty(prefix = "lifecycle", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SagaPlanSequencer {

    private static final Logger log = LoggerFactory.getLogger(SagaPlanSequencer.class);

    private final LifecycleConfig config;
    private final JdbcTemplate jdbc;

    public SagaPlanSequencer(LifecycleConfig config, JdbcTemplate jdbc) {
        this.config = config;
        this.jdbc = jdbc;
    }

    /**
     * Creates saga steps for a project from epic decomposition.
     *
     * @param projectId project UUID
     * @param planIds   ordered plan UUIDs (one per epic)
     * @param compensatingSpecs compensating spec per plan (nullable entries)
     */
    public void initializeSaga(UUID projectId, List<UUID> planIds, List<String> compensatingSpecs) {
        for (int i = 0; i < planIds.size(); i++) {
            String compSpec = i < compensatingSpecs.size() ? compensatingSpecs.get(i) : null;
            jdbc.update("""
                INSERT INTO project_saga_steps (id, project_id, plan_id, step_ordinal,
                    compensating_spec, status, created_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'PENDING', NOW())
                """, UUID.randomUUID().toString(), projectId.toString(),
                    planIds.get(i).toString(), i, compSpec);
        }
        log.info("Initialized saga for project {} with {} steps", projectId, planIds.size());
    }

    /**
     * Starts the first pending step in a saga.
     *
     * @param projectId project UUID
     * @return plan ID of the started step, or null if no pending steps
     */
    @Nullable
    public UUID startNextStep(UUID projectId) {
        List<Map<String, Object>> pending = jdbc.queryForList("""
            SELECT id, plan_id, step_ordinal FROM project_saga_steps
            WHERE project_id = ?::uuid AND status = 'PENDING'
            ORDER BY step_ordinal ASC LIMIT 1
            """, projectId.toString());

        if (pending.isEmpty()) return null;

        Map<String, Object> step = pending.getFirst();
        String stepId = step.get("id").toString();
        String planId = step.get("plan_id").toString();

        jdbc.update("UPDATE project_saga_steps SET status = 'RUNNING' WHERE id = ?::uuid", stepId);
        log.debug("Started saga step {} (plan={}) for project {}", stepId, planId, projectId);

        return UUID.fromString(planId);
    }

    /**
     * Polls for completed/failed saga steps and advances accordingly.
     *
     * <p>Checks running steps against plan status. If a plan completed,
     * advances to next step. If a plan failed and compensation is enabled,
     * triggers reverse compensation.</p>
     */
    @Scheduled(fixedDelayString = "${lifecycle.saga.poll-interval-ms:30000}")
    public void pollSagaAdvancement() {
        // Find running saga steps
        List<Map<String, Object>> runningSteps = jdbc.queryForList("""
            SELECT s.id AS step_id, s.project_id, s.plan_id, s.step_ordinal,
                   s.compensating_spec, p.status AS plan_status
            FROM project_saga_steps s
            JOIN plans p ON p.id = s.plan_id
            WHERE s.status = 'RUNNING'
            """);

        for (Map<String, Object> step : runningSteps) {
            String planStatus = (String) step.get("plan_status");
            String stepId = step.get("step_id").toString();
            UUID projectId = UUID.fromString(step.get("project_id").toString());

            if ("COMPLETED".equals(planStatus)) {
                // Step completed — mark and advance
                jdbc.update("""
                    UPDATE project_saga_steps SET status = 'COMPLETED', completed_at = NOW()
                    WHERE id = ?::uuid
                    """, stepId);
                log.info("Saga step {} completed for project {}", stepId, projectId);

                // Start next step
                startNextStep(projectId);

            } else if ("FAILED".equals(planStatus)) {
                // Step failed
                jdbc.update("UPDATE project_saga_steps SET status = 'FAILED' WHERE id = ?::uuid", stepId);
                log.warn("Saga step {} failed for project {}", stepId, projectId);

                if (config.saga().compensateOnFailure()) {
                    triggerCompensation(projectId);
                }
            }
        }
    }

    /**
     * Triggers reverse compensation for a failed saga.
     *
     * <p>Walks backward through completed steps and creates compensating
     * plans using their compensating_spec. Compensating plan IDs are
     * recorded on the step for tracking.</p>
     */
    void triggerCompensation(UUID projectId) {
        List<Map<String, Object>> completedSteps = jdbc.queryForList("""
            SELECT id, plan_id, compensating_spec, step_ordinal
            FROM project_saga_steps
            WHERE project_id = ?::uuid AND status = 'COMPLETED' AND compensating_spec IS NOT NULL
            ORDER BY step_ordinal DESC
            """, projectId.toString());

        if (completedSteps.isEmpty()) {
            log.info("No compensable steps for project {}", projectId);
            return;
        }

        log.info("Triggering compensation for project {} ({} steps)", projectId, completedSteps.size());

        for (Map<String, Object> step : completedSteps) {
            String stepId = step.get("id").toString();
            // Mark as compensating
            jdbc.update("UPDATE project_saga_steps SET status = 'COMPENSATING' WHERE id = ?::uuid", stepId);

            // The actual plan creation (OrchestrationService.createAndStart) would be
            // called by ProjectLifecycleService which has the dependency.
            // Here we just record the intent.
            log.debug("Compensation pending for step {} (spec: {}...)",
                    stepId, truncate((String) step.get("compensating_spec"), 80));
        }
    }

    /**
     * Returns saga progress for a project.
     */
    public SagaProgress getProgress(UUID projectId) {
        List<Map<String, Object>> steps = jdbc.queryForList("""
            SELECT status, COUNT(*) as cnt FROM project_saga_steps
            WHERE project_id = ?::uuid GROUP BY status
            """, projectId.toString());

        int total = 0, completed = 0, failed = 0, running = 0, pending = 0, compensating = 0;
        for (Map<String, Object> row : steps) {
            int cnt = ((Number) row.get("cnt")).intValue();
            total += cnt;
            switch ((String) row.get("status")) {
                case "COMPLETED" -> completed = cnt;
                case "FAILED" -> failed = cnt;
                case "RUNNING" -> running = cnt;
                case "PENDING" -> pending = cnt;
                case "COMPENSATING", "COMPENSATED" -> compensating += cnt;
            }
        }

        return new SagaProgress(total, completed, running, pending, failed, compensating);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // --- Types ---

    public record SagaProgress(
            int totalSteps,
            int completed,
            int running,
            int pending,
            int failed,
            int compensating
    ) {
        public boolean isAllCompleted() {
            return completed == totalSteps && totalSteps > 0;
        }

        public boolean hasFailed() {
            return failed > 0;
        }
    }
}
