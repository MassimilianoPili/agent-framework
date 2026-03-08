# Branching: Vertical Slices, Horizontal Merges

## Problem
Traditional GitFlow with long-lived feature branches creates merge hell. Trunk-based development with short-lived branches is ideal for humans but does not work well when autonomous agents produce entire features in parallel without real-time coordination. Agent branches may overlap on shared files, and merges need clear rules.

## Solution
**Vertical slices** for features: each plan (a unit of work) gets a short-lived branch implementing a full vertical slice (contract + backend + frontend). **Horizontal merges** for environment promotion: long-lived branches (`develop`, `test`, `main`) represent environments, and vertical slices merge into them sequentially.

### Branch Topology

```
main ─────────────────────────────────────────────── (prod)
 │
 ├── develop ─────────────────────────────────────── (dev/integration)
 │    │
 │    ├── test ───────────────────────────────────── (QA)
 │    │
 │    ├── agent/PLAN-42/be-user-service ──┐ (merged)
 │    │                                   │
 │    ├── agent/PLAN-42/fe-user-page ─────┤ (merged)
 │    │                                   │
 │    ├── agent/PLAN-43/be-order-api ─────┘
 │    │
```

### Branch Naming Convention

```
agent/{planId}/{taskKey}
```

Examples:
- `agent/PLAN-42/be-user-service`
- `agent/PLAN-42/fe-user-page`
- `agent/PLAN-43/contract-order-api`

### Vertical Slice Flow

```
1. Control plane creates plan PLAN-42 with tasks:
   - contract-user-api    (contract-worker)
   - be-user-service      (be-worker, depends on contract)
   - fe-user-page         (fe-worker, depends on contract)

2. Contract worker:
   - Creates branch: agent/PLAN-42/contract-user-api (from develop)
   - Writes OpenAPI spec
   - Opens PR to develop
   - Review worker approves
   - Merges to develop

3. BE worker (triggered after contract merges):
   - Creates branch: agent/PLAN-42/be-user-service (from develop)
   - Implements generated interface
   - Opens PR to develop
   - Review worker approves
   - Merges to develop

4. FE worker (can run in parallel with BE after contract merges):
   - Creates branch: agent/PLAN-42/fe-user-page (from develop)
   - Implements UI using generated TypeScript client
   - Opens PR to develop
   - Merges to develop
```

### Cascade Merge (Horizontal)

After changes accumulate in `main` (hotfixes, config), cascade them down:

```
main -> develop -> test
```

This runs automatically. If a conflict arises, the cascade halts and a human is notified.

### Conflict Resolution Rules

| Scenario | Resolution |
|----------|------------|
| Two agents modify the same file | Second PR must rebase; if conflict, escalate to human |
| Agent PR conflicts with human PR | Human PR takes priority; agent rebases |
| Cascade merge conflict | Halt and notify tech lead |

### Agent Branch Lifecycle

1. **Created** from `develop` HEAD when the task is assigned
2. **Active** while the worker makes commits
3. **PR opened** when the worker completes
4. **Merged** after review-worker approval + quality gates pass
5. **Deleted** after merge (cleanup)

Branches older than 7 days without activity are flagged as stale and auto-closed.

### Environment Promotion

```
develop ──(auto-merge)──> test ──(manual sign-off)──> main
```

| Promotion | Trigger | Approval |
|-----------|---------|----------|
| develop -> test | Automatic after all plan tasks merged | Agent review only |
| test -> main | Manual | Human tech lead + product owner |

## Trade-offs

- **Pro**: Each agent branch is small and focused (one task). Merge conflicts are minimized because path ownership prevents two agents from modifying the same directory.
- **Pro**: The develop branch always has a working integration. Test and prod promotions are deliberate gates.
- **Con**: Sequential task dependencies (contract before BE/FE) add latency. Mitigated by running independent tasks in parallel.
- **Con**: Cascade merges can fail on conflicts. Requires human intervention, which is a bottleneck.
- **Con**: Many short-lived branches create Git history noise. Squash merges keep the log clean.
