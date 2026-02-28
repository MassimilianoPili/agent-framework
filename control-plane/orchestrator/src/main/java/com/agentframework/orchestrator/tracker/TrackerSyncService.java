package com.agentframework.orchestrator.tracker;

import com.agentframework.orchestrator.event.SpringPlanEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Asynchronously synchronises plan events to an external issue tracker via MCP tool.
 *
 * <p>Follows the DB-first principle: the internal DB is the source of truth.
 * The external tracker is eventually consistent — this service propagates changes
 * asynchronously without blocking the orchestration thread.</p>
 *
 * <p>The MCP tool invocation ({@code tracker-mcp}) is a placeholder: the actual
 * tool call will be implemented when the {@code tracker-mcp} MCP server is available.
 * Until then, this service logs the events it would sync.</p>
 *
 * <p>Uses {@code @Async} so that tracker failures (network, rate limits) never
 * propagate to the orchestration transaction.</p>
 */
@Service
public class TrackerSyncService {

    private static final Logger log = LoggerFactory.getLogger(TrackerSyncService.class);

    /**
     * Handles a plan event by syncing it to the external tracker.
     * Runs asynchronously on the task executor to avoid blocking the orchestration thread.
     */
    @EventListener
    @Async
    public void onPlanEvent(SpringPlanEvent event) {
        try {
            syncToTracker(event);
        } catch (Exception e) {
            // Log but never propagate — tracker sync is best-effort
            log.warn("Failed to sync event {} for plan {} to tracker: {}",
                     event.eventType(), event.planId(), e.getMessage());
        }
    }

    private void syncToTracker(SpringPlanEvent event) {
        // TODO: invoke tracker-mcp tool when available.
        // The tool call will update the external issue tracker with the event data.
        //
        // Example for TASK_COMPLETED:
        //   mcpClient.call("tracker-mcp", "update_issue", Map.of(
        //       "issue_id", resolveIssueId(event),
        //       "status",   mapStatus(event.eventType()),
        //       "comment",  buildComment(event)
        //   ));
        //
        // For now, log the event that would have been synced.
        switch (event.eventType()) {
            case SpringPlanEvent.TASK_COMPLETED ->
                log.debug("Tracker sync [TASK_COMPLETED] plan={} task={} profile={} duration={}ms",
                          event.planId(), event.taskKey(), event.workerProfile(), event.durationMs());
            case SpringPlanEvent.TASK_FAILED ->
                log.debug("Tracker sync [TASK_FAILED] plan={} task={}",
                          event.planId(), event.taskKey());
            case SpringPlanEvent.PLAN_COMPLETED ->
                log.debug("Tracker sync [PLAN_COMPLETED] plan={}", event.planId());
            case SpringPlanEvent.PLAN_PAUSED ->
                log.debug("Tracker sync [PLAN_PAUSED] plan={}", event.planId());
            default ->
                log.debug("Tracker sync [{}] plan={}", event.eventType(), event.planId());
        }
    }
}
