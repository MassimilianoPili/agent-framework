package com.agentframework.orchestrator.lifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Multi-Plan Project Lifecycle Manager.
 *
 * <pre>
 * lifecycle:
 *   enabled: false
 *   decomposition:
 *     max-epics-per-project: 20
 *     max-plans-per-epic: 10
 *   saga:
 *     compensate-on-failure: true
 *     max-compensation-retries: 2
 *     compensation-timeout-minutes: 30
 *   sprint:
 *     duration-hours: 24
 *     max-items-per-sprint: 15
 *     auto-scope-enabled: true
 *   release:
 *     require-stabilization: true
 *     stabilization-min-plans: 2
 * </pre>
 *
 * @param decomposition epic decomposition limits
 * @param saga          saga pattern configuration
 * @param sprint        sprint iteration parameters
 * @param release       release management criteria
 */
@ConfigurationProperties(prefix = "lifecycle")
public record LifecycleConfig(
        DecompositionConfig decomposition,
        SagaConfig saga,
        SprintConfig sprint,
        ReleaseConfig release
) {
    public LifecycleConfig {
        if (decomposition == null) decomposition = new DecompositionConfig(20, 10);
        if (saga == null) saga = new SagaConfig(true, 2, 30);
        if (sprint == null) sprint = new SprintConfig(24, 15, true);
        if (release == null) release = new ReleaseConfig(true, 2);
    }

    /**
     * @param maxEpicsPerProject  max epics in a single project
     * @param maxPlansPerEpic     max plans generated from a single epic
     */
    public record DecompositionConfig(int maxEpicsPerProject, int maxPlansPerEpic) {
        public DecompositionConfig {
            if (maxEpicsPerProject <= 0) maxEpicsPerProject = 20;
            if (maxPlansPerEpic <= 0) maxPlansPerEpic = 10;
        }
    }

    /**
     * @param compensateOnFailure       trigger compensation on plan failure
     * @param maxCompensationRetries    retries for compensation plans
     * @param compensationTimeoutMinutes timeout for compensation execution
     */
    public record SagaConfig(boolean compensateOnFailure, int maxCompensationRetries,
                              int compensationTimeoutMinutes) {
        public SagaConfig {
            if (maxCompensationRetries < 0) maxCompensationRetries = 2;
            if (compensationTimeoutMinutes <= 0) compensationTimeoutMinutes = 30;
        }
    }

    /**
     * @param durationHours      sprint time-box duration
     * @param maxItemsPerSprint  capacity cap per sprint
     * @param autoScopeEnabled   auto-drop low priority items if over capacity
     */
    public record SprintConfig(int durationHours, int maxItemsPerSprint, boolean autoScopeEnabled) {
        public SprintConfig {
            if (durationHours <= 0) durationHours = 24;
            if (maxItemsPerSprint <= 0) maxItemsPerSprint = 15;
        }
    }

    /**
     * @param requireStabilization   require STABILIZING phase before release
     * @param stabilizationMinPlans  min plans that must pass in stabilization
     */
    public record ReleaseConfig(boolean requireStabilization, int stabilizationMinPlans) {
        public ReleaseConfig {
            if (stabilizationMinPlans <= 0) stabilizationMinPlans = 2;
        }
    }
}
