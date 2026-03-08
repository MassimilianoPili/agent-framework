package com.agentframework.messaging.inprocess;

import com.agentframework.messaging.MessageAcknowledgment;
import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process message broker that replaces Redis Streams for single-JVM deployments.
 *
 * <p>Provides:
 * <ul>
 *   <li>Singleton enforcement: {@link ConcurrentHashMap} prevents duplicate task dispatch</li>
 *   <li>Cancellation: {@link Future#cancel(boolean)} with interrupt propagation</li>
 *   <li>Virtual threads: zero-overhead per task (~1KB stack), efficient for I/O-bound LLM calls</li>
 *   <li>Routing: workerType property in envelope maps to the correct registered handler</li>
 * </ul>
 *
 * <p>Handler routing: workers register with a group name following the convention
 * {@code {WORKER_TYPE}-worker-group} (e.g. {@code BE-worker-group}). On dispatch, the
 * broker extracts {@code workerType} from the envelope properties and finds the matching handler.
 */
public class InProcessMessageBroker {

    private static final Logger log = LoggerFactory.getLogger(InProcessMessageBroker.class);

    /** destination → ordered list of registered handlers */
    private final Map<String, List<RegisteredHandler>> handlers = new ConcurrentHashMap<>();

    /** taskKey → running Future (singleton enforcement + cancellation) */
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    record RegisteredHandler(String group, MessageHandler handler) {}

    // ------------------------------------------------------------------ registration

    /**
     * Register a handler for a destination + consumer group pair.
     * Multiple handlers can be registered for the same destination (one per worker type).
     */
    public void register(String destination, String group, MessageHandler handler) {
        log.info("InProcess: registering handler for destination='{}' group='{}'", destination, group);
        handlers.computeIfAbsent(destination, k -> new CopyOnWriteArrayList<>())
                .add(new RegisteredHandler(group, handler));
    }

    // ------------------------------------------------------------------ dispatch

    /**
     * Dispatch an envelope to the appropriate handler in a new virtual thread.
     *
     * <p>Routing priority:
     * <ol>
     *   <li>If envelope has {@code workerType} property: find handler whose group contains the type</li>
     *   <li>Fallback: first registered handler for the destination</li>
     * </ol>
     *
     * <p>Singleton: if {@code taskKey} is already running, the message is silently dropped.
     */
    public void dispatch(MessageEnvelope envelope) {
        List<RegisteredHandler> candidates = handlers.getOrDefault(envelope.destination(), List.of());
        if (candidates.isEmpty()) {
            log.warn("InProcess: no handler registered for destination='{}' — message dropped",
                    envelope.destination());
            return;
        }

        String taskKey = envelope.properties().getOrDefault("taskKey", envelope.messageId());

        // Singleton enforcement — equivalent to the distributed lock in Redis mode
        if (runningTasks.containsKey(taskKey)) {
            log.warn("InProcess: task '{}' already running — duplicate dropped", taskKey);
            return;
        }

        String workerType = envelope.properties().get("workerType");
        String workerProfile = envelope.properties().get("workerProfile");
        RegisteredHandler chosen = findHandler(candidates, workerType, workerProfile);
        if (chosen == null) {
            log.warn("InProcess: no matching handler for workerType='{}' workerProfile='{}' destination='{}'",
                    workerType, workerProfile, envelope.destination());
            return;
        }

        NoOpAcknowledgment ack = new NoOpAcknowledgment();
        String handlerGroup = chosen.group();
        MessageHandler handlerFn = chosen.handler();

        Future<?> future = executor.submit(() -> {
            log.debug("InProcess: starting task '{}' on handler group='{}'", taskKey, handlerGroup);
            try {
                handlerFn.handle(envelope.body(), ack);
            } catch (CancellationException e) {
                log.info("InProcess: task '{}' cancelled", taskKey);
            } catch (Exception e) {
                log.error("InProcess: task '{}' failed with exception", taskKey, e);
            } finally {
                runningTasks.remove(taskKey);
                log.debug("InProcess: task '{}' completed, removed from running map", taskKey);
            }
        });

        runningTasks.put(taskKey, future);
    }

    // ------------------------------------------------------------------ cancellation

    /**
     * Cancel a running task by sending {@link Thread#interrupt()} to its virtual thread.
     *
     * <p>The task will stop at the next interrupt check — either before the LLM call
     * (in {@code AbstractWorker.process()}) or before the next tool call
     * (in {@code PolicyEnforcingToolCallback.executeWithPolicy()}).
     *
     * @param taskKey the task to cancel
     * @return {@code true} if the task was found and interrupted, {@code false} if not found
     */
    public boolean cancel(String taskKey) {
        Future<?> future = runningTasks.get(taskKey);
        if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true); // mayInterruptIfRunning = true
            log.info("InProcess: cancel signal sent to task '{}' (interrupted={})", taskKey, cancelled);
            return cancelled;
        }
        log.debug("InProcess: cancel requested for task '{}' but not found in running map", taskKey);
        return false;
    }

    // ------------------------------------------------------------------ status

    /** Returns a snapshot of currently running task keys. */
    public List<String> getRunningTaskKeys() {
        return new ArrayList<>(runningTasks.keySet());
    }

    /** Returns the count of currently running tasks. */
    public int getRunningTaskCount() {
        return runningTasks.size();
    }

    // ------------------------------------------------------------------ internal

    /**
     * Find the best handler for the given workerType and workerProfile.
     *
     * <p>Matching priority:
     * <ol>
     *   <li>Exact match: group starts with "{type}-{profile}-" (e.g. "BE-be-java-worker-group")</li>
     *   <li>Type-only match: group starts with "{type}-" (first handler of the matching type)</li>
     *   <li>Fallback: first registered handler for the destination</li>
     * </ol>
     *
     * <p>In consolidated JVM mode, each worker registers with group
     * "{workerType}-{workerProfile}-worker-group". The handler's internal
     * {@code shouldProcess()} provides an additional safety check.
     */
    private RegisteredHandler findHandler(List<RegisteredHandler> candidates,
                                          String workerType, String workerProfile) {
        if (workerType != null && !workerType.isBlank()) {
            // 1. Try exact match: type + profile
            if (workerProfile != null && !workerProfile.isBlank()) {
                String exactPrefix = workerType + "-" + workerProfile + "-";
                var exact = candidates.stream()
                        .filter(h -> h.group().startsWith(exactPrefix))
                        .findFirst();
                if (exact.isPresent()) {
                    return exact.get();
                }
            }
            // 2. Fallback: type-only match (first handler of that type)
            String typePrefix = workerType + "-";
            var typeMatch = candidates.stream()
                    .filter(h -> h.group().startsWith(typePrefix))
                    .findFirst();
            if (typeMatch.isPresent()) {
                return typeMatch.get();
            }
        }
        // 3. Last resort: first handler
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    // ------------------------------------------------------------------ NoOpAck

    /**
     * No-op acknowledgment for in-process delivery.
     * In-process dispatch has at-most-once semantics by design — no retry on failure
     * (the exception is caught and logged, result is published as FAILED by the worker).
     */
    private static class NoOpAcknowledgment implements MessageAcknowledgment {
        @Override
        public void complete() { /* no-op: in-process, no external broker to notify */ }

        @Override
        public void reject(String reason) {
            /* no-op: failure is surfaced via AgentResult.success=false, not by re-queuing */
        }
    }
}
