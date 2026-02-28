package com.agentframework.orchestrator.domain;

public enum WorkerType {
    BE,
    FE,
    AI_TASK,
    CONTRACT,
    REVIEW {
        /**
         * REVIEW uses a dedicated topic for QoS isolation, not the unified "agent-tasks".
         */
        @Override
        public String topicName() {
            return "agent-reviews";
        }
    },
    /**
     * CONTEXT_MANAGER explores the codebase and produces enriched context
     * (relevant file paths, world state) for downstream domain workers.
     * Always runs as a dependency before BE/FE/AI_TASK tasks.
     */
    CONTEXT_MANAGER,
    /**
     * SCHEMA_MANAGER extracts and normalises interfaces, DTOs, and architectural
     * constraints relevant to a task. Always runs as a dependency before domain workers.
     */
    SCHEMA_MANAGER,

    /**
     * HOOK_MANAGER analyzes the full plan and produces contextual {@code HookPolicy}
     * for every downstream task. Runs after CM and SM, before domain workers.
     * Its result JSON contains a {@code "policies"} map of taskKey → HookPolicy.
     */
    HOOK_MANAGER,

    /**
     * AUDIT_MANAGER is an optional final step that reads collected audit events
     * and produces a human-readable report of tool usage across the plan execution.
     */
    AUDIT_MANAGER,

    /**
     * EVENT_MANAGER analyzes hook violation patterns from the event log and
     * proposes policy adjustments for future plan executions.
     */
    EVENT_MANAGER,

    /**
     * TASK_MANAGER fetches the latest issue snapshot from the external issue tracker
     * (via tracker-mcp) and stores it on the PlanItem so downstream domain workers
     * have rich, up-to-date task context.
     *
     * <p>Typically runs as the first dependency in a plan, before CONTEXT_MANAGER
     * and SCHEMA_MANAGER, so that all workers receive the canonical issue description.
     */
    TASK_MANAGER,

    /**
     * COMPENSATOR_MANAGER executes rollback operations (git revert, file restore)
     * to undo the effect of a previously completed task. Uses the {@code git-mcp}
     * tool set to operate on the workspace.
     *
     * <p>Triggered via {@code POST .../items/{itemId}/compensate}. The orchestrator
     * creates a new PlanItem with this worker type and the failed item's context
     * as the compensation description.</p>
     */
    COMPENSATOR_MANAGER,

    /**
     * SUB_PLAN items are handled inline by the orchestrator, not dispatched to a worker.
     * When dispatched, the orchestrator calls {@code OrchestrationService.createAndStart()}
     * with the item's {@code subPlanSpec} and records the child plan ID in {@code childPlanId}.
     *
     * <p>If {@code awaitCompletion=true} (default), the item stays DISPATCHED until the
     * child plan reaches a terminal state. If {@code awaitCompletion=false}, it transitions
     * immediately to DONE (fire-and-forget).</p>
     *
     * <p>Recursion guard: depth ≥ plan's maxDepth → item transitions to FAILED immediately.</p>
     */
    SUB_PLAN,

    // ─── Council Layer ──────────────────────────────────────────────────────────

    /**
     * COUNCIL_MANAGER is the facilitator of the advisory council.
     *
     * <p>It runs in two contexts:</p>
     * <ol>
     *   <li><b>Pre-planning</b>: called synchronously by {@code CouncilService} before the
     *       planner decomposes the spec. Selects domain experts, moderates their deliberation,
     *       and synthesises a {@code CouncilReport} stored on {@code Plan.councilReport}.</li>
     *   <li><b>In-plan task</b>: intercepted by {@code OrchestrationService.dispatchReadyItems()}
     *       and executed in-process (never sent via messaging). Produces a task-scoped
     *       council report that flows to dependent workers via dependency results.</li>
     * </ol>
     *
     * <p>TaskKey prefix: {@code CL-}</p>
     */
    COUNCIL_MANAGER {
        @Override
        public String topicName() {
            // Never dispatched via messaging; in-process only.
            // Return a sentinel value — callers must check for COUNCIL_MANAGER before dispatching.
            return "agent-council";
        }
    },

    /**
     * MANAGER represents a domain-level architectural advisor.
     *
     * <p>MANAGER workers have <b>read-only</b> tool access (Glob, Grep, Read only —
     * no Write, Edit or Bash). Their output is structured domain knowledge in JSON,
     * consumed by downstream workers as dependency context.</p>
     *
     * <p>Routing is controlled by {@code workerProfile}:
     * {@code be-manager}, {@code fe-manager}, {@code security-manager}, {@code data-manager}.</p>
     *
     * <p>TaskKey prefix: {@code MG-}</p>
     */
    MANAGER {
        @Override
        public String topicName() {
            return "agent-advisory";
        }
    },

    /**
     * SPECIALIST represents a cross-cutting domain expert.
     *
     * <p>Like MANAGER, SPECIALIST workers are <b>read-only</b>. They provide focused expertise
     * on cross-cutting concerns: security, databases, API design, testing strategy.</p>
     *
     * <p>Routing is controlled by {@code workerProfile}:
     * {@code database-specialist}, {@code auth-specialist},
     * {@code api-specialist}, {@code testing-specialist}.</p>
     *
     * <p>TaskKey prefix: {@code SP-}</p>
     */
    SPECIALIST {
        @Override
        public String topicName() {
            return "agent-advisory";
        }
    };

    /**
     * Returns the Service Bus topic name for this worker type.
     * All task types use the unified "agent-tasks" topic; routing to the correct
     * worker is handled by a SQL filter on the workerType message property.
     * REVIEW overrides this to return "agent-reviews" (dedicated topic for QoS isolation).
     */
    public String topicName() {
        return "agent-tasks";
    }
}
