package com.agentframework.orchestrator.planner;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Structured output schema for the planner Claude call.
 * BeanOutputConverter generates JSON Schema from this record and instructs
 * Claude to respond in this exact format.
 */
@JsonClassDescription("A decomposed execution plan with ordered tasks")
public record PlanSchema(

    @JsonPropertyDescription("Short summary of what this plan accomplishes")
    String summary,

    @JsonPropertyDescription("Ordered list of tasks to execute")
    List<PlanItemSchema> tasks
) {}
