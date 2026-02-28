package com.agentframework.orchestrator.event;

import com.agentframework.orchestrator.domain.PlanStatus;

import java.util.UUID;

/**
 * Published when all items in a plan are terminal and the plan is complete.
 */
public record PlanCompletedEvent(UUID planId, PlanStatus status, int totalItems, long failedItems) {}
