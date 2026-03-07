package com.agentframework.orchestrator.planner;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Schema for a single task in the planner's structured output.
 * Claude uses the @JsonPropertyDescription annotations as field-level instructions.
 */
@JsonClassDescription("A single task within the execution plan")
public record PlanItemSchema(

    @JsonPropertyDescription("Unique key within the plan. Format: BE-001, FE-001, AI-001, CT-001, RV-001")
    String taskKey,

    @JsonPropertyDescription("One-line task title")
    String title,

    @JsonPropertyDescription("Detailed task description with acceptance criteria")
    String description,

    @JsonPropertyDescription("Worker type: BE, FE, AI_TASK, CONTRACT, REVIEW")
    String workerType,

    @JsonPropertyDescription("Worker profile selecting the concrete technology stack. "
        + "Examples: be-java, be-go, be-rust, be-node, be-quarkus, be-laravel, be-cpp, "
        + "fe-react, fe-vanillajs, fe-angular, fe-svelte. "
        + "Null for non-implementation tasks (AI_TASK, CONTRACT, REVIEW)")
    String workerProfile,

    @JsonPropertyDescription("Task keys this task depends on; empty array if no dependencies")
    List<String> dependsOn,

    @JsonPropertyDescription(
        "MCP tool names this task needs. Use real MCP names: "
        + "fs_read, fs_write, fs_search, fs_list, bash_execute, python_execute. "
        + "null or empty = no tools (text-only task like REVIEW or CONTRACT)")
    List<String> toolHints
) {}
