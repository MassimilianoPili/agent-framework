package com.agentframework.orchestrator.messaging;

import com.agentframework.messaging.MessageAcknowledgment;
import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.orchestration.OrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AgentResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(AgentResultConsumer.class);

    private final MessageListenerContainer listenerContainer;
    private final OrchestrationService orchestrationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${messaging.agent-results.topic:agent-results}")
    private String resultsTopic;

    @Value("${messaging.agent-results.subscription:orchestrator-group}")
    private String resultsSubscription;

    public AgentResultConsumer(MessageListenerContainer listenerContainer,
                               OrchestrationService orchestrationService,
                               ObjectMapper objectMapper,
                               TransactionTemplate transactionTemplate) {
        this.listenerContainer = listenerContainer;
        this.orchestrationService = orchestrationService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void start() {
        listenerContainer.subscribe(resultsTopic, resultsSubscription, this::handleMessage);
        listenerContainer.start();
        log.info("AgentResult consumer started — listening on '{}'", resultsTopic);
    }

    @PreDestroy
    public void stop() {
        listenerContainer.stop();
        log.info("AgentResult consumer stopped");
    }

    /**
     * Processes a received AgentResult message.
     *
     * <p>Uses an explicit TransactionTemplate so that the message ACK is registered
     * as an {@code afterCommit} callback. This guarantees:
     * <ul>
     *   <li>On successful commit → ACK (message consumed)</li>
     *   <li>On rollback → no ACK → Redis redelivers the message</li>
     *   <li>If ACK itself fails after commit → idempotency guard in onTaskCompleted
     *       skips the duplicate on redelivery</li>
     * </ul>
     */
    private void handleMessage(String body, MessageAcknowledgment ack) {
        try {
            AgentResult result = objectMapper.readValue(body, AgentResult.class);

            log.info("Received AgentResult for task {} (plan={}, success={})",
                     result.taskKey(), result.planId(), result.success());

            transactionTemplate.executeWithoutResult(status -> {
                orchestrationService.onTaskCompleted(result);

                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                ack.complete();
                            }
                        });
            });
        } catch (Exception e) {
            log.error("Failed to process AgentResult message: {}", e.getMessage(), e);
            ack.reject(e.getMessage());
        }
    }
}
