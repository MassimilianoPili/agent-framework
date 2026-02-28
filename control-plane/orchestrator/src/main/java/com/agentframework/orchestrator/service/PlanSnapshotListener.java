package com.agentframework.orchestrator.service;

import com.agentframework.orchestrator.event.PlanCompletedEvent;
import com.agentframework.orchestrator.event.PlanCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Automatically creates plan snapshots at key lifecycle events.
 * Leverages the Observer pattern (Spring Events) to trigger Memento snapshots.
 */
@Component
public class PlanSnapshotListener {

    private static final Logger log = LoggerFactory.getLogger(PlanSnapshotListener.class);

    private final PlanSnapshotService snapshotService;

    public PlanSnapshotListener(PlanSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @EventListener
    public void onPlanCreated(PlanCreatedEvent event) {
        try {
            snapshotService.snapshot(event.planId(), "after_planner");
        } catch (Exception e) {
            log.warn("Failed to create snapshot for plan {}: {}", event.planId(), e.getMessage());
        }
    }

    @EventListener
    public void onPlanCompleted(PlanCompletedEvent event) {
        try {
            snapshotService.snapshot(event.planId(), "plan_completed_" + event.status().name().toLowerCase());
        } catch (Exception e) {
            log.warn("Failed to create completion snapshot for plan {}: {}", event.planId(), e.getMessage());
        }
    }
}
