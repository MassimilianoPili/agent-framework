package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Polls for FAILED plan items whose {@code nextRetryAt} has elapsed and re-queues them.
 *
 * <p>Runs every 5 seconds. Uses {@link OrchestrationService#retryFailedItem(java.util.UUID)}
 * which handles the FAILED → WAITING transition and re-opens PAUSED/FAILED plans.</p>
 *
 * <p>The backoff and PAUSED threshold are set in {@code OrchestrationService.onTaskCompleted()}
 * at the time of failure. This scheduler only decides <em>when</em> to re-trigger, not how
 * many attempts remain (that is tracked via {@link com.agentframework.orchestrator.repository.DispatchAttemptRepository}).</p>
 */
@Component
public class AutoRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoRetryScheduler.class);

    private final PlanItemRepository planItemRepository;
    private final OrchestrationService orchestrationService;

    public AutoRetryScheduler(PlanItemRepository planItemRepository,
                               OrchestrationService orchestrationService) {
        this.planItemRepository = planItemRepository;
        this.orchestrationService = orchestrationService;
    }

    @Scheduled(fixedDelayString = "${retry.scheduler-interval-ms:5000}")
    @Transactional
    public void retryEligibleItems() {
        List<PlanItem> eligible = planItemRepository.findRetryEligible(Instant.now());
        if (eligible.isEmpty()) {
            return;
        }

        log.info("AutoRetryScheduler: found {} item(s) eligible for retry", eligible.size());

        for (PlanItem item : eligible) {
            try {
                // Clear nextRetryAt before retrying so the scheduler doesn't pick it up again
                item.setNextRetryAt(null);
                planItemRepository.save(item);
                orchestrationService.retryFailedItem(item.getId());
                log.info("Auto-retried item {} (task={})", item.getId(), item.getTaskKey());
            } catch (Exception e) {
                log.error("Auto-retry failed for item {} (task={}): {}",
                          item.getId(), item.getTaskKey(), e.getMessage());
            }
        }
    }
}
