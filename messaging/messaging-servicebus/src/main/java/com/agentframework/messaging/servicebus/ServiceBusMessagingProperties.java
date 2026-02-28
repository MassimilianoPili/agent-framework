package com.agentframework.messaging.servicebus;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Azure Service Bus messaging.
 */
@ConfigurationProperties(prefix = "azure.servicebus")
public class ServiceBusMessagingProperties {

    /** Service Bus connection string. */
    private String connectionString;

    /** Max concurrent calls for the processor. */
    private int maxConcurrentCalls = 1;

    public String getConnectionString() { return connectionString; }
    public void setConnectionString(String connectionString) { this.connectionString = connectionString; }

    public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
    public void setMaxConcurrentCalls(int maxConcurrentCalls) { this.maxConcurrentCalls = maxConcurrentCalls; }
}
