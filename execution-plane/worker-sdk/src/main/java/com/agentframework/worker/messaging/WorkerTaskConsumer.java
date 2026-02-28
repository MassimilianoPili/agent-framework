package com.agentframework.worker.messaging;

import com.agentframework.messaging.MessageAcknowledgment;
import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.worker.AbstractWorker;
import com.agentframework.worker.config.WorkerProperties;
import com.agentframework.worker.dto.AgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public WorkerTaskConsumer(MessageListenerContainer listenerContainer,
                              AbstractWorker worker,
                              ObjectMapper objectMapper,
                              WorkerProperties properties) {
        this.listenerContainer = listenerContainer;
        this.worker = worker;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        listenerContainer.subscribe(
                properties.getTaskTopic(),
                properties.getTaskSubscription(),
                this::handleMessage
        );
        listenerContainer.start();
        log.info("Task consumer started for worker type: {} on topic '{}'",
                 worker.workerType(), properties.getTaskTopic());
    }

    @PreDestroy
    public void stop() {
        listenerContainer.stop();
        log.info("Task consumer stopped for worker type: {}", worker.workerType());
    }

    /**
     * Handles an incoming message containing an AgentTask.
     * Deserializes the message, delegates to the worker, and completes/rejects.
     */
    private void handleMessage(String body, MessageAcknowledgment ack) {
        try {
            AgentTask task = objectMapper.readValue(body, AgentTask.class);

            log.info("Received task {} for worker {} (plan={})",
                     task.taskKey(), worker.workerType(), task.planId());

            worker.process(task);
            ack.complete();

        } catch (Exception e) {
            log.error("Failed to handle AgentTask message: {}", e.getMessage(), e);
            ack.reject(e.getMessage());
        }
    }
}
