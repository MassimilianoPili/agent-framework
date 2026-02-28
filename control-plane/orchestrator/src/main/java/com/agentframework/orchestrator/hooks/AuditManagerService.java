package com.agentframework.orchestrator.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized audit event receiver for the agent framework.
 *
 * <p>Receives JSONL audit events forwarded by {@code audit-log.sh} via HTTP POST.
 * The shell script sends events asynchronously (fire-and-forget) so audit logging
 * never blocks tool execution.</p>
 *
 * <p>Events are stored in-memory and exposed via a REST API for querying.
 * Future iterations may persist to a database or stream to an observability platform.</p>
 *
 * <p>Endpoint: {@code POST /audit/events} — accepts raw JSON event body.</p>
 */
@RestController
@RequestMapping("/audit")
public class AuditManagerService {

    private static final Logger log = LoggerFactory.getLogger(AuditManagerService.class);
    private static final int MAX_EVENTS = 10_000;

    /** In-memory event store (thread-safe, bounded by MAX_EVENTS). */
    private final CopyOnWriteArrayList<Map<String, Object>> events = new CopyOnWriteArrayList<>();

    /**
     * Receives an audit event from a worker's {@code audit-log.sh}.
     *
     * <p>Expected JSON body:</p>
     * <pre>{@code
     * {"ts":"2025-01-01T00:00:00Z","session":"...","tool":"Bash","worker":"BE","taskKey":"be-001"}
     * }</pre>
     */
    @PostMapping("/events")
    public ResponseEntity<Void> receiveEvent(@RequestBody Map<String, Object> event) {
        if (events.size() >= MAX_EVENTS) {
            // Evict oldest entries when at capacity (simple ring-buffer behavior)
            events.remove(0);
        }
        events.add(event);
        log.debug("Audit event received: tool={} worker={} task={}",
                  event.get("tool"), event.get("worker"), event.get("taskKey"));
        return ResponseEntity.ok().build();
    }

    /**
     * Returns all stored audit events, optionally filtered by taskKey.
     *
     * @param taskKey optional filter; if null, returns all events
     */
    @GetMapping("/events")
    public List<Map<String, Object>> getEvents(@RequestParam(required = false) String taskKey) {
        if (taskKey == null || taskKey.isBlank()) {
            return List.copyOf(events);
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> event : events) {
            if (taskKey.equals(event.get("taskKey"))) {
                filtered.add(event);
            }
        }
        return filtered;
    }

    /**
     * Health check and summary endpoint.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "storedEvents", events.size());
    }
}
