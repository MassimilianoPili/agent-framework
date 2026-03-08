package com.agentframework.messaging.hybrid;

import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageSender;
import com.agentframework.messaging.inprocess.InProcessMessageBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hybrid {@link MessageSender} that routes messages to either the in-process broker
 * or a remote worker JVM based on the {@code workerType} property.
 *
 * <p>Worker types listed in {@link HybridMessagingProperties#getRemoteTypes()} are
 * dispatched via REST to the corresponding remote JVM. All other types (lightweight
 * managers) are dispatched locally through the {@link InProcessMessageBroker}.
 *
 * <p>Non-task messages (e.g., results, events) are always dispatched in-process
 * since the orchestrator's result consumer listens on the local broker.
 */
public class HybridMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(HybridMessageSender.class);

    private static final String TASK_DESTINATION = "agent-tasks";

    private final InProcessMessageBroker inProcessBroker;
    private final RemoteWorkerClient remoteClient;
    private final HybridMessagingProperties properties;

    public HybridMessageSender(InProcessMessageBroker inProcessBroker,
                                RemoteWorkerClient remoteClient,
                                HybridMessagingProperties properties) {
        this.inProcessBroker = inProcessBroker;
        this.remoteClient = remoteClient;
        this.properties = properties;
    }

    @Override
    public void send(MessageEnvelope envelope) {
        String workerType = envelope.properties().get("workerType");

        // Only route task dispatches to remote workers.
        // Results, events, and other messages always go to the in-process broker.
        if (TASK_DESTINATION.equals(envelope.destination())
                && workerType != null
                && properties.getRemoteTypes().contains(workerType)) {
            log.debug("Routing task to remote worker: type={}, taskKey={}",
                    workerType, envelope.properties().get("taskKey"));
            remoteClient.dispatch(envelope);
        } else {
            inProcessBroker.dispatch(envelope);
        }
    }
}
