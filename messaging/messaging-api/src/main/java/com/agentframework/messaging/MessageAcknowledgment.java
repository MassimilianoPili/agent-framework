package com.agentframework.messaging;

/**
 * Acknowledgment handle for a received message.
 * Each provider maps these operations to its native ack mechanism.
 */
public interface MessageAcknowledgment {

    /**
     * Acknowledge successful processing.
     * The message is removed from the queue/stream.
     */
    void complete();

    /**
     * Reject the message with a reason.
     * Depending on the provider, this may dead-letter the message.
     *
     * @param reason human-readable rejection reason
     */
    void reject(String reason);
}
