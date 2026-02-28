package com.agentframework.messaging.servicebus;

import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageSender;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Azure Service Bus implementation of MessageSender.
 * Caches ServiceBusSenderClient instances per destination (topic name).
 */
public class ServiceBusMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusMessageSender.class);

    private final String connectionString;
    private final ConcurrentMap<String, ServiceBusSenderClient> senders = new ConcurrentHashMap<>();

    public ServiceBusMessageSender(String connectionString) {
        this.connectionString = connectionString;
    }

    @Override
    public void send(MessageEnvelope envelope) {
        var sender = senders.computeIfAbsent(envelope.destination(), this::createSender);

        ServiceBusMessage message = new ServiceBusMessage(envelope.body());

        if (envelope.messageId() != null) {
            message.setMessageId(envelope.messageId());
        }

        envelope.properties().forEach((key, value) ->
                message.getApplicationProperties().put(key, value));

        sender.sendMessage(message);
        log.debug("Sent Service Bus message to '{}', id={}", envelope.destination(), envelope.messageId());
    }

    private ServiceBusSenderClient createSender(String topicName) {
        log.info("Creating Service Bus sender for topic: {}", topicName);
        return new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .topicName(topicName)
                .buildClient();
    }

    /**
     * Close all cached senders. Called during shutdown.
     */
    public void close() {
        senders.values().forEach(ServiceBusSenderClient::close);
        senders.clear();
    }
}
