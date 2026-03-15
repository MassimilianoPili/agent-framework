package com.agentframework.orchestrator.analytics.mast;

import java.util.Map;

/**
 * Multi-Agent System failure Taxonomy (MAST).
 *
 * <p>Defines a structured classification of failures in multi-agent orchestration
 * systems. The taxonomy has 3 top-level categories (FC1–FC3) with 14 specific
 * failure modes (FM1–FM14).</p>
 *
 * <h3>Design rationale</h3>
 * <p>Generic retry/backoff treats all failures the same, but different failure modes
 * require fundamentally different recovery strategies. A specification ambiguity (FM1)
 * needs human clarification, not retry. A coordination deadlock (FM6) needs
 * cycle-breaking, not re-execution. This taxonomy enables targeted self-healing.</p>
 *
 * @see MastClassifierService
 * @see SelfHealingRouter
 */
public final class MastTaxonomy {

    private MastTaxonomy() {}

    /**
     * Top-level failure categories.
     */
    public enum FailureCategory {
        /** Failures originating from the task specification itself. */
        FC1_SPECIFICATION("Specification Failures"),
        /** Failures arising from inter-agent communication and coordination. */
        FC2_INTER_AGENT("Inter-Agent Failures"),
        /** Emergent system-level failures not attributable to any single agent. */
        FC3_EMERGENT("Emergent Failures");

        private final String description;

        FailureCategory(String description) {
            this.description = description;
        }

        public String description() { return description; }
    }

    /**
     * Specific failure modes within each category.
     */
    public enum FailureMode {
        // FC1: Specification Failures
        FM1_AMBIGUOUS_SPEC(FailureCategory.FC1_SPECIFICATION,
                "Ambiguous specification",
                "Spec is too vague or has multiple valid interpretations"),
        FM2_INCOMPLETE_REQ(FailureCategory.FC1_SPECIFICATION,
                "Incomplete requirements",
                "Spec lacks critical information needed for implementation"),
        FM3_CONFLICTING_CONSTRAINTS(FailureCategory.FC1_SPECIFICATION,
                "Conflicting constraints",
                "Spec contains contradictory requirements"),
        FM4_SCOPE_CREEP(FailureCategory.FC1_SPECIFICATION,
                "Scope creep",
                "Spec drift during execution — task grew beyond original scope"),

        // FC2: Inter-Agent Failures
        FM5_COMMUNICATION_BREAKDOWN(FailureCategory.FC2_INTER_AGENT,
                "Communication breakdown",
                "Worker reports missing_context — required information not available"),
        FM6_COORDINATION_DEADLOCK(FailureCategory.FC2_INTER_AGENT,
                "Coordination deadlock",
                "Circular dependencies prevent any task from progressing"),
        FM7_RESOURCE_CONTENTION(FailureCategory.FC2_INTER_AGENT,
                "Resource contention",
                "Token budget exhausted or concurrent file access conflict"),
        FM8_PROTOCOL_VIOLATION(FailureCategory.FC2_INTER_AGENT,
                "Protocol violation",
                "Worker returned unexpected message format or invalid result JSON"),
        FM9_TRUST_DEGRADATION(FailureCategory.FC2_INTER_AGENT,
                "Trust degradation",
                "Worker ELO collapsed below threshold — unreliable outputs"),

        // FC3: Emergent Failures
        FM10_CASCADING_FAILURE(FailureCategory.FC3_EMERGENT,
                "Cascading failure",
                "One task's failure propagates through dependency chain"),
        FM11_OSCILLATION(FailureCategory.FC3_EMERGENT,
                "Oscillation",
                "Ralph-Loop or self-refine non-convergence (flip-flopping)"),
        FM12_PERFORMANCE_DEGRADATION(FailureCategory.FC3_EMERGENT,
                "Performance degradation",
                "Worker drift, model quality decline, or increasing latency"),
        FM13_PARTIAL_COMPLETION(FailureCategory.FC3_EMERGENT,
                "Partial completion",
                "Some tasks completed but plan is stuck with remaining failures"),
        FM14_RECOVERY_FAILURE(FailureCategory.FC3_EMERGENT,
                "Recovery failure",
                "Compensation or retry itself failed — meta-failure");

        private final FailureCategory category;
        private final String name;
        private final String description;

        FailureMode(FailureCategory category, String name, String description) {
            this.category = category;
            this.name = name;
            this.description = description;
        }

        public FailureCategory category() { return category; }
        public String modeName() { return name; }
        public String description() { return description; }
    }

    /**
     * Classification result for a failed task.
     *
     * @param mode        the identified failure mode
     * @param category    the top-level failure category
     * @param confidence  classifier confidence [0.0, 1.0]
     * @param evidence    human-readable evidence supporting the classification
     * @param metadata    additional diagnostic data (e.g., error patterns, metrics)
     */
    public record FailureClassification(
            FailureMode mode,
            FailureCategory category,
            double confidence,
            String evidence,
            Map<String, Object> metadata
    ) {
        public FailureClassification(FailureMode mode, double confidence, String evidence) {
            this(mode, mode.category(), confidence, evidence, Map.of());
        }

        public FailureClassification(FailureMode mode, double confidence,
                                      String evidence, Map<String, Object> metadata) {
            this(mode, mode.category(), confidence, evidence, metadata);
        }
    }
}
