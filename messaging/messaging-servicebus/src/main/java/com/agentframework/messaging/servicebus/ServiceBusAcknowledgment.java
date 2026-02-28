package com.agentframework.messaging.servicebus;

import com.agentframework.messaging.MessageAcknowledgment;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Service Bus acknowledgment.
 * complete() = context.complete() (removes from subscription).
 * reject() = context.deadLetter() (moves to DLQ with reason).
 */
public class ServiceBusAcknowledgment implements MessageAcknowledgment {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusAcknowledgment.class);

    private final ServiceBusReceivedMessageContext context;

    public ServiceBusAcknowledgment(ServiceBusReceivedMessageContext context) {
        this.context = context;
    }

    @Override
    public void complete() {
        context.complete();
    }

    @Override
    public void reject(String reason) {
        log.warn("Dead-lettering Service Bus message: {}", reason);
        context.deadLetter(
                new DeadLetterOptions()
                        .setDeadLetterReason("ProcessingException")
                        .setDeadLetterErrorDescription(reason != null ? reason : "unknown")
        );
    }
}
