package com.agentframework.orchestrator.event;

import java.util.UUID;

/**
 * Published when a new plan is created and transitions to RUNNING.
 */
public record PlanCreatedEvent(UUID planId, String spec, int itemCount) {}
