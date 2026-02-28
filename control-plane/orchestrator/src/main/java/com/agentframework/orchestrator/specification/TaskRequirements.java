package com.agentframework.orchestrator.specification;

import com.agentframework.orchestrator.domain.WorkerType;

import java.util.List;

/**
 * Requirements extracted from a task that must be matched against profile capabilities.
 *
 * @param taskKey      the task identifier
 * @param workerType   the expected worker type
 * @param targetPaths  file paths the task intends to write (may be empty if unknown)
 */
public record TaskRequirements(
        String taskKey,
        WorkerType workerType,
        List<String> targetPaths) {

    public TaskRequirements {
        targetPaths = targetPaths != null ? List.copyOf(targetPaths) : List.of();
    }
}
