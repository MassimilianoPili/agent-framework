# Diagramma Architetturale — Agent Framework

## Flusso Principale di Orchestrazione

```mermaid
graph TD
    subgraph USER["User / CI-CD"]
        U[POST /api/v1/plans]
    end

    subgraph CP["CONTROL PLANE — Orchestrator"]
        API[PlanController<br/>REST API]
        COUNCIL[CouncilService<br/>Pre-planning session]
        PLANNER[PlannerService<br/>Claude LLM decomposition]
        ORCH[OrchestrationService<br/>Core dispatch loop]
        DB[(PostgreSQL<br/>Plan + PlanItem)]

        API -->|spec + budget| COUNCIL
        COUNCIL -->|CouncilReport| PLANNER
        PLANNER -->|Plan con N items| DB
        DB --> ORCH
    end

    subgraph BUS["SERVICE BUS — Artemis / Azure SB"]
        TOPICS[agent-tasks-be<br/>agent-tasks-fe<br/>agent-tasks-review<br/>agent-tasks-cm<br/>...]
        RESULTS[agent-results]
    end

    subgraph EP["EXECUTION PLANE — Workers"]
        BE[BE Worker<br/>be-java / be-go]
        FE[FE Worker]
        RV[REVIEW Worker]
        CM[CONTEXT_MANAGER]
        SM[SCHEMA_MANAGER]
        HM[HOOK_MANAGER]
        TM[TASK_MANAGER]
        COMP[COMPENSATOR_MANAGER]
    end

    subgraph MCP["MCP LAYER — Tool Servers"]
        GIT[git]
        FS[repo-fs]
        OAPI[openapi]
        TRACKER[tracker]
        TEST[test]
    end

    U --> API
    ORCH -->|AgentTask| TOPICS
    TOPICS --> BE & FE & RV & CM & SM & HM & TM & COMP
    BE & FE & RV & CM & SM & HM & TM & COMP -->|AgentResult| RESULTS
    RESULTS -->|onTaskCompleted| ORCH
    EP <-->|MCP tool calls| MCP

    style CP fill:#e8f4fd,stroke:#2196F3
    style EP fill:#fff3e0,stroke:#FF9800
    style MCP fill:#f3e5f5,stroke:#9C27B0
    style BUS fill:#e8f5e9,stroke:#4CAF50
    style USER fill:#fce4ec,stroke:#E91E63
```

## Dispatch Loop — Dettaglio

```mermaid
graph LR
    subgraph DISPATCH["dispatchReadyItems(planId)"]
        QUERY[findDispatchableItems<br/>WAITING + deps soddisfatte]
        BUDGET{TokenBudgetService<br/>checkBudget}
        SPECIAL{Tipo speciale?}
        RISK{RiskLevel?}
        PROFILE[ProfileRegistry<br/>resolveDefaultProfile]
        SEND[taskProducer.dispatch<br/>→ Service Bus]

        QUERY --> BUDGET
        BUDGET -->|ALLOW / SOFT_LIMIT| SPECIAL
        BUDGET -->|FAIL_FAST| FAIL_ITEM[Item → FAILED]
        BUDGET -->|NO_NEW_DISPATCH| SKIP[Item resta WAITING]
        SPECIAL -->|SUB_PLAN| SUBPLAN[handleSubPlan<br/>child Plan]
        SPECIAL -->|COUNCIL_MANAGER| COUNCIL_TASK[handleCouncilManager<br/>sync in-process]
        SPECIAL -->|standard| RISK
        RISK -->|CRITICAL| AWAIT[Item → AWAITING_APPROVAL]
        RISK -->|LOW / MEDIUM| PROFILE
        PROFILE --> SEND
        SEND --> DISPATCHED[Item → DISPATCHED]
    end

    style DISPATCH fill:#e3f2fd,stroke:#1565C0
```

## Reward Pipeline

```mermaid
graph TD
    subgraph REWARD["Reward Computation"]
        DONE[Item → DONE]
        PS[computeProcessScore<br/>tokenEff × 0.4<br/>retryPenalty × 0.3<br/>durationEff × 0.3]
        RS[distributeReviewScore<br/>per_task JSON o broadcast]
        QG[distributeQualityGateSignal<br/>passed → +1 / failed → -1]
        AGG[recomputeAggregatedReward<br/>Bayesian weighted<br/>review 0.50 + process 0.30 + QG 0.20]
        ELO[EloRatingService<br/>pairwise K=32<br/>per worker profile]
        DPO[PreferencePairGenerator<br/>cross-profile + retry comparison]

        DONE -->|Provenance metrics| PS
        DONE -->|REVIEW worker result| RS
        DONE -->|QualityGateReport| QG
        PS --> AGG
        RS --> AGG
        QG --> AGG
        AGG -->|plan completed| ELO
        AGG -->|delta > 0.3| DPO
    end

    subgraph EXPORT["Reward Export API"]
        R1[GET /rewards<br/>NDJSON per-task]
        R2[GET /rewards/stats<br/>ELO leaderboard]
        R3[GET /rewards/preference-pairs<br/>DPO NDJSON]
    end

    ELO --> R2
    DPO --> R3
    AGG --> R1

    style REWARD fill:#fff8e1,stroke:#F9A825
    style EXPORT fill:#e8f5e9,stroke:#2E7D32
```

## Event Sourcing + SSE

```mermaid
graph LR
    subgraph EVENTS["Hybrid Event Sourcing"]
        ORCH_EV[OrchestrationService<br/>state transitions]
        STORE[(PlanEventStore<br/>append-only log<br/>planId + sequenceNumber)]
        SPRING[SpringPlanEvent<br/>ApplicationEvent]
        SSE[SseEmitterRegistry<br/>late-join replay<br/>+ live broadcast]
        TRACKER_SYNC[TrackerSyncService<br/>@Async @EventListener<br/>TODO: MCP tracker call]
        CLIENT[Browser / UI<br/>GET /plans/id/events]

        ORCH_EV -->|append| STORE
        ORCH_EV -->|publishEvent| SPRING
        SPRING --> SSE
        SPRING -->|async| TRACKER_SYNC
        CLIENT -->|subscribe| SSE
        SSE -->|replay from| STORE
    end

    style EVENTS fill:#ede7f6,stroke:#512DA8
```

## Missing-Context Feedback Loop

```mermaid
graph LR
    W[Worker] -->|AgentResult<br/>missing_context| ORCH[OrchestrationService]
    ORCH -->|extractMissingContext| NEW_CM[Crea CM task<br/>per contesto mancante]
    NEW_CM -->|addDependency| ORIG[Item originale<br/>+ contextRetryCount++]
    ORIG -->|DISPATCHED → WAITING| QUEUE[Re-entra in dispatch queue]
    NEW_CM -->|dispatch| CM[CONTEXT_MANAGER]
    CM -->|DONE| ORCH2[onTaskCompleted]
    ORCH2 -->|deps soddisfatte| DISPATCH[Re-dispatch item originale]

    style ORCH fill:#e3f2fd,stroke:#1565C0
    style CM fill:#fff3e0,stroke:#FF9800
```

## SUB_PLAN Recursion

```mermaid
graph TD
    PARENT[Parent Plan<br/>depth=0]
    ITEM[PlanItem<br/>workerType=SUB_PLAN]
    CHILD[Child Plan<br/>depth=1, parentPlanId=parent.id]
    GUARD{depth < maxDepth?}
    COMPLETE[onChildPlanCompleted]

    PARENT -->|contiene| ITEM
    ITEM -->|handleSubPlan| GUARD
    GUARD -->|si| CHILD
    GUARD -->|no| FAIL[Item → FAILED<br/>max depth exceeded]
    CHILD -->|tutti items DONE| COMPLETE
    COMPLETE -->|parent item → DONE| PARENT
    CHILD -->|qualche item FAILED| FAIL_PARENT[parent item → FAILED]

    ITEM -.->|awaitCompletion=true| DISPATCHED[Resta DISPATCHED<br/>fino a child completato]
    ITEM -.->|awaitCompletion=false| FIRE[→ DONE subito<br/>fire-and-forget]

    style PARENT fill:#e8f4fd,stroke:#2196F3
    style CHILD fill:#e8f4fd,stroke:#2196F3,stroke-dasharray: 5 5
```

## Auto-Retry + Pause

```mermaid
graph LR
    FAIL[Item FAILED<br/>onTaskCompleted] -->|exponential backoff<br/>+ jitter ±25%| SCHEDULE[nextRetryAt = now + delay]
    SCHEDULE --> CHECK{attempts >=<br/>attemptsBeforePause?}
    CHECK -->|no| WAIT[Item resta FAILED<br/>con nextRetryAt]
    CHECK -->|si| PAUSE[Plan → PAUSED<br/>+ PLAN_PAUSED event]
    WAIT --> SCHEDULER[AutoRetryScheduler<br/>poll ogni 5s]
    SCHEDULER -->|nextRetryAt <= now| RETRY[retryFailedItem<br/>FAILED → WAITING]
    RETRY --> DISPATCH[dispatchReadyItems]
    PAUSE -->|manuale| RESUME[POST /resume<br/>PAUSED → RUNNING]
    RESUME --> DISPATCH

    style SCHEDULER fill:#fff3e0,stroke:#FF9800
    style PAUSE fill:#ffebee,stroke:#C62828
```

## Token Budget Enforcement

```mermaid
graph TD
    PRE[Pre-dispatch<br/>checkBudget]
    POST[Post-task<br/>recordUsage]
    DB[(plan_token_usage<br/>atomic UPDATE)]

    PRE -->|ALLOW| DISPATCH[Procedi con dispatch]
    PRE -->|SOFT_LIMIT| WARN[Log warning<br/>procedi comunque]
    PRE -->|NO_NEW_DISPATCH| SKIP[Item resta WAITING<br/>dispatch saltato]
    PRE -->|FAIL_FAST| FAIL[Item → FAILED<br/>budget exceeded]

    POST -->|REQUIRES_NEW tx| DB

    style PRE fill:#e3f2fd,stroke:#1565C0
    style DB fill:#e8f5e9,stroke:#2E7D32
```

## Approval Workflow (AWAITING_APPROVAL)

```mermaid
graph LR
    HM[HOOK_MANAGER<br/>analizza task] -->|HookPolicy<br/>riskLevel=CRITICAL| GATE[Risk Gate<br/>in dispatchReadyItems]
    GATE --> AWAIT[Item → AWAITING_APPROVAL]
    AWAIT -->|POST .../approve| APPROVE[→ WAITING<br/>+ triggerDispatch]
    AWAIT -->|POST .../reject| REJECT[→ FAILED<br/>+ failureReason]
    APPROVE --> DISPATCH[Re-dispatch]

    style AWAIT fill:#fff9c4,stroke:#F57F17
    style GATE fill:#ffebee,stroke:#C62828
```
