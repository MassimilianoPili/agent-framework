package com.agentframework.messaging.inprocess;

import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageSender;

/**
 * In-process implementation of {@link MessageSender}.
 * Delegates to {@link InProcessMessageBroker} instead of writing to a Redis stream or JMS queue.
 */
public class InProcessMessageSender implements MessageSender {

    private final InProcessMessageBroker broker;

    public InProcessMessageSender(InProcessMessageBroker broker) {
        this.broker = broker;
    }

    @Override
    public void send(MessageEnvelope envelope) {
        broker.dispatch(envelope);
    }
}
