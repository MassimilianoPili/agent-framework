package com.agentframework.messaging.inprocess;

import com.agentframework.messaging.MessageHandler;
import com.agentframework.messaging.MessageListenerContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process implementation of {@link MessageListenerContainer}.
 *
 * <p>Unlike the Redis or JMS implementations, there is no background polling thread to start.
 * Message delivery is synchronous (from the caller's virtual thread) via the shared
 * {@link InProcessMessageBroker}. Handlers are registered eagerly on {@link #subscribe}.
 *
 * <p>{@link #start()} and {@link #stop()} are no-ops — the broker is always ready.
 */
public class InProcessMessageListenerContainer implements MessageListenerContainer {

    private static final Logger log = LoggerFactory.getLogger(InProcessMessageListenerContainer.class);

    private final InProcessMessageBroker broker;
    private volatile boolean running = false;

    public InProcessMessageListenerContainer(InProcessMessageBroker broker) {
        this.broker = broker;
    }

    @Override
    public void subscribe(String destination, String group, MessageHandler handler) {
        broker.register(destination, group, handler);
        log.info("InProcess: subscribed to destination='{}' group='{}'", destination, group);
    }

    /** No-op: the broker is ready as soon as it is constructed. */
    @Override
    public void start() {
        running = true;
        log.info("InProcess: MessageListenerContainer started (broker always ready)");
    }

    /** No-op: no background threads to shut down. */
    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
