package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Safety-net poller that picks up items stuck in {@link ItemStatus#TO_DISPATCH}.
 *
 * <p>Normally, items are dispatched immediately by the REST endpoint
 * ({@link OrchestrationService#redispatchItem}). This poller handles edge cases:</p>
 * <ul>
 *   <li>Direct DB updates: an operator sets {@code status = 'TO_DISPATCH'} via SQL</li>
 *   <li>Crash recovery: the endpoint transitioned to TO_DISPATCH but crashed before dispatch</li>
 * </ul>
 *
 * <p>Runs every 10 seconds (configurable). Each item is dispatched in its own
 * transaction via {@link RedispatchTransactionService} to isolate failures.</p>
 *
 * @see AutoRetryScheduler for the analogous pattern used by automatic retry
 */
@Component
public class RedispatchPollerService {

    private static final Logger log = LoggerFactory.getLogger(RedispatchPollerService.class);

    private final PlanItemRepository planItemRepository;
    private final RedispatchTransactionService redispatchTransactionService;

    public RedispatchPollerService(PlanItemRepository planItemRepository,
                                    RedispatchTransactionService redispatchTransactionService) {
        this.planItemRepository = planItemRepository;
        this.redispatchTransactionService = redispatchTransactionService;
    }

    @Scheduled(fixedDelayString = "${redispatch.poller-interval-ms:10000}")
    public void pollToDispatch() {
        List<PlanItem> items = planItemRepository.findByStatusWithPlan(ItemStatus.TO_DISPATCH);
        if (items.isEmpty()) {
            return;
        }

        log.info("RedispatchPoller: found {} item(s) in TO_DISPATCH", items.size());

        for (PlanItem item : items) {
            try {
                redispatchTransactionService.redispatchItem(item.getId());
                log.info("Poller redispatched item {} (task={})", item.getId(), item.getTaskKey());
            } catch (Exception e) {
                log.error("Poller redispatch failed for item {} (task={}): {}",
                          item.getId(), item.getTaskKey(), e.getMessage());
            }
        }
    }
}
