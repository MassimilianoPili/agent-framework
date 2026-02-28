package com.agentframework.orchestrator.event;

import java.util.UUID;

/**
 * Published when a plan item completes (successfully or with failure).
 */
public record PlanItemCompletedEvent(UUID planId, UUID itemId, String taskKey, boolean success, long durationMs) {}
