package com.agentframework.messaging;

import java.util.Map;

/**
 * Transport-agnostic message envelope.
 * Carries the payload and metadata across any messaging provider.
 */
public record MessageEnvelope(
        String messageId,
        String destination,
        String body,
        Map<String, String> properties
) {
    public MessageEnvelope {
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("destination must not be blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        if (properties == null) {
            properties = Map.of();
        }
    }
}
