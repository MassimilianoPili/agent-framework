package com.agentframework.messaging;

/**
 * Sends messages to a destination (topic, stream, queue).
 * Each messaging provider implements this interface.
 */
public interface MessageSender {

    /**
     * Send a message envelope to its destination.
     *
     * @param envelope the message to send
     */
    void send(MessageEnvelope envelope);
}
