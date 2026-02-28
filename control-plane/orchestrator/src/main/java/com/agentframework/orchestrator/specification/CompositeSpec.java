package com.agentframework.orchestrator.specification;

import java.util.List;

/**
 * Combines multiple {@link WorkerCapabilitySpec} instances with AND semantics.
 * All specs must be satisfied for the composite to pass.
 */
public class CompositeSpec implements WorkerCapabilitySpec {

    private final List<WorkerCapabilitySpec> specs;

    public CompositeSpec(WorkerCapabilitySpec... specs) {
        this.specs = List.of(specs);
    }

    @Override
    public boolean isSatisfiedBy(ProfileCapabilities profile, TaskRequirements task) {
        return specs.stream().allMatch(s -> s.isSatisfiedBy(profile, task));
    }
}
