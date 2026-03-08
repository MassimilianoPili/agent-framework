package com.agentframework.worker.runtime;

import com.agentframework.worker.dto.AgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Set;

/**
 * REST API for the standalone worker-runtime JVM.
 *
 * <p>Called by the orchestrator's {@code RemoteWorkerClient}:
 * <ul>
 *   <li>{@code POST /tasks} — dispatch a task (fire-and-forget, 202 Accepted)
 *   <li>{@code POST /tasks/{taskKey}/cancel} — cancel a running task
 *   <li>{@code GET /tasks} — list running task keys
 *   <li>{@code GET /health} — runtime health info
 * </ul>
 *
 * <p>The request body for {@code POST /tasks} is the raw JSON produced by the orchestrator's
 * {@code AgentTaskProducer} — the same {@link AgentTask} record used throughout the framework.
 */
@RestController
public class WorkerRuntimeController {

    private static final Logger log = LoggerFactory.getLogger(WorkerRuntimeController.class);

    private final LocalTaskDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public WorkerRuntimeController(LocalTaskDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * Receives a task from the orchestrator and dispatches it on a virtual thread.
     *
     * @return 202 Accepted if dispatched, 409 Conflict if already running
     */
    @PostMapping("/tasks")
    public ResponseEntity<Map<String, String>> dispatchTask(@RequestBody String body) {
        try {
            AgentTask task = objectMapper.readValue(body, AgentTask.class);

            boolean accepted = dispatcher.dispatch(task);
            if (accepted) {
                return ResponseEntity.accepted()
                        .body(Map.of("status", "accepted", "taskKey", task.taskKey()));
            } else {
                return ResponseEntity.status(409)
                        .body(Map.of("status", "conflict", "taskKey", task.taskKey(),
                                "reason", "Task already running"));
            }
        } catch (Exception e) {
            log.error("Failed to deserialize task: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "reason", e.getMessage()));
        }
    }

    /**
     * Cancels a running task by interrupting its virtual thread.
     *
     * @return 200 OK if cancelled, 404 Not Found if not running
     */
    @PostMapping("/tasks/{taskKey}/cancel")
    public ResponseEntity<Map<String, String>> cancelTask(@PathVariable String taskKey) {
        boolean cancelled = dispatcher.cancel(taskKey);
        if (cancelled) {
            return ResponseEntity.ok(Map.of("status", "cancelled", "taskKey", taskKey));
        } else {
            return ResponseEntity.status(404)
                    .body(Map.of("status", "not_found", "taskKey", taskKey));
        }
    }

    /**
     * Returns the set of currently running task keys.
     */
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> listTasks() {
        Set<String> running = dispatcher.getRunningTaskKeys();
        return ResponseEntity.ok(Map.of(
                "count", running.size(),
                "tasks", running));
    }

    /**
     * Health endpoint with runtime info for the orchestrator's health monitor.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Runtime rt = Runtime.getRuntime();
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "runningTasks", dispatcher.getRunningTaskCount(),
                "heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024),
                "heapMaxMb", rt.maxMemory() / (1024 * 1024),
                "uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000));
    }
}
