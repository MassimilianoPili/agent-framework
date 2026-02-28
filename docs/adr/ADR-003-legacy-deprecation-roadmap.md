# ADR-003: Legacy Naming and Topic Deprecation Roadmap

**Status**: Accepted
**Date**: 2026-02-26
**Context**: Fase 1 — Coerenza Contratti e Topologia

## Context

The agent framework evolved through three design phases:

1. **v1 (per-workerType)**: One subscription per WorkerType (`be-worker`, `fe-worker`), one topic per type (`agent-tasks-be`, `agent-tasks-fe`)
2. **v2 (unified topic)**: Single `agent-tasks` topic with SQL filters on `workerType`
3. **v3 (per-profile)**: SQL filters on `workerProfile` for multi-stack types (BE, FE), retaining `workerType` filter for single-profile types (AI_TASK, CONTRACT, REVIEW)

Each evolution left naming artifacts in configuration files, job manifests, and documentation.

## Decision

All v1 and v2 naming patterns are deprecated. Fase 1 of the evolution plan removes them from active files. This ADR documents what was deprecated, what replaced it, and how to verify zero residual references.

## Deprecated Patterns

### Subscription names (v1 → v3)

| Deprecated | Replacement | Filter |
|---|---|---|
| `be-worker-sub` | `be-java-worker-sub`, `be-go-worker-sub`, `be-rust-worker-sub`, `be-node-worker-sub` | `workerProfile = 'be-{stack}'` |
| `fe-worker-sub` | `fe-react-worker-sub` | `workerProfile = 'fe-react'` |

### Job manifest names (v1 → v3)

| Deprecated | Replacement |
|---|---|
| `be-worker.job.yml` | `be-java-worker.job.yml` + `be-go-worker.job.yml` + `be-rust-worker.job.yml` + `be-node-worker.job.yml` |
| `fe-worker.job.yml` | `fe-react-worker.job.yml` |

### Topic patterns (v1 → v2)

| Deprecated | Replacement |
|---|---|
| `agent-tasks-be` | `agent-tasks` |
| `agent-tasks-fe` | `agent-tasks` |
| `agent-tasks-test` | `agent-tasks` (test fixtures) |

### Schema fields (v3 provenance)

| Deprecated field (root level) | Canonical location |
|---|---|
| `AgentResult.workerType` | `AgentResult.provenance.workerType` |
| `AgentResult.workerProfile` | `AgentResult.provenance.workerProfile` |
| `AgentResult.modelId` | `AgentResult.provenance.modelId` |
| `AgentResult.promptHash` | `AgentResult.provenance.promptHash` |

Root-level fields are marked `"deprecated": true` in `AgentResult.schema.json` and will be removed in a future major version.

## Removal Timeline

| Milestone | Target | Scope |
|---|---|---|
| Fase 1 complete | 2026-02 | All deprecated patterns removed from active config and runtime files |
| Schema major bump | TBD | Remove deprecated root-level fields from `AgentResult.schema.json` |
| Azure infra cleanup | TBD | Delete orphaned Service Bus subscriptions (`be-worker-sub`, `fe-worker-sub`) |

## Verification

Run after any topology change to confirm zero residual references:

```bash
# Obsolete subscription names (should return zero results outside docs/adr/)
grep -rn 'subscriptionName: be-worker\b\|subscriptionName: fe-worker\b' \
  --include="*.yml" --include="*.yaml" --include="*.java" \
  | grep -v target/ | grep -v docs/adr/

# Obsolete per-type topics (should return zero results outside docs/adr/)
grep -rn 'agent-tasks-be\|agent-tasks-fe\|agent-tasks-test' \
  --include="*.yml" --include="*.yaml" --include="*.java" \
  | grep -v target/ | grep -v docs/adr/

# Obsolete job names
ls execution-plane/runtime/jobs/ | grep -E '^(be|fe)-worker\.job\.yml$'
```

All three commands should produce empty output when Fase 1 is complete.
