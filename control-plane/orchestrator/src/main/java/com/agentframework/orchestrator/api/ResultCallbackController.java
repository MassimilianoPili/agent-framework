package com.agentframework.orchestrator.api;

import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.inprocess.InProcessMessageBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Receives task results from remote worker JVMs and injects them into the
 * in-process broker for unified consumption by {@code AgentResultConsumer}.
 *
 * <p>Active only in hybrid messaging mode. Remote workers call
 * {@code POST /internal/results} with the {@code AgentResult} JSON body.
 * The controller wraps it in a {@link MessageEnvelope} and dispatches it
 * to the local broker's {@code agent-results} destination.
 *
 * <p>This unifies the result flow: both in-process and remote results
 * arrive at the same handler via the {@code InProcessMessageListenerContainer}.
 */
@RestController
@ConditionalOnProperty(name = "messaging.provider", havingValue = "hybrid")
@ConditionalOnBean(InProcessMessageBroker.class)
public class ResultCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ResultCallbackController.class);

    private static final String RESULTS_DESTINATION = "agent-results";

    private final InProcessMessageBroker broker;

    public ResultCallbackController(InProcessMessageBroker broker) {
        this.broker = broker;
    }

    @PostMapping("/internal/results")
    public ResponseEntity<Void> receiveResult(@RequestBody String body) {
        log.debug("Received result callback from remote worker");

        MessageEnvelope envelope = new MessageEnvelope(
                UUID.randomUUID().toString(),
                RESULTS_DESTINATION,
                body,
                Map.of()
        );

        broker.dispatch(envelope);

        return ResponseEntity.accepted().build();
    }
}
