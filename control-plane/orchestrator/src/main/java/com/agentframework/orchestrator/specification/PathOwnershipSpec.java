package com.agentframework.orchestrator.specification;

/**
 * Validates that the profile owns all target paths the task intends to write to.
 * If the task has no target paths, this spec is trivially satisfied.
 */
public class PathOwnershipSpec implements WorkerCapabilitySpec {

    @Override
    public boolean isSatisfiedBy(ProfileCapabilities profile, TaskRequirements task) {
        if (task.targetPaths().isEmpty()) {
            return true;
        }

        for (String targetPath : task.targetPaths()) {
            boolean covered = profile.ownsPaths().stream()
                    .anyMatch(targetPath::startsWith);
            if (!covered) {
                return false;
            }
        }
        return true;
    }
}
