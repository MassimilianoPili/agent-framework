package com.agentframework.worker.messaging;

import com.agentframework.common.crypto.SignedResultEnvelope;
import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageSender;
import com.agentframework.worker.crypto.WorkerSigningService;
import com.agentframework.worker.dto.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Publishes AgentResult messages to the results topic.
 * The orchestrator's AgentResultConsumer picks these up.
 *
 * <p>When a {@link WorkerSigningService} is present and enabled (#31),
 * the result is wrapped in a {@link SignedResultEnvelope} with Ed25519
 * signature before publishing. The orchestrator verifies the signature
 * on receipt. When signing is disabled, raw {@code AgentResult} JSON
 * is published for backward compatibility.</p>
 */
public class WorkerResultProducer {

    private static final Logger log = LoggerFactory.getLogger(WorkerResultProducer.class);

    private final MessageSender sender;
    private final ObjectMapper objectMapper;
    private final String resultsTopic;
    private final WorkerSigningService signingService;

    public WorkerResultProducer(MessageSender sender, ObjectMapper objectMapper,
                                String resultsTopic, WorkerSigningService signingService) {
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.resultsTopic = resultsTopic;
        this.signingService = signingService;
    }

    public void publish(AgentResult result) {
        try {
            String body;

            if (signingService != null && signingService.isEnabled()) {
                String resultJson = objectMapper.writeValueAsString(result);
                SignedResultEnvelope envelope = signingService.sign(resultJson);
                body = objectMapper.writeValueAsString(envelope);
                log.debug("Signed AgentResult for task {} (pubKey={}…)",
                        result.taskKey(),
                        signingService.getPublicKeyBase64().substring(0, 20));
            } else {
                body = objectMapper.writeValueAsString(result);
            }

            sender.send(new MessageEnvelope(
                    result.itemId().toString(),
                    resultsTopic,
                    body,
                    Map.of("planId", result.planId().toString(),
                           "taskKey", result.taskKey(),
                           "success", String.valueOf(result.success()))
            ));

            log.info("Published AgentResult for task {} (plan={}, success={}, signed={})",
                     result.taskKey(), result.planId(), result.success(),
                     signingService != null && signingService.isEnabled());
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish AgentResult for task: " + result.taskKey(), e);
        }
    }
}
