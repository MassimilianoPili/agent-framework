# Diagramma Architetturale v2 — Agent Framework

> **Versione**: 2.0 | **Aggiornato**: 2026-03-17
> **Stack**: Java 21, Spring Boot 3.4.1, Spring AI 1.0.0, Redis Streams, PostgreSQL 18 + pgvector + Apache AGE v1.7.0
> **Stato codebase**: 42 Flyway migrations, 77+ analytics services, 22 WorkerType, 47+ profili worker

---

## 1. Overview Architetturale

I 3 piani logici + infrastruttura. Flussi principali dal client ai worker e ritorno.

```mermaid
graph TD
    subgraph USER["User / CI-CD"]
        U[POST /api/v1/plans]
    end

    subgraph CP["CONTROL PLANE"]
        API[PlanController<br/>REST API + SSE]
        COUNCIL[CouncilService<br/>8 membri advisory]
        PLANNER[PlannerService<br/>Claude LLM decomposition]
        ORCH[OrchestrationService<br/>State Machine + Dispatch]
        GP[GP Engine<br/>UCB Worker Selection]
        REWARD[RewardPipeline<br/>4 fonti + ELO + DPO]
        LEADER[LeaderElection<br/>Redis SET NX]
        EVENTS[PlanEventStore<br/>append-only log]
        LEDGER[TokenLedger<br/>double-entry]
    end

    subgraph BUS["REDIS STREAMS — 5 topic"]
        TASKS[agent-tasks]
        RESULTS[agent-results]
        REVIEWS[agent-reviews]
        ADVISORY[agent-advisory]
    end

    subgraph EP["EXECUTION PLANE — 22 WorkerType"]
        DOMAIN[Domain Workers<br/>BE x13, FE x6, DBA x10<br/>MOBILE x2, AI_TASK, CONTRACT]
        INFRA_W[Infrastructure Workers<br/>CM, SM, HM, TM, COMP<br/>RAG, AUDIT, EVENT, INTEG<br/>RESEARCH, TOOL]
        REVIEW_W[REVIEW Worker<br/>Quality Gate]
        ADV_W[Advisory Workers<br/>MANAGER, SPECIALIST]
    end

    subgraph MCP["MCP LAYER — 7 server"]
        GIT_S[git]
        FS_S[repo-fs]
        OAPI_S[openapi]
        AZ_S[azure]
        TEST_S[test]
        BASH_S[bash]
        PY_S[python]
    end

    subgraph INFRA["INFRASTRUCTURE"]
        PG[(PostgreSQL 18<br/>pgvector + AGE v1.7)]
        REDIS[(Redis 7<br/>DB3 Streams + DB5 Cache)]
        OLLAMA[Ollama<br/>mxbai-embed-large<br/>qwen2.5:1.5b]
    end

    U --> API
    API --> COUNCIL
    COUNCIL --> PLANNER
    PLANNER --> ORCH
    ORCH --> GP
    GP --> ORCH
    ORCH --> TASKS
    ORCH --> REVIEWS
    TASKS --> DOMAIN
    TASKS --> INFRA_W
    REVIEWS --> REVIEW_W
    ADVISORY --> ADV_W
    DOMAIN --> RESULTS
    INFRA_W --> RESULTS
    REVIEW_W --> RESULTS
    ADV_W --> RESULTS
    RESULTS --> ORCH
    ORCH --> REWARD
    ORCH --> EVENTS
    ORCH --> LEDGER
    LEADER --> ORCH
    DOMAIN --> GIT_S
    DOMAIN --> FS_S
    DOMAIN --> BASH_S
    INFRA_W --> FS_S
    REVIEW_W --> FS_S
    ORCH --> PG
    REWARD --> PG
    GP --> PG
    EVENTS --> PG
    ORCH --> REDIS
    GP --> OLLAMA

    style CP fill:#e8f4fd,stroke:#2196F3
    style EP fill:#fff3e0,stroke:#FF9800
    style MCP fill:#f3e5f5,stroke:#9C27B0
    style BUS fill:#e8f5e9,stroke:#4CAF50
    style INFRA fill:#fce4ec,stroke:#E91E63
    style USER fill:#f5f5f5,stroke:#9E9E9E
```

---

## 2. Control Plane — Architettura Interna

Dettaglio dei componenti del piano di controllo e le loro interazioni.

```mermaid
graph TD
    subgraph API_LAYER["REST API"]
        CTRL[PlanController<br/>CRUD + SSE]
        SSE_EP["GET /plans/id/events"]
    end

    subgraph COUNCIL_SYS["Council System"]
        SEL[SubmodularSelector<br/>CELF greedy]
        QV[QuadraticVoting<br/>voice credits, k^2 cost]
        CR[CommitReveal<br/>SHA-256 anti-coordination]
        SYCO[SycophancyDetector<br/>groupthink guard]
        CRAG[CouncilRagEnricher]
        CREPORT[CouncilReport]
    end

    subgraph PLANNER_SYS["Planner"]
        PLAN_SVC[PlannerService<br/>Claude LLM]
        BEAN[BeanOutputConverter<br/>type-safe decomposition]
        PLAN_DB[(Plan + N PlanItems)]
    end

    subgraph ORCH_SYS["Orchestration"]
        DISPATCH[dispatchReadyItems<br/>wave-based DAG]
        BUDGET[TokenBudgetService<br/>4 outcome levels]
        ENRICH[EnrichmentInjector<br/>TOOL_MANAGER auto-dep]
        RETRY_S[AutoRetryScheduler<br/>poll 5s, exp backoff]
    end

    subgraph GP_SYS["GP Engine"]
        GP_PRED[GaussianProcessEngine<br/>RBF 1024-dim]
        UCB_SEL["UCB: mu + C*sigma"]
        MCTS_D[MctsDispatchService<br/>PUCT AlphaZero]
        HANDOFF[HandoffRouter<br/>confidence 0.7]
        SEREN[SerendipityService<br/>GP residual surprise]
    end

    subgraph REWARD_SYS["Reward Pipeline"]
        AGG[BayesianAggregation<br/>4 fonti weighted]
        ELO_S[EloRatingService<br/>K=32 pairwise]
        DPO_S[PreferencePairGenerator<br/>3 strategie DPO]
        TL[TokenLedgerService<br/>DEBIT + CREDIT]
        SHAP[ShapleyDagService<br/>infra worker credits]
    end

    subgraph EVENT_SYS["Event Sourcing"]
        ESTORE[PlanEventStore<br/>append-only]
        SSE_REG[SseEmitterRegistry<br/>late-join replay]
        TRACKER[TrackerSyncService<br/>async listener]
        LEAD[LeaderElection<br/>SET NX TTL=30s]
    end

    CTRL --> SEL
    SEL --> QV
    QV --> CR
    CR --> SYCO
    CRAG --> SEL
    SYCO --> CREPORT
    CREPORT --> PLAN_SVC
    PLAN_SVC --> BEAN
    BEAN --> PLAN_DB
    PLAN_DB --> DISPATCH
    DISPATCH --> BUDGET
    BUDGET --> ENRICH
    ENRICH --> GP_PRED
    GP_PRED --> UCB_SEL
    UCB_SEL --> MCTS_D
    MCTS_D --> HANDOFF
    HANDOFF --> DISPATCH
    DISPATCH --> AGG
    AGG --> ELO_S
    AGG --> DPO_S
    AGG --> TL
    TL --> SHAP
    DISPATCH --> ESTORE
    ESTORE --> SSE_REG
    ESTORE --> TRACKER
    LEAD --> ESTORE
    SSE_REG --> SSE_EP
    RETRY_S --> DISPATCH

    style API_LAYER fill:#e3f2fd,stroke:#1565C0
    style COUNCIL_SYS fill:#fff8e1,stroke:#F9A825
    style PLANNER_SYS fill:#e8f5e9,stroke:#2E7D32
    style ORCH_SYS fill:#e3f2fd,stroke:#1565C0
    style GP_SYS fill:#f3e5f5,stroke:#7B1FA2
    style REWARD_SYS fill:#fff3e0,stroke:#E65100
    style EVENT_SYS fill:#ede7f6,stroke:#512DA8
```

---

## 3. Worker Taxonomy — 22 WorkerType

Tutti i worker organizzati per categoria con profili e routing verso i Redis Streams.

```mermaid
graph LR
    subgraph DOMAIN["DOMAIN — 7 tipi, ~47 profili"]
        BE["BE<br/>java, go, rust, node<br/>python, kotlin, quarkus<br/>laravel, cpp, lua, dotnet<br/>elixir, ocaml"]
        FE["FE<br/>react, nextjs, vue<br/>angular, svelte, vanillajs"]
        DBA["DBA<br/>postgres, mysql, oracle<br/>mssql, sqlite, mongo<br/>graphdb, vectordb<br/>redis, cassandra"]
        MOB["MOBILE<br/>swift, kotlin"]
        AIT[AI_TASK]
        CON[CONTRACT]
        REV[REVIEW]
    end

    subgraph INFRASTRUCTURE["INFRASTRUCTURE — 11 tipi"]
        CM[CONTEXT_MANAGER]
        SM[SCHEMA_MANAGER]
        HM[HOOK_MANAGER]
        TM[TASK_MANAGER]
        COMP[COMPENSATOR_MANAGER]
        RM[RAG_MANAGER]
        AM[AUDIT_MANAGER]
        EM[EVENT_MANAGER]
        IM[INTEGRATION_MANAGER]
        RSM[RESEARCH_MANAGER]
        TLM[TOOL_MANAGER]
    end

    subgraph ADVISORY_CAT["ADVISORY — 3 tipi"]
        CMGR["COUNCIL_MANAGER<br/>(in-process, mai dispatched)"]
        MGR["MANAGER<br/>4 profili domain-level"]
        SPEC["SPECIALIST<br/>7 profili cross-cutting"]
    end

    subgraph META["META — 1 tipo"]
        SUB["SUB_PLAN<br/>recursive child plans"]
    end

    subgraph STREAMS["Redis Streams Routing"]
        S_TASKS[agent-tasks]
        S_REVIEWS[agent-reviews]
        S_ADVISORY[agent-advisory]
        S_COUNCIL["agent-council<br/>(sentinel only)"]
    end

    BE --> S_TASKS
    FE --> S_TASKS
    DBA --> S_TASKS
    MOB --> S_TASKS
    AIT --> S_TASKS
    CON --> S_TASKS
    CM --> S_TASKS
    SM --> S_TASKS
    HM --> S_TASKS
    TM --> S_TASKS
    COMP --> S_TASKS
    RM --> S_TASKS
    AM --> S_TASKS
    EM --> S_TASKS
    IM --> S_TASKS
    RSM --> S_TASKS
    TLM --> S_TASKS
    REV --> S_REVIEWS
    MGR --> S_ADVISORY
    SPEC --> S_ADVISORY
    CMGR --> S_COUNCIL

    style DOMAIN fill:#e8f4fd,stroke:#2196F3
    style INFRASTRUCTURE fill:#fff3e0,stroke:#FF9800
    style ADVISORY_CAT fill:#e8f5e9,stroke:#4CAF50
    style META fill:#f3e5f5,stroke:#9C27B0
    style STREAMS fill:#fce4ec,stroke:#E91E63
```

---

## 4. Redis Streams — Topologia Messaging

5 topic Redis Streams con producer, consumer groups e pattern QoS.

```mermaid
graph LR
    subgraph PRODUCER["Producers"]
        ORCH_P[OrchestrationService<br/>AgentTaskProducer]
        WORKER_P[All Workers<br/>AgentResultProducer]
    end

    subgraph STREAMS["Redis DB3 — Streams"]
        AT["agent-tasks<br/>main dispatch topic<br/>consumer group per workerType"]
        AR["agent-results<br/>completion channel<br/>single consumer: Orchestrator"]
        AREV["agent-reviews<br/>QoS isolation<br/>consumer group: REVIEW"]
        AADV["agent-advisory<br/>advisory channel<br/>consumer groups: MANAGER, SPECIALIST"]
        ACNC["agent-council<br/>sentinel value only<br/>never dispatched via messaging"]
    end

    subgraph CONSUMERS["Consumers"]
        CG_BE["ConsumerGroup: BE<br/>client-side shouldProcess"]
        CG_FE["ConsumerGroup: FE"]
        CG_DBA["ConsumerGroup: DBA"]
        CG_CM["ConsumerGroup: CM"]
        CG_OTHER["ConsumerGroups: MOB, AIT<br/>CON, SM, HM, TM<br/>COMP, RM, AM, EM<br/>IM, RSM, TLM"]
        CG_REV["ConsumerGroup: REVIEW"]
        CG_MGR["ConsumerGroup: MANAGER"]
        CG_SPEC["ConsumerGroup: SPECIALIST"]
        ORCH_C[OrchestrationService<br/>AgentResultConsumer<br/>XACK after processing]
    end

    ORCH_P -->|"XADD AgentTask"| AT
    ORCH_P -->|"XADD AgentTask"| AREV
    ORCH_P -->|"XADD AgentTask"| AADV
    AT -->|XREADGROUP| CG_BE
    AT -->|XREADGROUP| CG_FE
    AT -->|XREADGROUP| CG_DBA
    AT -->|XREADGROUP| CG_CM
    AT -->|XREADGROUP| CG_OTHER
    AREV -->|XREADGROUP| CG_REV
    AADV -->|XREADGROUP| CG_MGR
    AADV -->|XREADGROUP| CG_SPEC
    WORKER_P -->|"XADD AgentResult"| AR
    AR -->|XREADGROUP| ORCH_C

    style PRODUCER fill:#e3f2fd,stroke:#1565C0
    style STREAMS fill:#e8f5e9,stroke:#2E7D32
    style CONSUMERS fill:#fff3e0,stroke:#FF9800
```

---

## 5. Dispatch Loop v2 — Dettaglio

Il ciclo di dispatch arricchito con GP Worker Selection, MCTS, Handoff Router, budget check e risk gate.

```mermaid
graph TD
    START[dispatchReadyItems<br/>planId] --> QUERY[findDispatchableItems<br/>WAITING + deps soddisfatte]
    QUERY --> BUDGET{TokenBudgetService<br/>checkBudget}

    BUDGET -->|ALLOW| SPECIAL
    BUDGET -->|SOFT_LIMIT| WARN[Log warning] --> SPECIAL
    BUDGET -->|NO_NEW_DISPATCH| SKIP[Item resta WAITING]
    BUDGET -->|FAIL_FAST| FAIL_B[Item -- FAILED<br/>budget exceeded]

    SPECIAL{Tipo speciale?}
    SPECIAL -->|SUB_PLAN| SUBPLAN[handleSubPlan<br/>child Plan inline]
    SPECIAL -->|COUNCIL_MANAGER| COUNCIL_T[handleCouncilManager<br/>sync in-process]
    SPECIAL -->|standard| ENRICH_S

    ENRICH_S[EnrichmentInjector<br/>auto-inject TOOL_MANAGER dep] --> RISK

    RISK{RiskLevel?}
    RISK -->|CRITICAL| AWAIT[Item -- AWAITING_APPROVAL]
    RISK -->|LOW / MEDIUM| GP_SEL

    GP_SEL[GpWorkerSelection<br/>embedding 1024-dim] --> UCB_D{"UCB: mu + C*sigma<br/>cold-start: uniform prior"}

    UCB_D --> RALPH{"sigma alta?<br/>Ralph-Loop check"}
    RALPH -->|"si: alta incertezza"| PRE_REV[Pre-dispatch REVIEW<br/>feedback injection]
    RALPH -->|no| MCTS_CHECK

    PRE_REV --> MCTS_CHECK

    MCTS_CHECK{"Plan steps >= 4?"}
    MCTS_CHECK -->|si| MCTS_S[MctsDispatchService<br/>PUCT Select-Expand<br/>Simulate-Backprop]
    MCTS_CHECK -->|no| GREEDY[Greedy: best UCB profile]

    MCTS_S --> HANDOFF_C
    GREEDY --> HANDOFF_C

    HANDOFF_C{"HandoffRouter<br/>confidence >= threshold?"}
    HANDOFF_C -->|"direct: conf >= 0.7"| DIRECT[Worker-to-Worker<br/>handoff diretto]
    HANDOFF_C -->|centralized| PROFILE

    DIRECT --> SEND
    PROFILE[ProfileRegistry<br/>resolveProfile] --> SEND

    SEND[taskProducer.dispatch<br/>XADD Redis Stream] --> DISPATCHED[Item -- DISPATCHED]

    style START fill:#e3f2fd,stroke:#1565C0
    style BUDGET fill:#fff8e1,stroke:#F9A825
    style GP_SEL fill:#f3e5f5,stroke:#7B1FA2
    style MCTS_S fill:#f3e5f5,stroke:#7B1FA2
    style SEND fill:#e8f5e9,stroke:#2E7D32
```

---

## 6. GP Engine + MCTS + DPO

Gaussian Process con kernel RBF, UCB selection, MCTS dispatch con PUCT, DPO pair generation e semantic cache.

```mermaid
graph TD
    subgraph GP_CORE["GP Core — Gaussian Process"]
        TASK_EMB[Task Embedding<br/>1024-dim mxbai-embed-large]
        RBF[RBF Kernel<br/>lengthscale configurabile]
        CHOL[CholeskyDecomposition<br/>numerical stability]
        POST[GpPosterior<br/>alpha, L, embeddings, mean]
        PRED[GpPrediction<br/>mu, sigma2]
    end

    subgraph TRAINING["Training Data"]
        OUTCOMES[(task_outcomes<br/>embedding + reward<br/>+ ELO snapshot)]
        FIT[GaussianProcessEngine.fit<br/>max 500 training points]
        CACHE_M[GpModelCache<br/>in-memory per profile]
    end

    subgraph SELECTION["Worker Selection"]
        UCB_S["UCB Score<br/>mu + C * sigma"]
        COLD{"cold-start?<br/>tasks less than 50"}
        PRIOR[Uniform Prior<br/>all profiles equal]
        SELECT[Profile selezionato<br/>UCB massimo]
    end

    subgraph MCTS_SYS["MCTS — Monte Carlo Tree Search"]
        PUCT["PUCT Score<br/>Q + c * P * sqrt-N / 1+N"]
        SEL_M[Select: traverse con PUCT]
        EXP_M[Expand: child per profili non provati]
        SIM_M[Simulate: GP mean come leaf value]
        BACK_M[Backpropagate: Welford online mean]
        ONLINE[MctsOnlinePolicyService<br/>EMA decay, RAVE warm-start<br/>BAMCP root sampling]
    end

    subgraph DPO_SYS["DPO — Direct Preference Optimization"]
        STRAT1["cross-profile<br/>same task, different workers"]
        STRAT2["retry<br/>same worker, attempt N vs N+1"]
        STRAT3["gp_residual_surprise<br/>actual vs predicted reward"]
        PAIRS[PreferencePairGenerator<br/>export NDJSON]
    end

    subgraph SEM_CACHE["Semantic Cache"]
        RCACHE[(Redis DB5<br/>cosine threshold 0.92<br/>LRU eviction, 24h TTL)]
    end

    TASK_EMB --> RBF
    RBF --> CHOL
    CHOL --> POST
    POST --> PRED
    OUTCOMES --> FIT
    FIT --> CACHE_M
    CACHE_M --> POST
    PRED --> COLD
    COLD -->|"si"| PRIOR
    COLD -->|no| UCB_S
    PRIOR --> SELECT
    UCB_S --> SELECT
    POST --> PUCT
    PUCT --> SEL_M
    SEL_M --> EXP_M
    EXP_M --> SIM_M
    SIM_M --> BACK_M
    BACK_M --> ONLINE
    SELECT --> STRAT1
    SELECT --> STRAT2
    SELECT --> STRAT3
    STRAT1 --> PAIRS
    STRAT2 --> PAIRS
    STRAT3 --> PAIRS
    TASK_EMB --> RCACHE

    style GP_CORE fill:#f3e5f5,stroke:#7B1FA2
    style TRAINING fill:#fff8e1,stroke:#F9A825
    style SELECTION fill:#e3f2fd,stroke:#1565C0
    style MCTS_SYS fill:#ede7f6,stroke:#512DA8
    style DPO_SYS fill:#fff3e0,stroke:#E65100
    style SEM_CACHE fill:#fce4ec,stroke:#E91E63
```

---

## 7. Council System — Deep Dive

Pre-planning advisory con selezione submodulare, Quadratic Voting, commit-reveal e guardie anti-groupthink.

```mermaid
graph TD
    subgraph INPUT["Input"]
        SPEC[Plan Specification]
        RAG_E[CouncilRagEnricher<br/>augment spec with codebase context]
        TASTE[PlanDecompositionPredictor<br/>GP taste-profile]
    end

    subgraph MEMBER_SEL["Member Selection"]
        SUBMOD[SubmodularSelector<br/>CELF greedy optimization<br/>~63pct approximation guarantee]
        TOPIC_COV[TopicCoverageFunction<br/>diversity metric]
        LLM_SEL["LLM Selector<br/>fallback alternativo"]
    end

    subgraph MEMBERS["Council Members — max 8"]
        M1[be-manager]
        M2[fe-manager]
        M3[security-manager]
        M4[data-manager]
        S1[database-specialist]
        S2[auth-specialist]
        S3[api-specialist]
        S4[testing-specialist]
    end

    subgraph PARALLEL["Parallel Consultation"]
        POOL[Bounded ThreadPool<br/>2-8 threads, queue 20]
        TIMEOUT["Timeout: 120s per member"]
        FUTURES[CompletableFuture<br/>per member]
    end

    subgraph INTEGRITY["Integrity Checks"]
        COMMIT_P[Commit Phase<br/>SHA-256 hash of view]
        REVEAL_P[Reveal Phase<br/>verify hash matches]
        SYCO_D[SycophancyDetector<br/>groupthink detection]
        SUPER_S[SuperrationalityService<br/>cooperative pair detection]
        RENORM_S[RenormalizationGroup<br/>structural coupling analysis]
    end

    subgraph VOTING["Quadratic Voting"]
        CREDITS["Voice Credits<br/>budget per member"]
        COST_V["Cost: k votes = k^2 credits"]
        WEIGHT_V[WeightedRecommendation<br/>vote intensity weighting]
    end

    subgraph OUTPUT_C["Synthesis"]
        SYNTH[COUNCIL_MANAGER LLM<br/>synthesize all views]
        REPORT[CouncilReport<br/>consensusScore<br/>recommendations<br/>riskAssessment]
    end

    SPEC --> RAG_E
    RAG_E --> SUBMOD
    TASTE --> SUBMOD
    SUBMOD --> TOPIC_COV
    TOPIC_COV --> M1
    TOPIC_COV --> M2
    TOPIC_COV --> M3
    TOPIC_COV --> M4
    TOPIC_COV --> S1
    TOPIC_COV --> S2
    TOPIC_COV --> S3
    TOPIC_COV --> S4
    LLM_SEL --> M1
    M1 --> POOL
    M2 --> POOL
    M3 --> POOL
    M4 --> POOL
    S1 --> POOL
    S2 --> POOL
    S3 --> POOL
    S4 --> POOL
    POOL --> TIMEOUT
    TIMEOUT --> FUTURES
    FUTURES --> COMMIT_P
    COMMIT_P --> REVEAL_P
    REVEAL_P --> SYCO_D
    SYCO_D --> SUPER_S
    SUPER_S --> RENORM_S
    RENORM_S --> CREDITS
    CREDITS --> COST_V
    COST_V --> WEIGHT_V
    WEIGHT_V --> SYNTH
    SYNTH --> REPORT

    style INPUT fill:#e3f2fd,stroke:#1565C0
    style MEMBER_SEL fill:#fff8e1,stroke:#F9A825
    style MEMBERS fill:#e8f5e9,stroke:#2E7D32
    style PARALLEL fill:#fff3e0,stroke:#FF9800
    style INTEGRITY fill:#ffebee,stroke:#C62828
    style VOTING fill:#ede7f6,stroke:#512DA8
    style OUTPUT_C fill:#e8f4fd,stroke:#2196F3
```

---

## 8. RAG Engine — Pipeline Completa

Ingestion, embedding, hybrid search con HyDE, RRF fusion, Graph RAG e cascade reranking.

```mermaid
graph TD
    subgraph INGESTION["Ingestion Pipeline"]
        FW[FileWatcherService<br/>monitor codebase changes]
        CDR[CodeDocumentReader<br/>file parsing]
        RCC[RecursiveCodeChunker<br/>512 tokens, 50 overlap]
        PC[PropositionChunker<br/>proposition-level]
        ME[MetadataEnricher<br/>language, filePath, docType]
        CE[ContextualEnricher<br/>semantic context augmentation]
    end

    subgraph EMBEDDING_S["Embedding"]
        OLLAMA_E[Ollama mxbai-embed-large<br/>1024-dim vectors]
        EMB_CACHE[(EmbeddingCacheService<br/>Redis DB5, 24h TTL)]
    end

    subgraph STORAGE_S["Storage Layer"]
        PGVEC[(pgvector<br/>HNSW cosine index<br/>vector_store table)]
        AGE_KG[(knowledge_graph<br/>concepts, technologies<br/>uses, extends, implements)]
        AGE_CG[(code_graph<br/>modules, functions<br/>dependencies from AST)]
        AGE_TG[(task_graph<br/>tasks, prerequisites<br/>resource requirements)]
    end

    subgraph RETRIEVAL_S["Retrieval Pipeline"]
        QUERY_R[Search Query]
        HYDE[HyDE Query Transformer<br/>hypothetical answer generation]
        VEC_S[Vector Search<br/>cosine distance pgvector]
        BM25_S[BM25 Search<br/>tsvector full-text PostgreSQL]
        GRAPH_S_R[Graph Traversal<br/>Apache AGE Cypher queries]
        RRF_S["RRF Fusion<br/>Reciprocal Rank Fusion<br/>k=60"]
    end

    subgraph GRAPH_RAG_S["Graph RAG"]
        GRAG[GraphRagService<br/>cross-graph correlation]
        VTHREAD[Virtual Threads<br/>parallel queries, 30s timeout]
        MERGE_S[Merge by filePath<br/>unified view]
    end

    subgraph RERANKING_S["Cascade Reranking"]
        COS_R[CosineReranker<br/>threshold filter]
        LLM_R[LlmReranker<br/>Ollama qwen2.5:1.5b<br/>cross-encoder scoring]
        NOOP_R[NoOpReranker<br/>passthrough fallback]
        FINAL_R[Top-K Results<br/>injected in worker prompt]
    end

    FW --> CDR
    CDR --> RCC
    CDR --> PC
    RCC --> ME
    PC --> ME
    ME --> CE
    CE --> OLLAMA_E
    OLLAMA_E --> EMB_CACHE
    OLLAMA_E --> PGVEC
    CE --> AGE_KG
    CE --> AGE_CG
    CE --> AGE_TG
    QUERY_R --> HYDE
    HYDE --> VEC_S
    HYDE --> BM25_S
    HYDE --> GRAPH_S_R
    VEC_S --> RRF_S
    BM25_S --> RRF_S
    GRAPH_S_R --> RRF_S
    GRAPH_S_R --> GRAG
    GRAG --> VTHREAD
    VTHREAD --> MERGE_S
    MERGE_S --> RRF_S
    RRF_S --> COS_R
    COS_R --> LLM_R
    LLM_R --> FINAL_R
    COS_R --> NOOP_R
    NOOP_R --> FINAL_R

    style INGESTION fill:#e8f5e9,stroke:#2E7D32
    style EMBEDDING_S fill:#f3e5f5,stroke:#7B1FA2
    style STORAGE_S fill:#fff8e1,stroke:#F9A825
    style RETRIEVAL_S fill:#e3f2fd,stroke:#1565C0
    style GRAPH_RAG_S fill:#ede7f6,stroke:#512DA8
    style RERANKING_S fill:#fff3e0,stroke:#E65100
```

---

## 9. Reward Pipeline + Token Ledger

4 fonti di reward, aggregazione bayesiana, ELO rating, DPO pair generation e Token Ledger double-entry.

```mermaid
graph TD
    subgraph SOURCES["4 Fonti di Reward"]
        DONE_I[Item -- DONE]
        RS["Review Score<br/>weight 0.45<br/>REVIEW worker: per-task JSON<br/>range: -1.0 to +1.0"]
        PS["Process Score<br/>weight 0.25<br/>deterministico<br/>tokenEff*0.4 + retryPen*0.3<br/>+ durationEff*0.3"]
        QGS["Quality Gate Score<br/>weight 0.15<br/>post-completamento piano<br/>PASS +1 / FAIL -1"]
        CQS["Context Quality Score<br/>weight 0.15<br/>information-theoretic<br/>Mutual Info + KL Divergence"]
    end

    subgraph AGGREGATION_S["Bayesian Aggregation"]
        BAYES[BayesianAggregation<br/>re-normalize when<br/>sources unavailable]
        RESHAPE[PotentialRewardShaping<br/>Ng 1999]
    end

    subgraph ELO_SYS["ELO Rating"]
        ELO_R[EloRatingService<br/>pairwise K=32<br/>per worker profile]
        STATS[(worker_elo_stats<br/>rating, attempts, winRate)]
        LEADER_B["GET /rewards/stats<br/>ELO leaderboard"]
    end

    subgraph DPO_SYS_R["DPO Pair Generation"]
        CROSS["cross-profile<br/>same task, different workers"]
        RETRY_C["retry<br/>attempt N vs N+1"]
        GP_RES["gp_residual_surprise<br/>actual vs GP predicted"]
        EXPORT["GET /rewards/preference-pairs<br/>NDJSON export"]
    end

    subgraph LEDGER_SYS["Token Ledger — Double Entry"]
        DEBIT[DEBIT<br/>token consumed at dispatch]
        CREDIT_E[CREDIT<br/>token earned on completion]
        SHAPLEY_C[Shapley Credits<br/>infra workers via<br/>ShapleyDagService]
        BALANCE[(token_ledger<br/>per-plan balance<br/>low-efficiency alerts)]
        STAKE[ReputationStaking<br/>stake-weighted dispatch]
    end

    DONE_I --> RS
    DONE_I --> PS
    DONE_I --> QGS
    DONE_I --> CQS
    RS --> BAYES
    PS --> BAYES
    QGS --> BAYES
    CQS --> BAYES
    BAYES --> RESHAPE
    RESHAPE --> ELO_R
    ELO_R --> STATS
    STATS --> LEADER_B
    RESHAPE --> CROSS
    RESHAPE --> RETRY_C
    RESHAPE --> GP_RES
    CROSS --> EXPORT
    RETRY_C --> EXPORT
    GP_RES --> EXPORT
    DONE_I --> CREDIT_E
    CREDIT_E --> BALANCE
    DEBIT --> BALANCE
    SHAPLEY_C --> BALANCE
    BALANCE --> STAKE

    style SOURCES fill:#fff8e1,stroke:#F9A825
    style AGGREGATION_S fill:#e3f2fd,stroke:#1565C0
    style ELO_SYS fill:#e8f5e9,stroke:#2E7D32
    style DPO_SYS_R fill:#f3e5f5,stroke:#7B1FA2
    style LEDGER_SYS fill:#fff3e0,stroke:#E65100
```

---

## 10. State Machine — Plan e Item Lifecycle

Tutte le transizioni di stato per Plan e PlanItem con i trigger che le causano.

```mermaid
graph LR
    subgraph PLAN_SM["Plan Status"]
        P_PENDING((PENDING))
        P_RUNNING((RUNNING))
        P_COMPLETED((COMPLETED))
        P_FAILED((FAILED))
        P_PAUSED((PAUSED))
        P_CANCELLED((CANCELLED))

        P_PENDING -->|"Council + Planner done"| P_RUNNING
        P_RUNNING -->|"all items terminal"| P_COMPLETED
        P_RUNNING -->|"critical error"| P_FAILED
        P_RUNNING -->|"attemptsBeforePause"| P_PAUSED
        P_RUNNING -->|"POST /cancel"| P_CANCELLED
        P_PAUSED -->|"POST /resume"| P_RUNNING
        P_PAUSED -->|"POST /cancel"| P_CANCELLED
        P_COMPLETED -->|"ralph-loop reopen"| P_RUNNING
        P_FAILED -->|"POST /retry"| P_RUNNING
    end

    style PLAN_SM fill:#e8f4fd,stroke:#2196F3
```

```mermaid
graph LR
    subgraph ITEM_SM["Item Status"]
        I_WAIT((WAITING))
        I_DISP((DISPATCHED))
        I_RUN((RUNNING))
        I_DONE((DONE))
        I_FAIL((FAILED))
        I_AWAIT((AWAITING<br/>APPROVAL))
        I_TO_D((TO_DISPATCH))
        I_CANCEL((CANCELLED))

        I_WAIT -->|dispatch| I_DISP
        I_WAIT -->|"risk CRITICAL"| I_AWAIT
        I_WAIT -->|"operator skip"| I_DONE
        I_WAIT -->|"POST /cancel"| I_CANCEL
        I_DISP -->|"worker picks up"| I_RUN
        I_DISP -->|"result OK"| I_DONE
        I_DISP -->|"error / max retry"| I_FAIL
        I_DISP -->|"missing_context"| I_WAIT
        I_RUN -->|"result OK"| I_DONE
        I_RUN -->|"error"| I_FAIL
        I_AWAIT -->|"POST /approve"| I_WAIT
        I_AWAIT -->|"POST /reject"| I_FAIL
        I_AWAIT -->|timeout| I_FAIL
        I_DONE -->|"ralph-loop"| I_WAIT
        I_DONE -->|"manual redispatch"| I_TO_D
        I_FAIL -->|"auto-retry backoff"| I_WAIT
        I_FAIL -->|"manual redispatch"| I_TO_D
        I_TO_D -->|dispatch| I_DISP
    end

    style ITEM_SM fill:#fff3e0,stroke:#FF9800
```

---

## 11. Analytics Services — Taxonomy (77+ servizi)

Tutti i servizi analytics organizzati per dominio teorico.

```mermaid
graph TD
    subgraph GAME["Game Theory — 10 servizi"]
        GT1[ShapleyValueService]
        GT2[ShapleyDagService]
        GT3[CausalShapleyService]
        GT4[MarkovShapleyService]
        GT5[VCGMechanismService]
        GT6[ContractTheoryService]
        GT7[ByzantineFaultToleranceService]
        GT8[SuperrationalityService]
        GT9[ReputationStakingService]
        GT10[VotingProtocolService]
    end

    subgraph FINANCE["Finance — 6 servizi"]
        FN1[RealOptionsService]
        FN2[ProspectTheoryService]
        FN3[HedgeAlgorithmService]
        FN4[ErgodicBudgetAnalyzer]
        FN5[ParetoDispatchOptimizer]
        FN6[PotentialRewardShapingService]
    end

    subgraph INFO["Information Theory — 9 servizi"]
        IT1[FisherInformationService]
        IT2[MDLService]
        IT3[InformationBottleneckService]
        IT4[BocpdService]
        IT5[ActiveInferenceService]
        IT6[InformationDirectedSamplingService]
        IT7[InformationForagingService]
        IT8[BayesianSurpriseService]
        IT9[ValueOfInformationService]
    end

    subgraph CTRL["Control Theory — 5 servizi"]
        CT1[ModelPredictiveControlService]
        CT2[HInfinityRobustService]
        CT3[CurriculumPromptingService]
        CT4[ConvergenceMonitor]
        CT5[ContextWindowManager]
    end

    subgraph FORMAL["Formal Methods — 9 servizi"]
        FM1[LTLPolicyVerifier]
        FM2[PetriNetAnalyzer]
        FM3[CSPChannelVerifier]
        FM4[CompressedSensingRetriever]
        FM5[PACBayesService]
        FM6[FunctorialSemanticsService]
        FM7[DescriptionLogicMatcher]
        FM8[StateMachineVerifier]
        FM9[FixedPointAnalyzer]
    end

    subgraph COMPLEX["Complex Systems — 6 servizi"]
        CS1[SpinGlassDispatchService]
        CS2[StigmergyCoordinator]
        CS3[ReplicatorDynamicsService]
        CS4[EdgeOfChaosService]
        CS5[RenormalizationGroupService]
        CS6[PersistentHomologyService]
    end

    subgraph SAFETY["Meta / Safety — 5 servizi"]
        MS1[SycophancyDetectorService]
        MS2[GoodhartDetectorService]
        MS3[ProcessMiningService]
        MS4[SelfRefineGateService]
        MS5[IteratedAmplificationService]
    end

    subgraph DISP["Dispatch — 6 servizi"]
        DS1[MctsDispatchService]
        DS2[MctsOnlinePolicyService]
        DS3[HandoffRouterService]
        DS4[ThompsonSamplingSelector]
        DS5[ReflectiveDispatchService]
        DS6[PlanArchetypeRegistry]
    end

    subgraph OBS["Observability — 10 servizi"]
        OB1[SemanticCacheService]
        OB2[CalibrationAuditService]
        OB3[WorkerDriftMonitor]
        OB4[CouncilDiversityService]
        OB5[SliDefinitionService]
        OB6[SloTracker]
        OB7[ErrorBudgetCalculator]
        OB8[QueuingCapacityPlanner]
        OB9[DecisionTraceService]
        OB10[TaskTypeClassifier]
    end

    subgraph RESIL["Resilience — 8 servizi"]
        RE1[RecoveryRouterService]
        RE2[SandboxExecutionService]
        RE3[MastClassifierService]
        RE4[SelfHealingRouter]
        RE5[ActorModelSupervisor]
        RE6[ChandyLamportSnapshotter]
        RE7[FactorisedBeliefService]
        RE8[ProcessRewardModelService]
    end

    style GAME fill:#e8f4fd,stroke:#2196F3
    style FINANCE fill:#fff8e1,stroke:#F9A825
    style INFO fill:#f3e5f5,stroke:#7B1FA2
    style CTRL fill:#e3f2fd,stroke:#1565C0
    style FORMAL fill:#ede7f6,stroke:#512DA8
    style COMPLEX fill:#fce4ec,stroke:#E91E63
    style SAFETY fill:#ffebee,stroke:#C62828
    style DISP fill:#e8f5e9,stroke:#2E7D32
    style OBS fill:#fff3e0,stroke:#FF9800
    style RESIL fill:#e0f2f1,stroke:#00695C
```

---

## 12. MCP Layer — Tool Access Control

7 MCP server con deny-all baseline, policy a 2 livelli e enforcement runtime.

```mermaid
graph TD
    subgraph WORKER_REQ["Worker Tool Request"]
        W_REQ[Worker invoca tool MCP]
    end

    subgraph DENY["Deny-All Baseline"]
        DENY_ALL["Default: DENY<br/>ogni tool bloccato<br/>salvo allowlist esplicita"]
    end

    subgraph TIER1["Tier 1 — Static Policy"]
        HOOKS["Shell Hooks<br/>enforce-tool-allowlist.sh"]
        ALLOW_YML["Per-WorkerType Allowlists<br/>git.yml, repo-fs.yml<br/>openapi.yml, bash.yml<br/>azure.yml, test.yml, python.yml"]
        ENV_VAR["AGENT_WORKER_TYPE env var<br/>determines policy set"]
    end

    subgraph TIER2["Tier 2 — Dynamic Policy"]
        HM_POL[HOOK_MANAGER<br/>per-task HookPolicy]
        TM_POL[TOOL_MANAGER<br/>minimum tool set per task]
        HOOK_P["HookPolicy JSON<br/>allowedTools, ownedPaths<br/>allowedMcpServers, riskLevel<br/>maxTokenBudget"]
    end

    subgraph ENFORCEMENT["Runtime Enforcement"]
        POE[PathOwnershipEnforcer<br/>verify path in ownedPaths]
        PETCB[PolicyEnforcingToolCallback<br/>Spring AI callback]
        AUDIT_L[AuditLogger<br/>log every call<br/>truncate if over 1KB]
        SECRET[Secret Validator<br/>block API keys, passwords]
    end

    subgraph MCP_SERVERS["7 MCP Servers"]
        MS_GIT[git<br/>commit, branch, diff]
        MS_FS[repo-fs<br/>list, read, write, grep]
        MS_OAPI[openapi<br/>spec parsing, validation]
        MS_AZ[azure<br/>Service Bus, Blob]
        MS_TEST[test<br/>run, coverage]
        MS_BASH[bash<br/>execute, python_execute]
        MS_PY[python<br/>script execution]
    end

    subgraph REDACT["Redaction"]
        RED_RULES[redaction-rules.yml<br/>sanitize secrets in output]
        SANDBOX_L[limits.yml<br/>timeout, memory, CPU]
    end

    W_REQ --> DENY_ALL
    DENY_ALL --> HOOKS
    HOOKS --> ALLOW_YML
    ALLOW_YML --> ENV_VAR
    ENV_VAR --> HM_POL
    HM_POL --> HOOK_P
    TM_POL --> HOOK_P
    HOOK_P --> POE
    POE --> PETCB
    PETCB --> AUDIT_L
    AUDIT_L --> SECRET
    SECRET --> MS_GIT
    SECRET --> MS_FS
    SECRET --> MS_OAPI
    SECRET --> MS_AZ
    SECRET --> MS_TEST
    SECRET --> MS_BASH
    SECRET --> MS_PY
    MS_FS --> RED_RULES
    MS_BASH --> RED_RULES
    MS_BASH --> SANDBOX_L

    style WORKER_REQ fill:#f5f5f5,stroke:#9E9E9E
    style DENY fill:#ffebee,stroke:#C62828
    style TIER1 fill:#fff8e1,stroke:#F9A825
    style TIER2 fill:#e3f2fd,stroke:#1565C0
    style ENFORCEMENT fill:#fff3e0,stroke:#FF9800
    style MCP_SERVERS fill:#e8f5e9,stroke:#2E7D32
    style REDACT fill:#ede7f6,stroke:#512DA8
```

---

## 13. Event Sourcing + Leader Election + SSE

Hybrid event sourcing con leader election guard, late-join replay e tracker sync.

```mermaid
graph LR
    subgraph LEADER_SYS["Leader Election"]
        REDIS_L[(Redis<br/>orchestrator:leader)]
        SET_NX["SET NX + TTL 30s<br/>heartbeat ogni 10s"]
        ACQ[LeaderAcquiredEvent<br/>start consumers]
        LOST[LeaderLostEvent<br/>stop consumers]
        DEMOTE["Conservative demotion<br/>on Redis unavailability"]
    end

    subgraph EVENT_STORE_S["Event Store"]
        APPEND[PlanEventStore<br/>append-only log]
        SEQ[planId + sequenceNumber<br/>monotonic ordering]
        GUARD["Leader guard<br/>only leader appends"]
    end

    subgraph SPRING_EV["Spring Events"]
        PUB[ApplicationEventPublisher]
        CREATED[PlanCreatedEvent]
        DISPATCHED_E[PlanItemDispatchedEvent]
        COMPLETED_E[PlanItemCompletedEvent]
        SIDE_OK[TaskCompletedSideEffectEvent]
        SIDE_FAIL[TaskFailedSideEffectEvent]
    end

    subgraph SSE_SYS["SSE Streaming"]
        REG[SseEmitterRegistry]
        REPLAY["Late-join replay<br/>from store by sequenceNumber"]
        LIVE[Live broadcast<br/>to connected clients]
        ENDPOINT["GET /plans/id/events<br/>Last-Event-ID support"]
    end

    subgraph TRACKER_SYS["Tracker Sync"]
        TSYNC[TrackerSyncService<br/>@Async @EventListener]
    end

    SET_NX --> REDIS_L
    REDIS_L --> ACQ
    REDIS_L --> LOST
    REDIS_L --> DEMOTE
    ACQ --> GUARD
    GUARD --> APPEND
    APPEND --> SEQ
    APPEND --> PUB
    PUB --> CREATED
    PUB --> DISPATCHED_E
    PUB --> COMPLETED_E
    PUB --> SIDE_OK
    PUB --> SIDE_FAIL
    CREATED --> REG
    DISPATCHED_E --> REG
    COMPLETED_E --> REG
    REG --> REPLAY
    REG --> LIVE
    LIVE --> ENDPOINT
    REPLAY --> ENDPOINT
    COMPLETED_E --> TSYNC

    style LEADER_SYS fill:#ffebee,stroke:#C62828
    style EVENT_STORE_S fill:#e3f2fd,stroke:#1565C0
    style SPRING_EV fill:#e8f5e9,stroke:#2E7D32
    style SSE_SYS fill:#fff8e1,stroke:#F9A825
    style TRACKER_SYS fill:#f3e5f5,stroke:#7B1FA2
```

---

## 14. Error Handling + Resilienza

Auto-retry, missing-context loop, saga compensation, approval workflow, ralph-loop e SUB_PLAN recursion.

```mermaid
graph TD
    subgraph AUTO_RETRY["Auto-Retry con Exponential Backoff"]
        FAIL_I[Item FAILED] -->|"delay = base * 2^attempt<br/>jitter +/-25pct"| SCHEDULE[nextRetryAt = now + delay]
        SCHEDULE --> PAUSE_CHK{"attempts >=<br/>attemptsBeforePause?"}
        PAUSE_CHK -->|no| WAIT_R[Item resta FAILED<br/>con nextRetryAt]
        PAUSE_CHK -->|si| PAUSE_P[Plan -- PAUSED<br/>+ PLAN_PAUSED event]
        WAIT_R --> SCHED_P[AutoRetryScheduler<br/>poll ogni 5s]
        SCHED_P -->|"nextRetryAt <= now"| RETRY_I["retryFailedItem<br/>FAILED -- WAITING"]
        RETRY_I --> REDISP[dispatchReadyItems]
        PAUSE_P -->|"POST /resume"| RESUME_P["PAUSED -- RUNNING"]
        RESUME_P --> REDISP
    end

    subgraph MISSING_CTX["Missing-Context Feedback Loop"]
        W_MISS[Worker segnala<br/>missing_context] --> EXTRACT[extractMissingContext]
        EXTRACT --> NEW_CM[Crea task CM/TM<br/>per contesto mancante]
        NEW_CM -->|addDependency| ORIG[Item originale<br/>contextRetryCount++]
        ORIG -->|"DISPATCHED -- WAITING"| REQUEUE[Re-entra in dispatch queue]
        NEW_CM -->|dispatch| CM_W[CONTEXT_MANAGER]
        CM_W -->|DONE| DEPS_OK[deps soddisfatte]
        DEPS_OK --> REDISP_2[Re-dispatch item originale]
    end

    subgraph SAGA["Saga Compensation"]
        COMP_TRIG["POST /compensate"] --> COMP_W[COMPENSATOR_MANAGER<br/>analizza fallimento]
        COMP_W --> GIT_REV[git revert<br/>file restore]
        GIT_REV --> NEW_ITEMS[Nuovi PlanItem correttivi]
        NEW_ITEMS --> REOPEN[Piano riaperto<br/>con nuovi items]
    end

    subgraph APPROVAL["Human-in-the-Loop"]
        HM_RISK[HOOK_MANAGER<br/>riskLevel = CRITICAL] --> AWAIT_A["Item -- AWAITING_APPROVAL"]
        AWAIT_A -->|"POST /approve"| APPROVE_A["WAITING + triggerDispatch"]
        AWAIT_A -->|"POST /reject"| REJECT_A["FAILED + failureReason"]
        AWAIT_A -->|timeout| TIMEOUT_A[FAILED]
    end

    subgraph RALPH["Ralph-Loop Quality Gate"]
        QG_FAIL[Quality Gate failure] --> FEEDBACK[feedback injection<br/>in item context]
        FEEDBACK -->|"DONE -- WAITING"| REDO[Re-dispatch con feedback]
        REDO --> MAX_CHK{"loops >= maxRalphLoops?<br/>default: 2"}
        MAX_CHK -->|no| REDISP_3[dispatch con feedback]
        MAX_CHK -->|si| ACCEPT[Accetta risultato corrente]
    end

    subgraph SUB_PLAN_S["SUB_PLAN Recursion"]
        PARENT[Parent Plan<br/>depth=0] -->|contiene| SP_ITEM[PlanItem SUB_PLAN]
        SP_ITEM --> DEPTH{"depth less than maxDepth?"}
        DEPTH -->|si| CHILD[Child Plan<br/>depth=1]
        DEPTH -->|no| DEPTH_FAIL["FAILED: max depth exceeded"]
        CHILD -->|"tutti items DONE"| CHILD_OK[parent item -- DONE]
        CHILD -->|"qualche item FAILED"| CHILD_FAIL[parent item -- FAILED]
    end

    style AUTO_RETRY fill:#fff3e0,stroke:#FF9800
    style MISSING_CTX fill:#e3f2fd,stroke:#1565C0
    style SAGA fill:#ffebee,stroke:#C62828
    style APPROVAL fill:#fff8e1,stroke:#F9A825
    style RALPH fill:#e8f5e9,stroke:#2E7D32
    style SUB_PLAN_S fill:#ede7f6,stroke:#512DA8
```

---

## 15. Infrastructure e Data Layer

PostgreSQL 18 con 42 Flyway migrations raggruppate, Redis multi-DB e Ollama.

```mermaid
graph TD
    subgraph PG_SYS["PostgreSQL 18 — pgvector + AGE v1.7.0"]
        subgraph CORE_T["Core V1-V4"]
            T_PLAN[plans]
            T_ITEMS[plan_items + deps]
            T_EVENTS[plan_event]
            T_FEEDBACK[quality_gate_feedback]
        end

        subgraph RAG_T["RAG V5-V6"]
            T_VECTOR[vector_store<br/>HNSW cosine 1024-dim]
            T_KG[knowledge_graph<br/>Apache AGE]
            T_CG[code_graph<br/>Apache AGE]
            T_TG[task_graph<br/>Apache AGE]
        end

        subgraph GP_T["GP/DPO V7-V12"]
            T_QGF[quality_gate_feedback<br/>Ralph-Loop V7]
            T_OUTCOMES[task_outcomes<br/>embedding + reward V8]
            T_DPO_R[dpo_gp_residual V9]
            T_SEREN[serendipity V10]
            T_OPTLOCK[optimistic_locking V11]
            T_BAYES[bayesian_success V12]
        end

        subgraph BUDGET_T["Budget V13, V24"]
            T_COST[task_cost_tracking V13]
            T_LEDGER[token_ledger<br/>DEBIT + CREDIT V24]
        end

        subgraph SEC_T["Security V27-V33"]
            T_HASH[hash_chain V27]
            T_POLHASH[policy_hash V28]
            T_WKEYS[worker_keys V29]
            T_REPSTK[reputation_staking V30]
            T_PROMPT[prompt_hash V31]
            T_MERKLE[merkle_dag V32]
            T_COMMIT_T[council_commitments V33]
        end

        subgraph OPS_T["Operational V14-V22"]
            T_HINTS[tool_hints V14]
            T_ARTIFACT[artifact_store V15]
            T_WORKSPACE[workspace_volume V16]
            T_MODEL[plan_item_model_id V18]
            T_COMPMODE[compensation_mode V19]
            T_AUDIT_T[audit_events V20]
            T_CONV[conversation_log V21]
            T_FILEMODS[file_modifications V22]
        end

        subgraph ADV_T["Advanced V23-V42"]
            T_CTX_Q[context_quality V23]
            T_SHAPLEY[shapley_value V25]
            T_SPLIT[task_auto_split V26]
            T_EXEC[execution_runtime V34]
            T_GITSAFE[git_safety V35]
            T_COMPILE[compile_test_fix V36]
            T_XPLAN[cross_plan_knowledge V37]
            T_SELFIMP[self_improving V38]
            T_INTHUB[integration_hub V39]
            T_ELICIT[elicitation V40]
            T_LIFECYCLE[project_lifecycle V41]
            T_BENCH[effectiveness_benchmark V42]
        end
    end

    subgraph REDIS_SYS["Redis 7"]
        RDB3["DB3 — Streams<br/>agent-tasks, agent-results<br/>agent-reviews, agent-advisory"]
        RDB5["DB5 — Semantic Cache<br/>embedding cache 24h TTL<br/>GP model cache<br/>cosine threshold 0.92"]
    end

    subgraph OLLAMA_SYS["Ollama"]
        OLL_EMB["mxbai-embed-large<br/>1024-dim embeddings<br/>ingestion + search"]
        OLL_RANK["qwen2.5:1.5b<br/>cross-encoder reranker<br/>cascade reranking"]
    end

    T_VECTOR --> OLL_EMB
    T_OUTCOMES --> OLL_EMB
    RDB5 --> OLL_EMB
    T_VECTOR --> OLL_RANK

    style PG_SYS fill:#e3f2fd,stroke:#1565C0
    style CORE_T fill:#e8f4fd,stroke:#90CAF9
    style RAG_T fill:#f3e5f5,stroke:#CE93D8
    style GP_T fill:#fff8e1,stroke:#FFE082
    style BUDGET_T fill:#fff3e0,stroke:#FFCC80
    style SEC_T fill:#ffebee,stroke:#EF9A9A
    style OPS_T fill:#e8f5e9,stroke:#A5D6A7
    style ADV_T fill:#ede7f6,stroke:#B39DDB
    style REDIS_SYS fill:#fce4ec,stroke:#E91E63
    style OLLAMA_SYS fill:#e0f2f1,stroke:#00695C
```

---

## 16. Sequenza End-to-End — Lifecycle di un Piano

Flusso temporale completo dal POST API al PLAN_COMPLETED.

```mermaid
sequenceDiagram
    participant U as User
    participant API as PlanController
    participant CO as CouncilService
    participant PL as PlannerService
    participant OR as OrchestrationService
    participant GP as GP Engine
    participant RS as Redis Streams
    participant W as Worker
    participant RW as RewardService
    participant SSE as SSE Client

    U->>API: POST /api/v1/plans (spec + budget)
    API->>API: Create Plan (PENDING)
    API->>CO: conductPrePlanningSession(spec)
    Note over CO: SubmodularSelector CELF<br/>Parallel consult 8 members<br/>Commit-Reveal + QV
    CO-->>API: CouncilReport
    API->>PL: decompose(spec, councilReport)
    Note over PL: Claude LLM call<br/>BeanOutputConverter<br/>Persist Plan + N PlanItems
    PL-->>OR: Plan RUNNING, start orchestration
    OR->>OR: LeaderElection check
    OR->>OR: dispatchReadyItems (Wave 1)
    OR->>GP: predict(taskEmbedding) per profile
    GP-->>OR: GpPrediction (mu, sigma2)
    Note over OR: UCB selection<br/>MCTS if steps >= 4<br/>Ralph-Loop if sigma alta
    OR->>RS: XADD agent-tasks (AgentTask)
    OR-->>SSE: TASK_DISPATCHED event
    RS->>W: XREADGROUP (consumer group per type)
    Note over W: Load skills + Claude AI<br/>MCP tool calls<br/>PathOwnershipEnforcer
    W->>RS: XADD agent-results (AgentResult)
    RS->>OR: consume result, XACK
    OR->>OR: Item DONE, check dependencies
    OR->>RW: computeProcessScore
    OR->>RW: distributeReviewScore
    Note over RW: Bayesian aggregation<br/>4 fonti weighted
    OR-->>SSE: TASK_COMPLETED event
    OR->>OR: dispatchReadyItems (Wave N)
    Note over OR: Repeat waves until<br/>all items terminal
    OR->>RW: ELO update + DPO pairs
    OR->>OR: Plan COMPLETED
    OR-->>SSE: PLAN_COMPLETED event
    OR->>RW: QualityGateService.evaluate
```

---

> **Nota**: Questo documento sostituisce il [diagramma architetturale v1](/agent-framework/architecture/architecture-diagram) che rifletteva lo stato S4-S5 del framework. Il v1 resta disponibile come riferimento storico.
