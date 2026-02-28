# Architecture Overview

## Three-Plane Architecture

```
                          +-------------------+
                          |   User / CI/CD    |
                          +--------+----------+
                                   |
                            POST /api/v1/plans
                                   |
                                   v
+==================================================================+
|                       CONTROL PLANE                               |
|                                                                   |
|  +-------------------+    +------------------+    +------------+  |
|  | REST API          |--->| Planner Service  |--->| Plan DB    |  |
|  | (PlanController)  |    | (Claude LLM)     |    | (Postgres) |  |
|  +-------------------+    +--------+---------+    +------------+  |
|                                    |                              |
|                           Plan decomposed                         |
|                           into N tasks                            |
|                                    |                              |
|  +-------------------+    +--------v---------+                    |
|  | Orchestration     |<---| AgentTaskProducer|                    |
|  | Service           |    | (Service Bus)    |                    |
|  +--------+----------+    +------------------+                    |
|           |                                                       |
|  +--------------------+   +--------------------+                  |
|  | HookManagerService |   | AuditManagerService|                  |
|  | storePolicies()    |   | + EventManagerSvc  |                  |
|  | resolvePolicy()    |   | /audit  /events    |                  |
|  | evictPlan()        |   | REST endpoints     |                  |
|  +--------------------+   +--------------------+                  |
|                                                                   |
+===========|======================================================+
            |
     Service Bus Topics
     (agent-tasks-{type})
            |
            v
+==================================================================+
|                      EXECUTION PLANE                              |
|                                                                   |
|  +---------+ +---------+ +---------+ +--------+ +------+         |
|  | BE      | | FE      | | AI-Task | |Contract| |Review|         |
|  | (Claude)| | (Claude)| | (Claude)| |(Claude)| |(Read)|         |
|  +---------+ +---------+ +---------+ +--------+ +------+         |
|                                                                   |
|  +---------+ +---------+ +---------+                             |
|  | Context | | Schema  | | Hook    |  (+ AUDIT_MANAGER,          |
|  | Manager | | Manager | | Manager |    EVENT_MANAGER optional)  |
|  +---------+ +---------+ +---------+                             |
|       |                                                     |    |
|       +-------- AgentResult (Service Bus) ──────────────────+    |
|              |                                               |    |
+==============|===============================================+====+
               |
               v
+==================================================================+
|                         MCP LAYER                                 |
|                                                                   |
|  +---------+  +----------+  +---------+  +-------+  +------+     |
|  |   git   |  | repo-fs  |  | openapi |  | azure |  | test |     |
|  +---------+  +----------+  +---------+  +-------+  +------+     |
|                                                                   |
|  Policies: allowlists, redaction, sandbox limits                  |
|  Hooks: enforce-ownership, block-destructive, audit-log           |
+==================================================================+
```

## Data Flow

1. User submits a natural language spec via REST API
2. Planner Service (Claude LLM) decomposes spec into ordered tasks
3. Tasks are persisted in PostgreSQL and dispatched to Service Bus topics
3b. `HOOK_MANAGER` worker analyses each downstream task and produces a per-task `HookPolicy`
3c. `HookManagerService` stores the policies; subsequent dispatches inject `HookPolicy` into `AgentTask`
4. Specialized workers pick up tasks; `AgentTask` includes an optional `HookPolicy` injected by the orchestrator
5. Workers produce AgentResult messages back to Service Bus
6. Orchestrator consumes results, updates plan state, dispatches dependent tasks
7. When all tasks complete, Quality Gate Service generates a report
8. Review worker validates the generated code against quality gates

## Key Design Decisions

- **One topic per worker type**: Enables independent scaling (see ADR-001)
- **BeanOutputConverter for structured output**: Type-safe plan decomposition (see ADR-002)
- **Deny-all MCP baseline**: Workers can only use explicitly allowed tools
- **Contract-first**: OpenAPI spec is the source of truth for API design
- **Outbox pattern**: Ensures exactly-once delivery of Service Bus messages
- **Two-tier policy enforcement**: Tier 1 = shell hooks in `.claude/settings.json` (planner-level, static, `enforce-tool-allowlist.sh`); Tier 2 = `HookPolicy` record in `AgentTask` (per-task, dynamic, from `HOOK_MANAGER` worker)
- **Policy as data**: `HookPolicy` travels through the message bus with the task payload — the planner is the single policy authority
