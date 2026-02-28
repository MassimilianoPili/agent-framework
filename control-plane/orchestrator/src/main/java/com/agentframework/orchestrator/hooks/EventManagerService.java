package com.agentframework.orchestrator.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hook violation event receiver and tracker.
 *
 * <p>Receives violation events forwarded by hook scripts when they exit with code 2
 * (blocking a tool call). The shell scripts POST to this endpoint before exiting,
 * allowing the orchestrator to track violation patterns and react.</p>
 *
 * <p>Current behavior: tracks violation counts per taskKey. Future iterations may
 * trigger automatic plan cancellation when violations exceed a configurable threshold.</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code POST /events/violation} — receive a hook violation</li>
 *   <li>{@code GET /events/violations} — query stored violations</li>
 *   <li>{@code GET /events/health} — health and summary</li>
 * </ul>
 */
@RestController
@RequestMapping("/events")
public class EventManagerService {

    private static final Logger log = LoggerFactory.getLogger(EventManagerService.class);
    private static final int MAX_VIOLATIONS = 5_000;

    /** Violation counter per taskKey. */
    private final ConcurrentHashMap<String, AtomicInteger> violationCountByTask = new ConcurrentHashMap<>();

    /** Raw violation event log (in-memory, bounded). */
    private final CopyOnWriteArrayList<Map<String, Object>> violations = new CopyOnWriteArrayList<>();

    /**
     * Receives a hook violation event.
     *
     * <p>Expected JSON body:</p>
     * <pre>{@code
     * {"tool":"Glob","taskKey":"be-001","worker":"BE","reason":"Tool not in allowlist","ts":"..."}
     * }</pre>
     */
    @PostMapping("/violation")
    public ResponseEntity<Void> receiveViolation(@RequestBody Map<String, Object> violation) {
        if (violations.size() >= MAX_VIOLATIONS) {
            violations.remove(0);
        }
        violations.add(violation);

        String taskKey = (String) violation.getOrDefault("taskKey", "unknown");
        int count = violationCountByTask
            .computeIfAbsent(taskKey, k -> new AtomicInteger(0))
            .incrementAndGet();

        log.warn("Hook violation #{} for task {}: tool={} reason={}",
                 count, taskKey, violation.get("tool"), violation.get("reason"));

        return ResponseEntity.ok().build();
    }

    /**
     * Returns all violations, optionally filtered by taskKey.
     */
    @GetMapping("/violations")
    public List<Map<String, Object>> getViolations(@RequestParam(required = false) String taskKey) {
        if (taskKey == null || taskKey.isBlank()) {
            return List.copyOf(violations);
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> v : violations) {
            if (taskKey.equals(v.get("taskKey"))) {
                filtered.add(v);
            }
        }
        return filtered;
    }

    /**
     * Returns the violation count for a specific taskKey.
     */
    public int getViolationCount(String taskKey) {
        AtomicInteger counter = violationCountByTask.get(taskKey);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Health check and summary endpoint.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        int totalViolations = violationCountByTask.values().stream()
            .mapToInt(AtomicInteger::get)
            .sum();
        return Map.of(
            "status", "ok",
            "totalViolations", totalViolations,
            "tasksWithViolations", violationCountByTask.size()
        );
    }
}
