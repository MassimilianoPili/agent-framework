package com.agentframework.orchestrator.service;

import com.agentframework.orchestrator.event.PlanCompletedEvent;
import com.agentframework.orchestrator.event.PlanCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Automatically creates plan snapshots at key lifecycle events.
 * Leverages the Observer pattern (Spring Events) to trigger Memento snapshots.
 *
 * <p>Runs asynchronously after commit to avoid blocking the thread that publishes
 * the plan event (typically the Service Bus message handler).</p>
 */
@Component
public class PlanSnapshotListener {

    private static final Logger log = LoggerFactory.getLogger(PlanSnapshotListener.class);

    private final PlanSnapshotService snapshotService;

    public PlanSnapshotListener(PlanSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @Async("orchestratorAsyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanCreated(PlanCreatedEvent event) {
        try {
            snapshotService.snapshot(event.planId(), "after_planner");
        } catch (Exception e) {
            log.warn("Failed to create snapshot for plan {}: {}", event.planId(), e.getMessage());
        }
    }

    @Async("orchestratorAsyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanCompleted(PlanCompletedEvent event) {
        try {
            snapshotService.snapshot(event.planId(), "plan_completed_" + event.status().name().toLowerCase());
        } catch (Exception e) {
            log.warn("Failed to create completion snapshot for plan {}: {}", event.planId(), e.getMessage());
        }
    }
}
