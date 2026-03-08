package com.agentframework.worker.messaging;

import com.agentframework.messaging.MessageAcknowledgment;
import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.messaging.TaskLockService;
import com.agentframework.worker.AbstractWorker;
import com.agentframework.worker.config.WorkerProperties;
import com.agentframework.worker.dto.AgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Consumes AgentTask messages from the worker-type-specific topic.
 * Delegates to the AbstractWorker bean registered in this application context.
 *
 * One instance per worker application. The topic name comes from WorkerProperties.
 */
public class WorkerTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(WorkerTaskConsumer.class);

    private final MessageListenerContainer listenerContainer;
    private final AbstractWorker worker;
    private final ObjectMapper objectMapper;
    private final WorkerProperties properties;
    private final Optional<TaskLockService> taskLockService;

    public WorkerTaskConsumer(MessageListenerContainer listenerContainer,
                              AbstractWorker worker,
                              ObjectMapper objectMapper,
                              WorkerProperties properties,
                              Optional<TaskLockService> taskLockService) {
        this.listenerContainer = listenerContainer;
        this.worker = worker;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.taskLockService = taskLockService;
    }

    @PostConstruct
    public void start() {
        listenerContainer.subscribe(
                properties.getTaskTopic(),
                properties.getTaskSubscription(),
                this::handleMessage
        );
        listenerContainer.start();
        log.info("Task consumer started for worker type: {} on topic '{}' (lock={})",
                 worker.workerType(), properties.getTaskTopic(),
                 taskLockService.isPresent() ? "enabled" : "disabled");
    }

    @PreDestroy
    public void stop() {
        listenerContainer.stop();
        log.info("Task consumer stopped for worker type: {}", worker.workerType());
    }

    /**
     * Handles an incoming message containing an AgentTask.
     * Deserializes the message, filters by workerType/workerProfile, delegates to the worker.
     *
     * <p>Filtering is necessary because Redis Streams delivers all messages to every consumer
     * group on the same stream. Unlike Azure Service Bus (which supports server-side subscription
     * filters), Redis requires application-level filtering.</p>
     *
     * <p>When a {@link TaskLockService} is available, acquires a distributed lock before
     * processing. If the lock cannot be acquired (another instance is already processing this
     * task), the message is acknowledged without processing — preventing double processing
     * when a worker restarts and reclaims its PEL entries.</p>
     */
    private void handleMessage(String body, MessageAcknowledgment ack) {
        try {
            AgentTask task = objectMapper.readValue(body, AgentTask.class);

            if (!shouldProcess(task)) {
                log.debug("Skipping task {} (type={}, profile={}) — not for this worker (type={}, profile={})",
                          task.taskKey(), task.workerType(), task.workerProfile(),
                          worker.workerType(), worker.workerProfile());
                ack.complete();
                return;
            }

            if (taskLockService.isPresent() && !taskLockService.get().acquire(task.taskKey())) {
                log.warn("Task {} already locked by another consumer — skipping to prevent double processing",
                         task.taskKey());
                ack.complete();
                return;
            }

            log.info("Received task {} for worker {} (plan={})",
                     task.taskKey(), worker.workerType(), task.planId());

            try {
                worker.process(task);
                ack.complete();
            } finally {
                taskLockService.ifPresent(ls -> {
                    try {
                        ls.release(task.taskKey());
                    } catch (Exception ex) {
                        log.warn("Failed to release task lock for {}: {}", task.taskKey(), ex.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            log.error("Failed to handle AgentTask message: {}", e.getMessage(), e);
            ack.reject(e.getMessage());
        }
    }

    /**
     * Determines whether this worker should process the given task.
     *
     * <p>Rules:</p>
     * <ol>
     *   <li>workerType must match (e.g., FE task → FE worker, AI_TASK → AI_TASK worker)</li>
     *   <li>If both task and worker have a profile, they must match (e.g., fe-vanillajs → fe-vanillajs)</li>
     * </ol>
     */
    private boolean shouldProcess(AgentTask task) {
        // Type must match
        if (!worker.workerType().equals(task.workerType())) {
            return false;
        }

        // If both sides specify a profile, they must agree
        String taskProfile = task.workerProfile();
        String myProfile = worker.workerProfile();
        if (taskProfile != null && !taskProfile.isBlank()
                && myProfile != null && !myProfile.isBlank()
                && !taskProfile.equals(myProfile)) {
            return false;
        }

        return true;
    }
}
