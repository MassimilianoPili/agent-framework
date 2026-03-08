package com.agentframework.worker.runtime;

import com.agentframework.worker.AbstractWorker;
import com.agentframework.worker.dto.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes tasks on virtual threads with singleton enforcement and cancellation support.
 *
 * <p>Same patterns as {@code InProcessMessageBroker}: virtual thread executor,
 * {@code ConcurrentHashMap} for running tasks, {@code Future.cancel(true)} for interrupts.
 *
 * <p>The dispatcher finds the appropriate {@link AbstractWorker} bean by matching
 * {@code workerType} and {@code workerProfile} (same logic as
 * {@code InProcessWorkerRegistrar.shouldProcess()}).
 */
@Component
public class LocalTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalTaskDispatcher.class);

    private final List<AbstractWorker> workers;
    private final ConcurrentHashMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LocalTaskDispatcher(ObjectProvider<List<AbstractWorker>> workersProvider) {
        this.workers = workersProvider.getIfAvailable(List::of);
        log.info("LocalTaskDispatcher initialized with {} worker bean(s)", this.workers.size());
    }

    /**
     * Dispatches a task to the appropriate worker bean on a virtual thread.
     *
     * @return true if the task was accepted, false if a task with the same key is already running
     */
    public boolean dispatch(AgentTask task) {
        String taskKey = task.taskKey();

        // Singleton enforcement: reject duplicate task keys
        if (runningTasks.containsKey(taskKey)) {
            log.warn("Task '{}' already running — rejecting duplicate dispatch", taskKey);
            return false;
        }

        AbstractWorker worker = findWorker(task);
        if (worker == null) {
            log.error("No worker found for type={} profile={}", task.workerType(), task.workerProfile());
            return false;
        }

        Future<?> future = executor.submit(() -> {
            try {
                log.info("Processing task '{}' with worker type={} profile={}",
                        taskKey, worker.workerType(), worker.workerProfile());
                worker.process(task);
            } catch (Exception e) {
                log.error("Task '{}' failed: {}", taskKey, e.getMessage(), e);
            } finally {
                runningTasks.remove(taskKey);
                log.debug("Task '{}' completed, removed from running map", taskKey);
            }
        });

        runningTasks.put(taskKey, future);
        return true;
    }

    /**
     * Cancels a running task by sending an interrupt to its virtual thread.
     *
     * @return true if the task was found and interrupted
     */
    public boolean cancel(String taskKey) {
        Future<?> future = runningTasks.get(taskKey);
        if (future == null) {
            return false;
        }
        boolean cancelled = future.cancel(true);
        if (cancelled) {
            runningTasks.remove(taskKey);
            log.info("Cancelled task '{}'", taskKey);
        }
        return cancelled;
    }

    /**
     * Returns the set of currently running task keys.
     */
    public Set<String> getRunningTaskKeys() {
        return Set.copyOf(runningTasks.keySet());
    }

    /**
     * Returns the number of currently running tasks.
     */
    public int getRunningTaskCount() {
        return runningTasks.size();
    }

    private AbstractWorker findWorker(AgentTask task) {
        String taskType = task.workerType();
        String taskProfile = task.workerProfile();

        // Try exact match (type + profile) first
        for (AbstractWorker w : workers) {
            if (w.workerType().equals(taskType)
                    && taskProfile != null && !taskProfile.isBlank()
                    && taskProfile.equals(w.workerProfile())) {
                return w;
            }
        }

        // Fall back to type-only match
        for (AbstractWorker w : workers) {
            if (w.workerType().equals(taskType)) {
                return w;
            }
        }

        return null;
    }
}
