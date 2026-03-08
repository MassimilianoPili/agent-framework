package com.agentframework.orchestrator.workspace;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanStatus;
import com.agentframework.orchestrator.repository.PlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

/**
 * Periodically cleans up workspace directories for completed/failed plans.
 *
 * <p>Runs every 5 minutes. Finds plans in terminal state (COMPLETED or FAILED)
 * that have been finished for over 1 hour and still have a workspace volume.
 * Destroys the workspace directory and clears the reference.</p>
 */
@Component
public class WorkspaceCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCleanupScheduler.class);
    private static final Duration CLEANUP_DELAY = Duration.ofHours(1);

    private final PlanRepository planRepository;
    private final WorkspaceManager workspaceManager;

    public WorkspaceCleanupScheduler(PlanRepository planRepository, WorkspaceManager workspaceManager) {
        this.planRepository = planRepository;
        this.workspaceManager = workspaceManager;
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void cleanupStaleWorkspaces() {
        Instant cutoff = Instant.now().minus(CLEANUP_DELAY);
        List<Plan> stale = planRepository.findPlansWithStaleWorkspaces(cutoff);

        if (stale.isEmpty()) return;

        log.info("Found {} plans with stale workspaces to clean up", stale.size());
        for (Plan plan : stale) {
            try {
                workspaceManager.destroyWorkspace(plan.getWorkspaceVolume());
                plan.setWorkspaceVolume(null);
                planRepository.save(plan);
                log.info("Cleaned up workspace for plan {}", plan.getId());
            } catch (Exception e) {
                log.warn("Failed to clean workspace for plan {}: {}", plan.getId(), e.getMessage());
            }
        }
    }
}
