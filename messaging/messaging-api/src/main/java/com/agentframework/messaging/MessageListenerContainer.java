package com.agentframework.messaging;

/**
 * Manages subscriptions and message consumption lifecycle.
 * Each provider implements this to wrap its native consumer mechanism.
 */
public interface MessageListenerContainer {

    /**
     * Register a subscription for a destination.
     *
     * @param destination the topic/stream/queue name
     * @param group       consumer group or subscription name
     * @param handler     callback for received messages
     */
    void subscribe(String destination, String group, MessageHandler handler);

    /**
     * Start consuming messages for all registered subscriptions.
     */
    void start();

    /**
     * Stop consuming messages and release resources.
     */
    void stop();
}
