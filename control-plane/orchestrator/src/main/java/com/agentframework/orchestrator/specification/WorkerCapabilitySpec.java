package com.agentframework.orchestrator.specification;

/**
 * Specification pattern interface for validating task-worker compatibility.
 *
 * <p>Each spec encapsulates a single compatibility rule. Specs can be combined
 * with {@link CompositeSpec} for AND semantics.</p>
 */
@FunctionalInterface
public interface WorkerCapabilitySpec {

    /**
     * Returns true if the profile capabilities satisfy the task requirements.
     */
    boolean isSatisfiedBy(ProfileCapabilities profile, TaskRequirements task);
}
