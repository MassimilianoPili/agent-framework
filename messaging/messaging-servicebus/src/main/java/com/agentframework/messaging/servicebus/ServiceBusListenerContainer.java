package com.agentframework.messaging.servicebus;

import com.agentframework.messaging.MessageHandler;
import com.agentframework.messaging.MessageListenerContainer;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Azure Service Bus implementation of MessageListenerContainer.
 * Each subscription creates a ServiceBusProcessorClient targeting a topic + subscription.
 */
public class ServiceBusListenerContainer implements MessageListenerContainer {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusListenerContainer.class);

    private final String connectionString;
    private final int maxConcurrentCalls;
    private final List<SubscriptionInfo> pendingSubscriptions = new ArrayList<>();
    private final List<ServiceBusProcessorClient> processors = new ArrayList<>();

    public ServiceBusListenerContainer(String connectionString, int maxConcurrentCalls) {
        this.connectionString = connectionString;
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    @Override
    public void subscribe(String destination, String group, MessageHandler handler) {
        log.info("Registering Service Bus subscription: topic='{}', subscription='{}'", destination, group);
        pendingSubscriptions.add(new SubscriptionInfo(destination, group, handler));
    }

    @Override
    public void start() {
        for (var info : pendingSubscriptions) {
            var processor = new ServiceBusClientBuilder()
                    .connectionString(connectionString)
                    .processor()
                    .topicName(info.destination)
                    .subscriptionName(info.group)
                    .maxConcurrentCalls(maxConcurrentCalls)
                    .processMessage(context -> {
                        String body = context.getMessage().getBody().toString();
                        var ack = new ServiceBusAcknowledgment(context);
                        info.handler.handle(body, ack);
                    })
                    .processError(errorContext ->
                            log.error("Service Bus error on '{}': {}",
                                      info.destination, errorContext.getException().getMessage()))
                    .buildProcessorClient();

            processor.start();
            processors.add(processor);

            log.info("Started Service Bus processor for topic '{}', subscription '{}'",
                     info.destination, info.group);
        }
    }

    @Override
    public void stop() {
        for (var processor : processors) {
            processor.close();
        }
        processors.clear();
        log.info("Stopped {} Service Bus processor(s)", pendingSubscriptions.size());
    }

    private record SubscriptionInfo(String destination, String group, MessageHandler handler) {}
}
