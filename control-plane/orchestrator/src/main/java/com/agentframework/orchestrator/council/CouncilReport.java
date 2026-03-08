package com.agentframework.orchestrator.council;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Structured output produced by the pre-planning council session.
 *
 * <p>The COUNCIL_MANAGER LLM synthesises the domain views from all consulted
 * MANAGER and SPECIALIST members into this consensus document. It is stored
 * as JSON text in {@code Plan.councilReport} and injected into each dispatched
 * {@code AgentTask} as the {@code councilContext} field.</p>
 *
 * <p>All fields are nullable: the council may omit sections that are
 * not relevant to the spec (e.g., {@code securityConsiderations} may be
 * empty for a pure data migration task).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CouncilReport(

    /** Profiles of the members actually consulted (e.g. ["be-manager", "security-specialist"]). */
    List<String> selectedMembers,

    /**
     * Concrete architectural decisions the planner and workers MUST respect.
     * Example: "Use Repository pattern", "JWT in Authorization header only".
     */
    List<String> architectureDecisions,

    /**
     * Rationale for technology stack choices derived from the spec.
     * Example: "Spring Boot 3.x chosen for Java 21 virtual threads support".
     */
    String techStackRationale,

    /**
     * Security constraints to enforce across all implementation tasks.
     * Example: ["Input validation at controller level via @Valid", "OWASP Top 10: parameterized queries"].
     */
    List<String> securityConsiderations,

    /**
     * Data modeling guidelines (primary key strategy, soft-delete, naming conventions).
     * Example: "UUID primary keys, soft delete via deletedAt timestamp".
     */
    String dataModelingGuidelines,

    /**
     * API design conventions to follow (REST vs GraphQL, versioning, pagination).
     * Example: "REST, plural resources, versioned via /v1/ prefix, cursor-based pagination".
     */
    String apiDesignGuidelines,

    /**
     * Testing strategy (unit/integration split, frameworks, coverage targets).
     * Example: "Unit tests for services, integration tests for controllers (Testcontainers), 80% coverage".
     */
    String testingStrategy,

    /**
     * Raw output from each consulted member, keyed by profile name.
     * Useful for debugging and for COUNCIL_MANAGER task-level sessions.
     */
    Map<String, String> memberInsights,

    // ── Council Taste Profile (#13) ───────────────────────────────────────

    /**
     * GP-predicted mean reward for a plan with this structural profile.
     * Null on cold start (fewer than 5 historical plans available).
     */
    Double predictedReward,

    /**
     * GP posterior variance (sigma²) for the prediction.
     * Higher values indicate lower confidence (e.g. novel plan structures).
     * Null when {@code predictedReward} is null.
     */
    Double predictionUncertainty,

    /**
     * Human-readable hint summarising the GP prediction for the planner.
     * Example: "GP: predicted reward 0.72 ± 0.15 for a 5-task plan (based on 42 past plans)".
     * Null when GP is in cold-start mode.
     */
    String decompositionHint

) {}
