package com.agentframework.orchestrator.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Unified, typed domain event published by OrchestrationService for all plan state changes.
 *
 * <p>Consumers:</p>
 * <ul>
 *   <li>{@code SseEmitterRegistry} — streams events to connected browser clients</li>
 *   <li>{@code TrackerSyncService} — syncs state asynchronously to an external issue tracker</li>
 * </ul>
 *
 * <p>Event types:</p>
 * <ul>
 *   <li>{@code PLAN_STARTED} — plan was created and first wave dispatched</li>
 *   <li>{@code TASK_DISPATCHED} — a plan item was sent to a worker</li>
 *   <li>{@code TASK_COMPLETED} — a plan item finished successfully</li>
 *   <li>{@code TASK_FAILED} — a plan item failed (may be retried)</li>
 *   <li>{@code PLAN_COMPLETED} — all items are terminal, plan done</li>
 *   <li>{@code PLAN_PAUSED} — plan paused due to attemptsBeforePause threshold</li>
 *   <li>{@code PLAN_RESUMED} — paused plan was manually resumed</li>
 *   <li>{@code PLAN_CANCELLED} — plan was explicitly cancelled by the operator</li>
 *   <li>{@code ITEM_STATUS_CHANGED} — a plan item transitioned to a new status (extraJson: {"status":"..."}</li>
 *   <li>{@code BUDGET_UPDATE} — token budget usage for a worker type (extraJson: {"workerType":"...","consumed":N,"total":N})</li>
 *   <li>{@code SYSTEM_CRITICALITY} — system-level criticality alert (sandpile model, C &gt;= 0.8)</li>
 *   <li>{@code WORKER_DRIFT_DETECTED} — Wasserstein drift detected in a worker profile's reward distribution</li>
 *   <li>{@code CALIBRATION_DRIFT} — GP prediction calibration drift detected (Dutch Book vulnerability)</li>
 * </ul>
 *
 * <p>The {@code extraJson} field carries type-specific extra payload as a pre-serialized JSON
 * string (to avoid per-event serialization overhead in the hot path).</p>
 */
public record SpringPlanEvent(
        String eventType,
        UUID planId,
        UUID itemId,        // null for plan-level events (PLAN_STARTED, PLAN_COMPLETED, etc.)
        String taskKey,     // null for plan-level events
        String workerProfile,
        boolean success,
        long durationMs,
        Instant occurredAt,
        String extraJson    // nullable: type-specific extra payload (JSON string)
) {
    public static final String PLAN_STARTED         = "PLAN_STARTED";
    public static final String TASK_DISPATCHED      = "TASK_DISPATCHED";
    public static final String TASK_COMPLETED       = "TASK_COMPLETED";
    public static final String TASK_FAILED          = "TASK_FAILED";
    public static final String PLAN_COMPLETED       = "PLAN_COMPLETED";
    public static final String PLAN_PAUSED          = "PLAN_PAUSED";
    public static final String PLAN_RESUMED         = "PLAN_RESUMED";
    public static final String PLAN_CANCELLED       = "PLAN_CANCELLED";
    public static final String ITEM_STATUS_CHANGED  = "ITEM_STATUS_CHANGED";
    public static final String BUDGET_UPDATE        = "BUDGET_UPDATE";
    public static final String SYSTEM_CRITICALITY   = "SYSTEM_CRITICALITY";
    public static final String WORKER_DRIFT_DETECTED = "WORKER_DRIFT_DETECTED";
    public static final String CALIBRATION_DRIFT    = "CALIBRATION_DRIFT";

    /** Factory for system-level events without a specific plan context. */
    public static SpringPlanEvent forSystem(String eventType) {
        return new SpringPlanEvent(eventType, null, null, null, null, true, 0, Instant.now(), null);
    }

    /** Factory for system-level events with extra JSON payload. */
    public static SpringPlanEvent forSystem(String eventType, String extraJson) {
        return new SpringPlanEvent(eventType, null, null, null, null, true, 0, Instant.now(), extraJson);
    }

    /** Factory for plan-level events (no item context). */
    public static SpringPlanEvent forPlan(String eventType, UUID planId) {
        return new SpringPlanEvent(eventType, planId, null, null, null, true, 0, Instant.now(), null);
    }

    /** Factory for item-level events. */
    public static SpringPlanEvent forItem(String eventType, UUID planId, UUID itemId,
                                          String taskKey, String workerProfile,
                                          boolean success, long durationMs) {
        return new SpringPlanEvent(eventType, planId, itemId, taskKey,
                                   workerProfile, success, durationMs, Instant.now(), null);
    }

    /**
     * Factory for {@link #ITEM_STATUS_CHANGED} events.
     * {@code extraJson} format: {@code {"status":"NEW_STATUS"}}.
     */
    public static SpringPlanEvent forItemStatus(UUID planId, UUID itemId,
                                                String taskKey, String workerProfile,
                                                String newStatus) {
        String extra = "{\"status\":\"" + newStatus + "\"}";
        return new SpringPlanEvent(ITEM_STATUS_CHANGED, planId, itemId, taskKey,
                                   workerProfile, true, 0, Instant.now(), extra);
    }

    /**
     * Factory for {@link #BUDGET_UPDATE} events.
     * {@code extraJson} format: {@code {"workerType":"BE","consumed":1200,"total":5000}}.
     */
    public static SpringPlanEvent forBudgetUpdate(String workerType, long consumed, long total) {
        String extra = "{\"workerType\":\"" + workerType
                + "\",\"consumed\":" + consumed
                + ",\"total\":" + total + "}";
        return new SpringPlanEvent(BUDGET_UPDATE, null, null, null, null, true, 0, Instant.now(), extra);
    }
}
