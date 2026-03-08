package com.agentframework.orchestrator.hooks;

import com.agentframework.orchestrator.audit.AuditEvent;
import com.agentframework.orchestrator.audit.AuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Centralized audit event receiver for the agent framework.
 *
 * <p>Receives JSONL audit events forwarded by {@code audit-log.sh} via HTTP POST.
 * The shell script sends events asynchronously (fire-and-forget) so audit logging
 * never blocks tool execution.</p>
 *
 * <p>Events are persisted to PostgreSQL via {@link AuditEventRepository} and survive
 * container restarts. A nightly cleanup job deletes events older than 30 days.</p>
 *
 * <p>Endpoint: {@code POST /audit/events} — accepts raw JSON event body.</p>
 */
@RestController
@RequestMapping("/audit")
public class AuditManagerService {

    private static final Logger log = LoggerFactory.getLogger(AuditManagerService.class);

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public AuditManagerService(AuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

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
        String raw;
        try {
            raw = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            raw = "{}";
        }
        AuditEvent auditEvent = new AuditEvent(
            getString(event, "taskKey"),
            getString(event, "tool"),
            getString(event, "worker"),
            getString(event, "session"),
            Instant.now(),
            raw
        );
        repository.save(auditEvent);
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
    public List<AuditEvent> getEvents(@RequestParam(required = false) String taskKey) {
        if (taskKey == null || taskKey.isBlank()) {
            return repository.findAll();
        }
        return repository.findByTaskKeyOrderByOccurredAtDesc(taskKey);
    }

    /**
     * Health check and summary endpoint.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "storedEvents", repository.count());
    }

    /**
     * Nightly cleanup: removes audit events older than 30 days.
     * Runs at 03:00 server time to avoid peak load periods.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldEvents() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        repository.deleteByOccurredAtBefore(cutoff);
        log.info("Cleaned up audit events older than 30 days (cutoff={})", cutoff);
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
