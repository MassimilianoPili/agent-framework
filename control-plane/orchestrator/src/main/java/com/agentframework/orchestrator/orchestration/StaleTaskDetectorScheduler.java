package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Detects and fails DISPATCHED tasks that never received a result.
 *
 * <p>A task is considered stale when it has been in DISPATCHED status for longer
 * than the configured timeout. This can happen when:
 * <ul>
 *   <li>The worker crashed without publishing a result</li>
 *   <li>The result message was lost (Redis stream trimmed, network error)</li>
 *   <li>The worker is stuck in an infinite loop</li>
 * </ul>
 *
 * <p>Stale tasks are transitioned to FAILED with reason {@code "stale_timeout"},
 * which triggers the normal failure flow (automatic retry via AutoRetryScheduler
 * if attempts remain, or plan pause if the attempt threshold is reached).</p>
 */
@Component
public class StaleTaskDetectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(StaleTaskDetectorScheduler.class);

    private final PlanItemRepository planItemRepository;

    @Value("${stale.timeout-minutes:30}")
    private int timeoutMinutes;

    public StaleTaskDetectorScheduler(PlanItemRepository planItemRepository) {
        this.planItemRepository = planItemRepository;
    }

    @Scheduled(fixedDelayString = "${stale.detector-interval-ms:60000}")
    @Transactional
    public void detectStaleTasks() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
        List<PlanItem> staleTasks = planItemRepository.findStaleDispatched(cutoff);

        if (staleTasks.isEmpty()) {
            return;
        }

        log.warn("StaleTaskDetector: found {} stale DISPATCHED task(s) (timeout={}min)",
                 staleTasks.size(), timeoutMinutes);

        for (PlanItem item : staleTasks) {
            try {
                item.transitionTo(ItemStatus.FAILED);
                item.setFailureReason("stale_timeout");
                item.setCompletedAt(Instant.now());
                planItemRepository.save(item);
                log.warn("Marked stale task {} as FAILED (plan={}, dispatched={})",
                         item.getTaskKey(), item.getPlan().getId(), item.getDispatchedAt());
            } catch (Exception e) {
                log.error("Failed to mark stale task {} as FAILED: {}",
                          item.getTaskKey(), e.getMessage());
            }
        }
    }
}
