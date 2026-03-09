package com.agentframework.common.federation;

import java.util.UUID;

/**
 * Lightweight transport DTO describing a task for federated routing decisions.
 *
 * <p>Contains the minimum subset of {@code AgentTask} fields needed for a
 * {@link FederationTaskRouter} to decide which server should execute the task.
 * The full task payload is transmitted separately via {@code dispatchRemote()}.</p>
 *
 * <p>{@code workerType} is a {@code String} (not an enum) for cross-server
 * portability: different servers may support different worker type sets.</p>
 *
 * @param planId        the plan this task belongs to
 * @param itemId        the plan item identifier
 * @param taskKey       short task key (e.g. "BE-001")
 * @param workerType    worker type name as string for portability
 * @param workerProfile optional worker profile (may be null)
 * @param title         human-readable task title
 */
public record FederatedTaskDescriptor(
        UUID planId,
        UUID itemId,
        String taskKey,
        String workerType,
        String workerProfile,
        String title
) {}
