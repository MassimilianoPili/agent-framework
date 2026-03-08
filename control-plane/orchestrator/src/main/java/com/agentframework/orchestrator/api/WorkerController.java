package com.agentframework.orchestrator.api;

import com.agentframework.messaging.inprocess.InProcessMessageBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * REST API for in-process worker lifecycle management (#29 Phase 1).
 *
 * <p>Only active when an {@link InProcessMessageBroker} bean is present in the context
 * (i.e., when {@code messaging.provider=in-process}). In Redis mode, the broker is absent
 * and all endpoints return 404.</p>
 */
@RestController
@RequestMapping("/api/v1/workers")
public class WorkerController {

    private static final Logger log = LoggerFactory.getLogger(WorkerController.class);

    private final InProcessMessageBroker inProcessBroker;

    public WorkerController(Optional<InProcessMessageBroker> inProcessBroker) {
        this.inProcessBroker = inProcessBroker.orElse(null);
    }

    /**
     * Returns the list of currently running task keys in the in-process broker.
     *
     * <p>Response: 200 with a JSON array of task-key strings, or 404 if not in in-process mode.</p>
     */
    @GetMapping
    public ResponseEntity<List<String>> getRunningWorkers() {
        if (inProcessBroker == null) {
            return ResponseEntity.notFound().build();
        }
        List<String> running = inProcessBroker.getRunningTaskKeys();
        log.debug("GET /api/v1/workers — {} task(s) running", running.size());
        return ResponseEntity.ok(running);
    }

    /**
     * Returns the count of currently running tasks.
     *
     * <p>Useful for health checks and load monitoring.</p>
     */
    @GetMapping("/count")
    public ResponseEntity<Integer> getRunningCount() {
        if (inProcessBroker == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(inProcessBroker.getRunningTaskCount());
    }

    /**
     * Cancels a specific running task by task key.
     *
     * <p>Sends {@code Thread.interrupt()} to the virtual thread executing the task.
     * The task will stop at the next interrupt check in {@code PolicyEnforcingToolCallback}
     * and publish a CANCELLED result. Returns 200 if the signal was sent, 404 if not found.</p>
     */
    @PostMapping("/{taskKey}/cancel")
    public ResponseEntity<Void> cancelTask(@PathVariable String taskKey) {
        if (inProcessBroker == null) {
            return ResponseEntity.notFound().build();
        }
        boolean cancelled = inProcessBroker.cancel(taskKey);
        log.info("Manual cancel for task '{}': signal_sent={}", taskKey, cancelled);
        return cancelled ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
