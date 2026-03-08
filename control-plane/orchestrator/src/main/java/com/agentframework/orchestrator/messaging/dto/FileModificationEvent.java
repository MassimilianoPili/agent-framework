package com.agentframework.orchestrator.messaging.dto;

/**
 * G3: Mirrors the worker-sdk FileModificationEvent for deserialization from agent-results.
 */
public record FileModificationEvent(
    String filePath,
    String operation,
    String contentHashBefore,
    String contentHashAfter,
    String diffPreview
) {}
