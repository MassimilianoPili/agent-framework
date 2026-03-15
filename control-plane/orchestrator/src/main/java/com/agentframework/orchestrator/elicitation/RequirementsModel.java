package com.agentframework.orchestrator.elicitation;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Structured requirements model extracted from a specification.
 *
 * <p>Each requirement element tracks its source: explicitly stated in the spec,
 * inferred by the LLM, or autonomously assumed when no clarification was available.
 * Assumed requirements are tagged with {@code [ASSUMED]} prefix for downstream workers.</p>
 *
 * <p>This model is serialized as JSONB on {@code elicitation_sessions.structured_requirements},
 * not as a separate JPA entity — avoids schema explosion while keeping structure.</p>
 *
 * @see <a href="https://doi.org/10.1007/978-3-642-12578-2">Pohl, K. (2010). Requirements Engineering</a>
 */
public final class RequirementsModel {

    private RequirementsModel() {}

    /**
     * Complete structured requirements from an elicitation session.
     *
     * @param functionalReqs   what the system should do
     * @param constraints      non-functional requirements and limitations
     * @param acceptanceCriteria testable conditions for acceptance
     * @param outOfScope       explicitly excluded features
     * @param overallCompleteness estimated completeness [0,1]
     */
    public record StructuredRequirements(
            List<Requirement> functionalReqs,
            List<Requirement> constraints,
            List<Requirement> acceptanceCriteria,
            List<String> outOfScope,
            double overallCompleteness
    ) {}

    /**
     * A single requirement with provenance tracking.
     *
     * @param text       the requirement statement
     * @param source     how this requirement was determined
     * @param confidence confidence in correctness [0,1]
     * @param segment    which spec segment this relates to (nullable)
     */
    public record Requirement(
            String text,
            Source source,
            double confidence,
            @Nullable String segment
    ) {}

    /**
     * How a requirement was determined.
     */
    public enum Source {
        /** Explicitly stated in the specification. */
        EXPLICIT,
        /** Inferred from context by the LLM. */
        INFERRED,
        /** Autonomously assumed when clarification was unavailable. */
        ASSUMED
    }

    /**
     * Ambiguity report for a specification, produced by {@link AmbiguityDetector}.
     *
     * @param segments         individual segment analyses
     * @param overallUncertainty aggregate uncertainty [0,1]
     * @param needsElicitation  true if uncertainty exceeds threshold
     */
    public record AmbiguityReport(
            List<SegmentAnalysis> segments,
            double overallUncertainty,
            boolean needsElicitation
    ) {}

    /**
     * Analysis of a single specification segment.
     *
     * @param text             the segment text
     * @param uncertaintyScore uncertainty score [0,1] (1 = completely ambiguous)
     * @param ambiguityType    type of ambiguity (LEXICAL, SYNTACTIC, SEMANTIC, REFERENTIAL, SCOPE)
     * @param suggestedQuestion candidate clarification question
     */
    public record SegmentAnalysis(
            String text,
            double uncertaintyScore,
            @Nullable String ambiguityType,
            @Nullable String suggestedQuestion
    ) {}
}
