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

@Component
public class AgentResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(AgentResultConsumer.class);

    private final MessageListenerContainer listenerContainer;
    private final OrchestrationService orchestrationService;
    private final ObjectMapper objectMapper;

    @Value("${messaging.agent-results.topic:agent-results}")
    private String resultsTopic;

    @Value("${messaging.agent-results.subscription:orchestrator-group}")
    private String resultsSubscription;

    public AgentResultConsumer(MessageListenerContainer listenerContainer,
                               OrchestrationService orchestrationService,
                               ObjectMapper objectMapper) {
        this.listenerContainer = listenerContainer;
        this.orchestrationService = orchestrationService;
        this.objectMapper = objectMapper;
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
     * Delegates to OrchestrationService.onTaskCompleted() which handles:
     * - Updating item status in DB
     * - Dispatching newly unblocked items
     * - Checking plan completion
     */
    private void handleMessage(String body, MessageAcknowledgment ack) {
        try {
            AgentResult result = objectMapper.readValue(body, AgentResult.class);

            log.info("Received AgentResult for task {} (plan={}, success={})",
                     result.taskKey(), result.planId(), result.success());

            orchestrationService.onTaskCompleted(result);
            ack.complete();
        } catch (Exception e) {
            log.error("Failed to process AgentResult message: {}", e.getMessage(), e);
            ack.reject(e.getMessage());
        }
    }
}
