package com.agentframework.orchestrator.event;

import java.util.UUID;

/**
 * Published when a plan item is dispatched to a worker.
 */
public record PlanItemDispatchedEvent(UUID planId, UUID itemId, String taskKey, String workerProfile) {}
