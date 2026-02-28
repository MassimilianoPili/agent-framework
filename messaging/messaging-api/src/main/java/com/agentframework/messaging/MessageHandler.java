package com.agentframework.messaging;

/**
 * Callback for processing received messages.
 * The handler receives the raw JSON body and an acknowledgment handle.
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Handle an incoming message.
     *
     * @param body the message body (JSON string)
     * @param ack  acknowledgment handle to complete or reject the message
     */
    void handle(String body, MessageAcknowledgment ack);
}
