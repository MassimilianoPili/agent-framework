package com.agentframework.orchestrator.specification;

/**
 * Validates that the profile's worker type matches the task's expected type.
 * This is the most basic capability check — a BE task must go to a BE profile.
 */
public class ToolAvailabilitySpec implements WorkerCapabilitySpec {

    @Override
    public boolean isSatisfiedBy(ProfileCapabilities profile, TaskRequirements task) {
        return profile.workerType().equals(task.workerType().name());
    }
}
