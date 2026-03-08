package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.config.StaleDetectorProperties;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>Timeout is configurable per workerType via {@link StaleDetectorProperties}.
 * The query window uses {@code maxTimeoutMinutes()} to load all potentially stale
 * candidates, then each item is filtered in-memory against its specific timeout.</p>
 */
@Component
public class StaleTaskDetectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(StaleTaskDetectorScheduler.class);

    private final PlanItemRepository planItemRepository;
    private final StaleDetectorProperties staleProps;

    public StaleTaskDetectorScheduler(PlanItemRepository planItemRepository,
                                      StaleDetectorProperties staleProps) {
        this.planItemRepository = planItemRepository;
        this.staleProps = staleProps;
    }

    @Scheduled(fixedDelayString = "${stale.detector-interval-ms:60000}")
    @Transactional
    public void detectStaleTasks() {
        // Use the widest window so we load all candidates at once — filter per-workerType below
        Instant queryWindow = Instant.now().minus(Duration.ofMinutes(staleProps.maxTimeoutMinutes()));
        List<PlanItem> candidates = planItemRepository.findStaleDispatched(queryWindow);

        if (candidates.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        int failedCount = 0;

        for (PlanItem item : candidates) {
            int itemTimeout = staleProps.timeoutFor(item.getWorkerType().name());
            Instant itemCutoff = now.minus(Duration.ofMinutes(itemTimeout));

            if (item.getDispatchedAt() == null || !item.getDispatchedAt().isBefore(itemCutoff)) {
                // Not yet stale for this workerType's specific timeout — skip
                continue;
            }

            try {
                item.transitionTo(ItemStatus.FAILED);
                item.setFailureReason("stale_timeout");
                item.setCompletedAt(now);
                planItemRepository.save(item);
                log.warn("Marked stale task {} as FAILED (plan={}, workerType={}, timeout={}min, dispatched={})",
                         item.getTaskKey(), item.getPlan().getId(),
                         item.getWorkerType(), itemTimeout, item.getDispatchedAt());
                failedCount++;
            } catch (Exception e) {
                log.error("Failed to mark stale task {} as FAILED: {}",
                          item.getTaskKey(), e.getMessage());
            }
        }

        if (failedCount > 0) {
            log.warn("StaleTaskDetector: failed {} stale DISPATCHED task(s)", failedCount);
        }
    }
}
