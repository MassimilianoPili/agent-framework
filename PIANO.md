# Agent Framework — Piano di Evoluzione Architetturale

Questo documento raccoglie le decisioni architetturali prese per l'evoluzione del framework,
incluse le scelte di design motivate e la priorità di implementazione.

---

## Nuovi concetti chiave

### Branching Strategy (sprint/iter/feature)

I task del piano operano su branch git **già esistenti**, dichiarati nel piano al momento della creazione.

```
FLUSSO AMBIENTI (vertical)          FLUSSO SPRINT/ITER (horizontal)
═══════════════════════════         ══════════════════════════════
release/prod
    │
release/collaudo  ◄════════════════ sprint3-iter4 → feature/sprint3/iter4/104
    │                                                → bugfix/sprint3/iter4/105
release/test      ◄════════════════ sprint4-iter1 → feature/sprint4/iter1/103
    │                                                → bugfix/sprint4/iter1/106
develop           ◄════════════════ sprint4-iter2 → feature/sprint4/iter2/101
                                                   → bugfix/sprint4/iter2/102
```

- I numeri nei branch (101, 102...) sono **issue ID del tracker esterno**
- Il merge su sprint/iter è **positivo** (avanzamento), avviene dopo validazione REVIEW worker
- La "compensation" corrisponde a operazioni git manuali sul feature branch

### DB-first (Source of Truth)

Il database interno del framework è la **source of truth** per lo stato di ogni piano.
I sistemi esterni (issue tracker, notifiche) sono **eventually consistent** rispetto al DB:

```
OrchestrationService → DB (write) → ApplicationEvent → TrackerSyncService (async MCP)
                                                      → SseEmitter (browser, opzionale)
```

### Nuovi WorkerType

| WorkerType | Ruolo |
|-----------|-------|
| `TASK_MANAGER` | Estende CONTEXT_MANAGER: recupera branch + issue snapshot via MCP tracker |
| `COMPENSATOR_MANAGER` | Rollback/revert operazioni git di task precedenti |
| `SUB_PLAN` | Virtual type: crea un piano figlio anziché dispatchare un worker |

---

## Roadmap per funzionalità

### 1. Event Sourcing Puro ✅ → [PIANO_HISTORY.md]
### 2. Missing-Context Feedback Loop ✅ → [PIANO_HISTORY.md]
### 3. Retry Automatico con Exponential Backoff ✅ → [PIANO_HISTORY.md]
### 4. Saga / Compensation (COMPENSATOR_MANAGER) ✅ → [PIANO_HISTORY.md]
### 5. SSE + TrackerSyncService

**Problema**: il client fa polling. Gli eventi real-time non vengono esposti né sincronizzati al tracker.

**Soluzione**: `OrchestrationService` pubblica `SpringPlanEvent`. Due consumer:
- `SseEmitterRegistry` → stream HTTP/SSE per browser
- `TrackerSyncService` → sync asincrono al tracker esterno via MCP

```
GET /api/v1/plans/{id}/events  →  SSE stream
event: task_completed
data: {"taskKey":"BE-001","success":true,"durationMs":45000,"branch":"feature/sprint4/iter2/101"}
```

**File**: `SseEmitterRegistry.java` (NEW), `TrackerSyncService.java` (NEW),
`SpringPlanEvent.java` (NEW), `PlanController.java`, `OrchestrationService`

---

### 6. Token Budget per WorkerType ✅ → [PIANO_HISTORY.md]

---

### 7. Context Cache cross-plan (TASK_MANAGER)

**Problema**: ogni piano ri-esplora il repo anche se commit e issue spec non sono cambiati.

**Cache key** (invalidazione event-driven, no TTL):
```
agentfw:ctx-cache:{sha256(repoPath)}:{gitCommitHash}:{workingTreeDiffHash}:{sha256(issueSnapshotJson)}
```

**Meccanismo**: `ContextCacheInterceptor.beforeExecute()` imposta `ThreadLocal<String> CACHED_RESULT`.
`AbstractWorker.process()` controlla il ThreadLocal prima di chiamare `execute()`.

**File**: `Plan.java` (sourceCommit, workingTreeDiffHash), `OrchestrationService.createAndStart()`,
`ContextCacheInterceptor.java` (NEW), `ContextCacheHolder.java` (NEW), `AbstractWorker.java`

---

### 8. Plan DAG REST + Mermaid

**Problema**: nessuna visualizzazione della struttura del piano e del suo stato.

**Endpoint**: `GET /api/v1/plans/{id}/graph?format=mermaid|json`

**Contenuto nodi**: stato + token consumati + durata + branch git + worker type icon
**Contenuto archi**: tipo dipendenza (context | schema | functional) con stile diverso

```
GET /api/v1/plans/{abc}/graph?format=mermaid
→ graph LR
    CM-001["CM-001\nDONE\n1200 tk | 8s\nfeature/sprint4/iter2/101"]:::wt_CONTEXT_MANAGER_st_DONE
    BE-001["BE-001\nRUNNING\n..."]:::wt_BE_st_RUNNING
    CM-001 --context--> BE-001
```

**File**: `PlanGraphService.java` (NEW), `PlanController.java`, `v1.yaml`

---

### 9. Hierarchical Plans (SUB_PLAN)

**Problema**: limite rigido di 20 task. Specifiche complesse non sono decomponibili.

**Soluzione**: `WorkerType.SUB_PLAN` — dispatch crea un piano figlio su branch dedicato.

```java
// dispatchReadyItems():
if (item.getWorkerType() == WorkerType.SUB_PLAN) {
    Plan child = createAndStart(item.getSubPlanSpec(), plan.getDepth() + 1);
    item.setChildPlanId(child.getId());
    item.transitionTo(item.isAwaitCompletion() ? DISPATCHED : DONE);
}
```

**Parametri**:
- `awaitCompletion: boolean` — il padre aspetta il figlio (sequenziale) o no (parallelo)
- `Plan.maxDepth` configurabile in `PlanRequest` (default: 3)

**File**: `WorkerType.java`, `PlanItem.java`, `Plan.java`, `OrchestrationService`,
`PlanRequest.java`

---

### 10. HookPolicy Extensions (Self-Constraining Agent)

**Problema**: `HookPolicy` contiene solo `allowedTools, ownedPaths, allowedMcpServers, auditEnabled`.
Il pattern "l'AI genera i propri vincoli" può essere molto più espressivo.

**Record esteso**:
```java
record HookPolicy(
    List<String> allowedTools,
    List<String> ownedPaths,
    List<String> allowedMcpServers,
    boolean auditEnabled,
    Integer maxTokenBudget,            // worker-level budget
    List<String> allowedNetworkHosts,  // enforced lato MCP server
    ApprovalMode requiredHumanApproval, // NONE | BLOCK | NOTIFY_TIMEOUT
    int approvalTimeoutMinutes,
    RiskLevel riskLevel,               // LOW | MEDIUM | HIGH | CRITICAL
    Integer estimatedTokens,
    boolean shouldSnapshot
)
```

**Nuovo stato**: `ItemStatus.AWAITING_APPROVAL`
```
WAITING → AWAITING_APPROVAL → DISPATCHED → RUNNING → DONE/FAILED
```

**Regole**:
- `CRITICAL` → sempre `AWAITING_APPROVAL`
- `HIGH/CRITICAL` → routing su worker pool dedicato
- `allowedNetworkHosts` → enforcement nel MCP server (non in `PolicyEnforcingToolCallback`)

**Nota tecnica**: `HookPolicy` è duplicato tra `orchestrator` e `worker-sdk`.
Proposta: estrarre in modulo `agent-common` condiviso.

**File**: `HookPolicy.java` (entrambi i moduli), `PlanItem.java`, `ItemStatus.java`,
`OrchestrationService`, `PlanController.java`, `v1.yaml`, MCP server tools

---

### 11. GP per Worker Selection ✅ → [PIANO_HISTORY.md]

---

### 12. Serendipita' nel Context Manager ✅ → [PIANO_HISTORY.md]

---

### 13. Council Taste Profile ✅ → [PIANO_HISTORY.md]

**Problema**: `CouncilService.conductPrePlanningSession()` (riga 97) e' stateless —
non impara da piani passati. Non sa che piani "CRUD API" con 3 task funzionano meglio
di piani con 5 task (overhead coordinamento). Ogni piano parte da zero.

**Soluzione**: GP che predice il reward atteso di una decomposizione dato lo spec embedding
e le caratteristiche strutturali del piano proposto.

**Perche' GP e non regola statica**: le regole ("CRUD = 3 task") non generalizzano.
Il GP interpola: spec "mezzo CRUD, mezzo real-time" riceve predizione pesata.
`sigma^2` dice al Council quando la predizione e' affidabile vs quando serve cautela.

**Perche' in `conductPrePlanningSession` e non nel planner**: il Council opera *prima*
del planner. Il planner riceve il `CouncilReport` come input. Il taste profile arricchisce
il report con raccomandazione sulla struttura — il planner decide se seguirla.
Separazione: Council = advisory, Planner = execution.

**Feature space**: `f(spec_embedding, n_tasks, has_context_task, has_review_task) → predicted_reward`.
Il GP opera su uno spazio low-dimensional (embedding + 3-4 feature strutturali), non sull'intero piano.

**File nuovi**: `shared/gp-engine/PlanDecompositionPredictor.java`.
Modifica `CouncilService.java` (inietta `Optional<PlanDecompositionPredictor>`).
Flyway per tabella `plan_outcomes` (spec embedding + reward finale piano).

**Sforzo**: 2g. **Dipendenze**: #11 (GP engine). **Dati minimi**: ~20 piani completati.

---

### 14. DPO con GP Residual ✅ → [PIANO_HISTORY.md]

---

### 15. Active Learning per Token Budget ✅ → [PIANO_HISTORY.md]

---

### 16. Ralph-Loop (Quality Gate Feedback Loop) ✅ → [PIANO_HISTORY.md]

---

### 17. SDK Scaffold Worker ✅ → [PIANO_HISTORY.md]

---

### 18. ADR-005: GP + Serendipita' ✅ → [PIANO_HISTORY.md]

---

### TASK_MANAGER (nuovo worker type)

**Problema**: `CONTEXT_MANAGER` fornisce solo file rilevanti. Mancano: branch git target,
dati del tracker esterno, acceptance criteria, issue snapshot.

**Soluzione**: `TASK_MANAGER` estende `CONTEXT_MANAGER` aggiungendo:
- Recupero issue dal tracker esterno via MCP tool
- Salvataggio `PlanItem.issueSnapshot` (TEXT JSON) — snapshot al momento del dispatch
- Fornitura branch target al worker (es. `feature/sprint4/iter2/101`)
- Recupero test spec per REVIEW worker

**Principio DB-first**: i dati del tracker vengono snapshotati nel DB interno al momento
della creazione del piano. Il tracker è eventually consistent. Il worker usa sempre i dati
dal DB, non interroga il tracker direttamente.

**File**: nuovo modulo `task-manager-worker/`, aggiornamento `WorkerType.java`,
`PlanItem.java` (campo `issueSnapshot TEXT`)

---

## Dipendenze tra feature

```
ES puro (1) ──────────────────► SSE late join con replay (5b)
ES puro (1) ──────────────────► Compensation audit trail (4)
TASK_MANAGER ─────────────────► Context cache (7) — issueSnapshotHash
Missing-context (2) ──────────► Retry (3) — contesto fresco prima del retry
HookPolicy extensions (10) ───► Token budget (6) — maxTokenBudget task-level
GP Worker Selection (11) ─────► Serendipita' Context Manager (12)
GP Worker Selection (11) ─────► Council Taste Profile (13)
GP Worker Selection (11) ─────► DPO con GP Residual (14)
GP Worker Selection (11) ─────► Active Token Budget (15)
Ralph-Loop (16) ──────────────► (standalone)
SDK Scaffold (17) ────────────► (standalone)
ADR-005 (18) ─────────────────► (standalone, documenta #11-#15)
S8 (Bug fix + resilienza) ───► #22 Orchestrator singleton (usa S8-H consumer resilience)
S8 ──────────────────────────► #19 Retry TO_DISPATCH (S8 stabilizza stato)
B13 (Fix nomi manifest) ────► #24 toolHints (i nomi devono essere corretti prima)
#27 (ToolNames registry) ──► B13, B14, B16 (centralizzazione risolve tutti e 3)
#27 ────────────────────────► S8-L (implementazione centralizzazione in S8)
#25 mcp-bash/python-tool ───► #24 toolHints (bash_execute/python_execute disponibili)
#25 ────────────────────────► #27 (nuovi tool da aggiungere a ToolNames)
B17 (Context overflow) ────► (standalone, 3 livelli di fix indipendenti)
#23 Enrichment Pipeline ─────► #24L2 TOOL_MANAGER ✅ (usa enrichment per generare policy)
#23 ─────────────────────────► (sblocca RAG S1-S3, HOOK_MANAGER, CONTEXT_MANAGER)
#26L1 Cost tracking ─────────► #26L2 Auto-split (necessita costi reali + GP prediction)
#26L2 Auto-split ✅ S15 ─────► (dipende da #11 GP, #20 modelHint)
#20 Modello per task ────────► (standalone)
#21 Topic splitting ─────────► (standalone, futuro)
#25 ─────────────────────────► (standalone, parallelo a #23)
#28 Monitoring Dashboard ────► #5 SSE (infrastruttura eventi)
#28 ─────────────────────────► G1 (conversation history per Worker Detail)
#28 ─────────────────────────► G4 (Prometheus per Stats panel)
B17 L2 (CompactingTCM) ─────► (standalone, BeanPostProcessor nel worker-sdk)
```

---

## Priorità di implementazione

| # | Feature | Sforzo stimato | Impatto | Dipendenze |
|---|---------|---------------|---------|------------|
| 8 | DAG endpoint Mermaid | 0.5g | Medio | — |
| 2+3 | Missing-context + Auto-retry ✅ | 2g | Alto | — |
| 5 | SSE + TrackerSyncService | 1g | Alto | — |
| 6 | Token budget per WorkerType ✅ | 1g | Medio | — |
| TM | TASK_MANAGER worker | 2g | Alto | — |
| 7 | Context cache | 1g | Medio | TASK_MANAGER |
| 1 | Event Sourcing puro ✅ | 5g | Molto alto | — (foundation) |
| 5b | SSE late join con replay | 0.5g | Alto | ES puro |
| 9 | Hierarchical plans | 3g | Alto | — |
| 10 | HookPolicy extensions | 2g | Alto | — |
| 4 | COMPENSATOR_MANAGER ✅ | 2g | Medio | — |
| lib | Modulo agent-common (HookPolicy) | 0.5g | Medio | — |
| **RAG** | **RAG Pipeline + Graph RAG (3 sessioni) ✅** | **10g** | **Molto alto** | — |
| 16 | Ralph-Loop (Quality Gate Feedback) ✅ | 1.5g | Alto | — |
| 17 | SDK Scaffold Worker ✅ | 0.5g | Basso | — |
| 11 | GP Worker Selection ✅ | 3g | Molto alto | pgvector, Ollama |
| 14 | DPO con GP Residual ✅ | 1g | Alto | #11 |
| 12 | Serendipita' Context Manager ✅ | 2g | Alto | #11 |
| 13 | Council Taste Profile ✅ | 2g | Medio | #11 |
| 15 | Active Token Budget ✅ | 1g | Medio | #11 |
| 18 | ADR-005 (GP Motivazioni) ✅ | 0.5g | Medio | — |
| **S8** | **Fix bug critici + resilienza (B1-B7, B9, B14-B16) ✅** | **4g** | **Critico** | #27 (per S8-L) |
| **#27** | **Centralizzazione nomi tool (ToolNames registry) ✅** | **0.5g** | **Alto** | — (prerequisito) |
| **B13 ✅** | **Fix nomi tool nei 22 manifest** | **0.5g** | **Alto** | #27 |
| **B14 ✅** | **Fix Mustache template write-tool-names** | **0.5g** | **Alto** | #27 |
| **B15 ✅** | **Project path dinamico in ownsPaths** | **0.5g** | **Alto** | — |
| **B16 ✅** | **HOOK_MANAGER schema con nomi Claude Code** | **0.5g** | **Alto** | #27 |
| **B17 ✅** | **Context overflow da `fs_read` senza limiti** | **1.5g** | **Critico** | — |
| **23 ✅** | **Enrichment Pipeline Activation** | **2g** | **Molto alto** (sblocca S1-S3) | — |
| **25 ✅** | **mcp-bash-tool + mcp-python-tool** | **1.5g** | **Alto** | — |
| **24 ✅** | **Tool configurabili (Livello 1: toolHints)** | **1g** | **Alto** | B13, #25 |
| **19 ✅** | **Retry manuale TO_DISPATCH** | **1g** | **Alto** | S8 |
| 20 | Modello LLM per task ✅ | 2g | Alto | — |
| 22 | Orchestrator singleton (leader election) ✅ | 1.5g | Medio-alto | S8-H |
| **26L1 ✅** | **Cost tracking per task** | 0.5g | Alto | — |
| **24L2 ✅** | **Tool configurabili (Livello 2: TOOL_MANAGER)** | 1g | Medio | #23 |
| **26L2 ✅** | **Auto-split task costosi** | 1.5g | Medio | #26L1, #11, #20 |
| 21 | Redis topic splitting | 1g | Basso | — |
| **28** | **Monitoring Dashboard UI (real-time)** ✅ S14 | **3g** | **Alto** | #5 (SSE), G1, G4, G6 |
| **29 ✅** | **Worker Lifecycle Management (kill, singleton, JVM-per-type)** | **3.5g** | **Alto** | Phase 1+1b (`a09df71`), Phase 2 hybrid (`9a4c580`) |
| **B18** | **No singleton per task — double processing** | — | **Alto** | Risolto da #29 (ConcurrentHashMap) |
| **B19** | **DispatchAttempt orfani — non-unique result** | **0.5g** | **Alto** | Query fix ✅ applicato, cleanup strutturale futuro |

---
---

# Stato implementazione #1-#49 (audit codice)

Verifica effettiva del codice nel repository (non solo piano). Aggiornato: 2026-03-09.

## Non implementati (12 item — nessun codice)

| # | Item |
|---|------|
| 21 | Redis topic splitting per workerType |
| 32 | Policy-as-Code Immutabile |
| 34 | Federazione Multi-Server |
| 41 | Topological Pattern Detection |
| 42 | Global Task Assignment (combinatoria) |
| 43 | Differential Privacy metriche |
| 44 | Execution Sandbox Containerizzato |
| 45 | Merkle Tree DAG Verification |
| 46 | Verifiable Council Deliberation |
| 47 | Reputation Staking |
| 48 | Content-Addressable Storage |
| 49 | Quadratic Voting Council |

## Parzialmente implementati (14 item — codice base, estensioni da fare)

| # | Item | Cosa c'e' | Cosa manca |
|---|------|-----------|------------|
| 5 | SSE + TrackerSync | `SseEmitterRegistry` (147 righe), endpoint `/events`, late-join replay | TrackerSync non wired |
| 10 | HookPolicy extensions | `HookPolicyResolver.DEFAULT_TOOL_ALLOWLISTS` per WorkerType, `PolicyEnforcingToolCallback` L1+L2 | Policy dinamiche runtime, L3 enforcement |
| 7 | Context Cache (TASK_MANAGER) | `ContextCacheService` (Redis, 99 righe), `ContextCacheInterceptor` (88 righe), `ContextCacheHolder`, `ContextCacheStore` SPI + `NoOpContextCacheStore`, `RedisContextCacheStore` ✅ (Redis-backed SPI, TTL 30min, `@ConditionalOnBean`), `AbstractWorker` integration (cache hit → skip LLM), `OrchestrationService` integration (PUT/GET), `Plan.sourceCommit`/`workingTreeDiffHash`, `PlanItem.issueSnapshot`, test completi (15 test: `ContextCacheInterceptorTest` + `RedisContextCacheStoreTest`) | TASK_MANAGER worker (bloccato da tracker-mcp) |
| 8 | DAG + Mermaid | `PlanGraphService` (227 righe), `toMermaid()`, endpoint `/graph` | Miglioramenti UI |
| 9 | Hierarchical Plans | `handleSubPlan()`, `SUB_PLAN` WorkerType, child plan | Estensioni previste |
| 35 | Context Quality Scoring | `ContextQualityService` (file relevance + entropy proxy), V23 migration, 4° reward source in `RewardComputationService` (0.15), slot [1027] in `BayesianSuccessPredictor` popolato, side-effect #7 in `TaskCompletedEventHandler` | Test unitari, tuning pesi, validazione con dati reali |
| 33 | Token Economics Double-Entry | `TokenLedger` entity, `TokenLedgerRepository` (7 query), `TokenLedgerService` (debit/credit/creditShapley/balance/efficiency/burnRate/perWorkerType), `TokenLedgerResponse` DTO (byWorkerType, burnRatePerMinute), `TokenLedgerProperties`, V24 migration, integrazione in `OrchestrationService` (debit dopo recordUsage), side-effect #8 in `TaskCompletedEventHandler` (credit da aggregatedReward), `GET /{id}/budget/ledger` endpoint con breakdown per workerType, metriche Prometheus (`orchestrator.ledger.debit/credit`), alert LOW_EFFICIENCY via SpringPlanEvent, 25 test unitari ✅ S16 | Tuning credit formula, dashboard Grafana |
| 37 | Adaptive Token Budget (PID) | `PidBudgetController` (PID in-memory per planId×workerType), `PidBudgetProperties`, integrazione in `OrchestrationService` (adjustPolicy dispatch, update completion, evictPlan cleanup), 10 test unitari | Tuning parametri PID con dati reali, metriche Prometheus, dashboard Grafana |
| 30 | Hash Chain Tamper-Proof | `HashChainVerifier` (SHA-256 chain verification), `HashChainVerificationResult` DTO, endpoint `GET /{id}/verify-chain` in PlanController, test unitari ✅ S15 | Integrazione auto-append hash su PlanEvent save, UI dashboard |
| 40 | Shapley Value Reward Distribution | `ShapleyDagService` (Monte Carlo DAG-aware, Kahn's random topo-sort), V25 migration (`shapley_value` su plan_items), `ShapleyDagResponse` DTO, `creditShapley()` in `TokenLedgerService`, side-effect #9 in `TaskCompletedEventHandler` (trigger su allDone), endpoint `GET /shapley-dag` + `GET /{id}/shapley`, 14 test unitari | Tuning K samples, integrazione ELO update con Shapley, dashboard Grafana |
| 36 | Worker Pool Sizing (Queueing Theory) | `QueueAnalyzer` (Erlang C + Little's Law + CPM delegation), `QueuingCapacityPlanner` (M/G/1 P-K, 8 test), `CriticalPathCalculator` + `TropicalScheduler` (CPM), endpoint `GET /{id}/queue-analysis`, 15 test unitari ✅ S17 | Dashboard Grafana, live queue depth monitoring, multi-worker aggregate |
| 38 | State Machine Verification (LTL) | `StateMachineVerifier` (BFS model checker, product state space), `@AllowedViolation` annotation, `StateMachineVerificationResult` DTO, 10 test unitari ✅ S15 | Integrazione CI/CD, verifica automatica su schema change |
| 39 | Policy Lattice Composition | `PolicyLattice` (meet-semilattice, TOP/BOTTOM, wildcard handling), 17 test unitari ✅ S15 | Integrazione in `HookManagerService.resolvePolicy()` |
| 31 | Verifiable Compute (Ed25519) | `Ed25519Signer` (keygen, sign, verify, key encode/decode — pure Java 21), `SignedResultEnvelope` (record con `sign()` factory + `verify()` TOFU/trusted), 12 test unitari ✅ S18 | Worker-SDK wiring (AbstractWorker signing), Flyway V11 (worker_keys), key registry endpoint, AgentResultConsumer verification |
| 42 | Global Task Assignment (Hungarian) | `HungarianAlgorithm` (Kuhn-Munkres O(n³), standalone, gestisce matrici rettangolari e +INF), `GlobalAssignmentSolver` (cost matrix da GP predictions, critical path boost, `@ConditionalOnProperty`), `AssignmentResult` DTO (assignments, predictions, totalCost, criticalPath, details), `GlobalAssignmentProperties` + `GlobalAssignmentAutoConfiguration`, integrazione in `OrchestrationService` (pre-assign batch prima del loop per-item, param 36), `PlanController` (`GET /{id}/assignment-preview`, param 17), config `global-assignment:` in application.yml, 15 test unitari (9 HungarianAlgorithm + 6 GlobalAssignmentSolver) ✅ S16 | Tuning critical-path-boost con dati reali, metriche Prometheus, dashboard Grafana |

**Nota**: #5, #8, #9 presenti dall'initial commit (`2c5d7cc`). Il piano li elenca come "da fare"
perche' richiedono estensioni rispetto all'implementazione base.

---
---

# Bug noti e fix pianificati

Bug emersi dall'esplorazione del codice e dall'uso reale del framework.

## Catena cascante critica

3 bug collegati — causa root dei problemi riscontrati: task bloccato DISPATCHED, risultato ignorato, piano mai completo.

| # | Bug | Severita' | File / Righe | Root cause | Fix proposto |
|---|-----|-----------|-------------|------------|-------------|
| B1 ✅ | **ACK prima di commit** — `AgentResultConsumer.handleMessage()` fa `ack.reject()` (XACK + DLQ) anche se la transazione di `onTaskCompleted` ha fatto rollback. Il messaggio e' perso, il task resta DISPATCHED. | CRITICAL | `AgentResultConsumer.java:58-71`, `RedisStreamAcknowledgment.java:44-58` | ACK avviene fuori dal boundary transazionale Spring | Spostare XACK dentro la transazione, oppure usare pattern "ACK after commit" con `TransactionSynchronization.afterCommit()`. Se rollback → non ACK → Redis ri-consegna il messaggio. |
| B2 ✅ | **Idempotency guard incompleto** — `onTaskCompleted()` guarda solo DONE/FAILED. Se un'eccezione causa rollback (reward computation, GP update), l'item resta DISPATCHED ma il messaggio e' in DLQ → task bloccato per sempre. | CRITICAL | `OrchestrationService.java:199-203` | Guard non copre DISPATCHED; side-effect (reward, GP, serendipity) nel path critico senza isolamento | Separare side-effect non critici in `@TransactionalEventListener(AFTER_COMMIT)`. La transizione DISPATCHED→DONE deve avere successo anche se i side-effect falliscono. |
| B3 ✅ | **Piano mai completo** — conseguenza di B1+B2: `findActiveByPlanId` trova item DISPATCHED (non terminale) → `checkPlanCompletion` esce subito → piano resta RUNNING per sempre | CRITICAL | `OrchestrationService.java:696-710`, `PlanItemRepository.java:22-23` | Cascata da B1+B2 | Risolvere B1+B2. Aggiungere "stale task detector" schedulato che marca come FAILED task DISPATCHED da piu' di X minuti senza risultato. |

## Bug aggiuntivi

| # | Bug | Severita' | File / Righe | Fix proposto |
|---|-----|-----------|-------------|-------------|
| B4 ✅ | **AutoRetryScheduler transazione unica** — `@Transactional` sull'intero loop. Se un retry fallisce, rollback di TUTTI. `nextRetryAt` resettato per tutti → retry immediato infinito. | HIGH | `AutoRetryScheduler.java:40-60` | Transaction separata per item (`REQUIRES_NEW`) o try-catch con restore `nextRetryAt` |
| B5 ✅ | **Missing context error propagation** — se il CM task creato per fornire contesto fallisce, il task padre resta WAITING per sempre (nessuna propagazione fallimento) | HIGH | `OrchestrationService.java:804-835` | Se CM fallisce → propagare FAILED al task padre. Oppure count retry e fallire dopo N tentativi. |
| B6 ✅ | **LazyInitializationException** — `item.getPlan()` con FetchType.LAZY in `onTaskCompleted()` e `retryFailedItem()` | HIGH | `OrchestrationService.java:281,356` | `JOIN FETCH plan` nella query repository |
| B7 ✅ | **Race condition child plan completion** — no `@Version`, concurrent `onChildPlanCompleted` puo' sovrascrivere | HIGH | `OrchestrationService.java:975-1011` | Aggiungere `@Version` a PlanItem e Plan entities |
| B8 | **Dependency results "1 vs 3"** — task FE-001 con 3 dipendenze (AI-001/AI-002/AI-003) logga "with 1 dependency results". `buildContextJson()` filtra `item.getDependsOn()` contro `completedResults`. | MEDIUM | `OrchestrationService.java:748-759`, `AgentContextBuilder.java:67-80` | Aggiungere log diagnostico: `log.info("Task {} dependsOn={}, completedKeys={}", ...)`. Verificare che il planner popoli correttamente `dependsOn`. |
| B9 ✅ | **Consumer group pending messages (PEL)** — al riavvio dell'orchestratore, `XREADGROUP` con `>` legge solo nuovi messaggi. Messaggi pending (in-flight al crash) non vengono reclamati → risultati persi. | MEDIUM | `RedisStreamListenerContainer.java` | Aggiungere `XAUTOCLAIM` all'avvio per reclamare messaggi pending oltre un idle timeout. |
| B10 ✅ | **Compensation task semantics** — riapertura plan COMPLETED/FAILED ambigua | MEDIUM | `OrchestrationService.java:365-430` | Fix: percorso COMPLETED separato (preserva `completedAt`), evento `PLAN_COMPENSATION_STARTED` nell'event store. |
| B11 ✅ | **Token budget check ordering** — GP prediction calcolata prima del budget check (costo non contabilizzato) | LOW | `OrchestrationService.java:629-650` | Fix: pre-check budget conservativo (`gpPrediction=null`) prima dell'inferenza GP. Se FAIL → skip GP e fail immediato. |
| B12 | **Optional service null checks** — `gpTaskOutcomeService` potrebbe essere null in path non protetti | LOW | `OrchestrationService.java:124-126, 549-554` | Usare Optional consistently |
| B13 ✅ | **Tool names errati in tutti i 22 manifest** — i manifest `.agent.yml` elencano tool con nomi Claude Code (`Read`, `Write`, `Edit`, `Bash`, `Glob`, `Grep`) che non esistono nel registro Spring AI MCP. I worker non trovano i tool e partono con 0/N tools. | HIGH | `agents/manifests/*.agent.yml` (tutti i 22 file) | Correggere i nomi con la mappatura MCP reale. `Edit` e `Grep` non hanno equivalente MCP → rimuovere. Aggiungere sempre `fs_list`. |
| B14 ✅ | **Mustache template hardcoda write-tool-names errati** — `application.yml.mustache` (righe 30-32) genera `write-tool-names: [Write, Edit]` hardcoded, ignorando i nomi MCP reali. `PolicyProperties.java` ha il default corretto (`["Write", "Edit", "fs_write"]`), ma il template lo sovrascrive. Risultato: `PathOwnershipEnforcer` non controlla mai `fs_write` perche' non e' nella lista dei write-tool-names → un worker puo' scrivere ovunque con `fs_write` senza violazione path ownership. | HIGH | `agent-compiler-maven-plugin/.../templates/application.yml.mustache:30-32`, `PolicyProperties.java:37` | Aggiornare il template Mustache per generare `write-tool-names` dinamicamente dal manifest (includendo `fs_write`, `bash_execute`, etc.) oppure rimuovere la sezione hardcoded e lasciare i default di `PolicyProperties`. |
| B15 ✅ | **ownsPaths statici — manca il project path dinamico** — i manifest dichiarano `ownsPaths` statici (`backend/`, `frontend/`, `docs/`, `eval/`). Ma il path del progetto in corso (es. `cps4/`) non viene propagato dall'orchestratore. Un `ai-task` con `ownsPaths: [eval/]` non puo' scrivere in `cps4/` anche se il task lo richiede. L'orchestratore deve almeno propagare il path del piano/progetto come owned path per ogni worker. | HIGH | `agents/manifests/*.agent.yml` (ownsPaths), `AgentTaskProducer.java`, `WorkerTaskConsumer.java`, `PathOwnershipEnforcer.java:49-99` | L'orchestratore aggiunge il project path (dal campo `Plan.projectPath` o dalla spec del piano) ai `ownsPaths` del task al momento del dispatch. Design: `AgentTask.dynamicOwnsPaths` (lista propagata via Redis) + merge con i `ownsPaths` statici dal manifest nel `PathOwnershipEnforcer`. |
| B16 ✅ | **HOOK_MANAGER schema con nomi Claude Code** — `hook-manager.agent.yml:34` mostra esempio `["Read", "Write", "Edit", "Glob", "Grep", "Bash"]`. Il HOOK_MANAGER worker (LLM) genera HookPolicy con questi nomi. `PolicyEnforcingToolCallback` al livello 2 blocca i tool MCP reali (`fs_read`, `fs_write`) perche' non sono nell'allowlist della policy. Il fallback statico `HookPolicyResolver.DEFAULT_TOOL_ALLOWLISTS` ha i nomi corretti — ma viene usato solo se il HOOK_MANAGER non ha generato policy. | HIGH | `hook-manager.agent.yml:34`, `PolicyEnforcingToolCallback.java:113-125`, `HookPolicyResolver.java:35-46` | Aggiornare lo schema in `hook-manager.agent.yml` con nomi MCP reali. Centralizzare i nomi in un'unica source of truth (vedi #27). |
| B18 ✅ | **No singleton per task — double processing** — risolto con `RedisTaskLockService` (SETNX + Lua release + heartbeat 60s). Integrato in `WorkerTaskConsumer`: lock acquisito prima del processing, rilasciato nel finally. Se lock fallisce → ACK senza processing. | HIGH | `RedisTaskLockService.java`, `WorkerTaskConsumer.java` | Risolto S12. |
| B17 ✅ | **Context overflow da `fs_read` senza limiti** — un worker (es. AI-001 audit) che chiama `fs_read` su file grandi (HTML ~80KB, CSS ~80KB, JS ~30KB) accumula migliaia di token per ogni chiamata. Con un sito di 11 pagine (es. CPS), 48 tool call portano il prompt a 208K token, superando il limite di 200K del modello. Il LLM non ha visibilita' sul budget token residuo e continua a leggere file finche' non viene troncato. Nessun meccanismo di protezione: ne' `maxTokens` nel manifest, ne' auto-compacting nel worker SDK, ne' `limit` parametro nel tool `fs_read`. | CRITICAL | `WorkerChatClient.java` (nessun token counting pre-call), `fs_read` tool (nessun parametro `limit`), manifests `*.agent.yml` (nessun campo `maxTokens`) | Fix a 3 livelli: **(1)** campo `maxTokens` nel manifest → il worker SDK lo propaga come `maxOutputTokens` nella chat request (quick fix); **(2)** **auto-compacting nel worker SDK** — prima di ogni tool call, stimare token accumulati (system + messaggi + tool results). Se si supera una soglia (es. 75% del context window), compattare la storia: sostituire i risultati vecchi di tool call con un riassunto LLM compresso (un `fs_read` da 80KB → "file X: 1400 righe HTML, struttura header/nav/main/footer, Bootstrap 5" — da ~20K a ~200 token). Il worker continua a lavorare con contesto compresso, senza interrompere il task. Pattern analogo a Claude Code auto-compress; **(3)** parametro `limit` (righe/byte) nel tool `fs_read` — il worker sceglie quanto leggere per file (prevenzione upstream). Livello 2 e' il fix architetturale — il worker non crasha mai, si auto-compatta. |
| B19 ✅ | **DispatchAttempt orfani — non-unique result** — risolto con fix immediato (`ORDER BY attemptNumber DESC LIMIT 1`) + fix strutturale (`closeOrphanedAttempts(itemId)` in `retryFailedItem`). | HIGH | `DispatchAttemptRepository.java`, `OrchestrationService.java` | Risolto S12. |

## B17 Livello 2 — Design dettagliato: CompactingToolCallingManager

### Architettura del loop Spring AI (analisi del codice sorgente)

```
AnthropicChatModel.internalCall()
  → chiama API Anthropic → riceve tool_use nella risposta
  → chiama toolCallingManager.executeToolCalls()
    → esegue i tool MCP (fs_read, fs_write, etc.)
    → costruisce conversationHistory = previousMessages + assistantMessage + toolResponseMessage
  → internalCall() si richiama RICORSIVAMENTE con new Prompt(conversationHistory, options)
  → ad ogni ciclo, la history CRESCE: +1 assistant message + N tool results
  → dopo 48 cicli con file grandi → 208K token → CRASH
```

### Punto di intercettazione: ToolCallingManager (Spring DI)

```java
// AnthropicChatAutoConfiguration.java (Spring AI)
@Bean
@ConditionalOnMissingBean
public AnthropicChatModel anthropicChatModel(... ToolCallingManager toolCallingManager ...) {
    // ToolCallingManager iniettato tramite DI
}
```

`@ConditionalOnMissingBean` → se definiamo un bean `ToolCallingManager` nel worker-sdk,
Spring lo inietta in `AnthropicChatModel` al posto del `DefaultToolCallingManager`.
**Zero modifiche** ad AnthropicChatModel o Spring AI.

### Design: CompactingToolCallingManager

```java
public class CompactingToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;    // DefaultToolCallingManager
    private final int contextWindowTokens;         // es. 200_000
    private final double compactionThreshold;      // es. 0.75 (75%)

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        // 1. Delega l'esecuzione reale al DefaultToolCallingManager
        ToolExecutionResult result = delegate.executeToolCalls(prompt, response);

        // 2. Stima token della conversationHistory risultante
        List<Message> history = result.conversationHistory();
        int estimatedTokens = estimateTokens(history);

        // 3. Se sotto soglia → passthrough
        if (estimatedTokens < contextWindowTokens * compactionThreshold) {
            return result;
        }

        // 4. Se sopra soglia → compatta i tool result piu' vecchi
        List<Message> compacted = compactOldToolResults(history);
        return new ToolExecutionResult(compacted, result.toolResponseMessage());
    }

    private List<Message> compactOldToolResults(List<Message> history) {
        // Preserva: system message + ultimi N messaggi (es. ultimi 6: 3 coppie assistant+tool)
        // Compatta: i tool result piu' vecchi → sommario di 1-2 righe ciascuno
        // Es: "fs_read index.html: 1400 righe HTML, Bootstrap 5, header/nav/main/footer"
        // Da ~20K token a ~200 token per tool result
    }

    private int estimateTokens(List<Message> messages) {
        // Proxy: somma caratteri / 4 (approssimazione Claude)
        return messages.stream()
            .mapToInt(m -> m.getText().length() / 4)
            .sum();
    }
}
```

### Registrazione nel worker-sdk: BeanPostProcessor (approccio raccomandato)

Il problema con `@Bean` + `@ConditionalOnMissingBean`: il `CompactingToolCallingManager` ha bisogno
del `DefaultToolCallingManager` come delegate, che a sua volta richiede il `ToolCallbackResolver`.
Definire il bean sostitutivo crea un chicken-and-egg nelle dipendenze.

**Soluzione**: `BeanPostProcessor` che wrappa il `ToolCallingManager` GIA' CREATO da Spring AI:

```java
// CompactingToolCallingManagerPostProcessor.java
@Component
public class CompactingToolCallingManagerPostProcessor implements BeanPostProcessor {

    @Value("${agent.context-window-tokens:200000}")
    private int contextWindow;

    @Value("${agent.compaction-threshold:0.75}")
    private double threshold;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ToolCallingManager original) {
            return new CompactingToolCallingManager(original, contextWindow, threshold);
        }
        return bean;
    }
}
```

Vantaggi:
- Zero conflitto con auto-configuration Spring AI
- Il `DefaultToolCallingManager` viene creato normalmente con tutte le sue dipendenze
- Il post-processor lo wrappa DOPO la creazione → `AnthropicChatModel` riceve il wrapper
- Funziona con qualsiasi implementazione futura di `ToolCallingManager`

### Configurazione

```yaml
# application.yml (o manifest-driven)
agent:
  context-window-tokens: 200000   # limite modello (Claude: 200K)
  compaction-threshold: 0.75      # compatta al 75% = 150K token stimati
  compaction-keep-recent: 6       # preserva ultimi 6 messaggi (3 coppie)
```

### File coinvolti

| File | Azione |
|------|--------|
| `CompactingToolCallingManager.java` (NEW) | Wrapper con logica di compaction |
| `WorkerAutoConfiguration.java` | Registra bean `ToolCallingManager` |
| `application.yml` / manifest | Configurazione soglia e context window |

### Strategia di compaction

I tool result vecchi vengono sostituiti con un sommario strutturato:
```
[COMPACTED] fs_read src/index.html (originale: ~20K token)
→ File HTML, 1400 righe, struttura: <!DOCTYPE html>, <head> con Bootstrap 5 CDN,
  <nav> con 8 link, <main> con 3 section (hero, features, contact), <footer>.
  Nessun CSP header, nessun nonce su script inline.
```

Il sommario puo' essere generato:
- **Approccio A (semplice)**: troncamento a N righe + "... [troncato, originale X righe]"
- **Approccio B (smart)**: chiamata LLM leggera (haiku) per riassumere
- **Approccio C (euristico)**: prime 5 righe + ultime 5 righe + statistiche (righe, dimensione, tipo MIME)

Raccomandazione: partire con **Approccio A** (zero costo, zero latenza), passare a B per tool result > 10K token.

---

## Mappatura nomi tool Claude Code → MCP reale (B13)

| Nome Claude Code | Nome MCP reale | Note |
|------------------|----------------|------|
| `Read` | `fs_read` | Legge contenuto file |
| `Write` | `fs_write` | Crea/sovrascrive file |
| `Edit` | _(nessuno)_ | Il LLM puo' fare `fs_read` + `fs_write` |
| `Bash` | _(nessuno)_ | Nessun tool shell execution (→ vedi #25 `mcp-bash-tool`) |
| `Glob` | `fs_search` | Cerca file per glob pattern |
| `Grep` | `fs_grep` | Cerca contenuto nei file (regex) |
| _(mancante)_ | `fs_list` | Browse directory — **sempre incluso**, essenziale per orientamento LLM |

> **Nota**: questa tabella copre solo `mcp-filesystem-tools` (5 tool). Il server MCP registra **~548 tool** da 12+ librerie.

### Catalogo librerie MCP tool (~548 tool totali)

| Libreria | Prefisso | # Tool | Esempi |
|----------|----------|--------|--------|
| `mcp-filesystem-tools` | `fs_*` | 5 | `fs_list`, `fs_read`, `fs_write`, `fs_search`, `fs_grep` |
| `mcp-azure-tools` | `azure_*` | ~188 | `azure_list_vms`, `azure_create_storage_account`, `azure_get_aks_cluster` |
| `mcp-devops-tools` | `devops_*` | ~74 | `devops_list_repos`, `devops_create_work_item`, `devops_trigger_pipeline` |
| `mcp-ocp-tools` | `ocp_*` | ~43 | `ocp_list_pods`, `ocp_scale_deployment`, `ocp_get_pod_logs` |
| `mcp-jira-tools` | `jira_*` | ~28 | `jira_search_issues`, `jira_create_issue`, `jira_transition_issue` |
| `mcp-docker-tools` | `docker_*` | ~23 | `docker_list_containers`, `docker_pull_image`, `docker_inspect_network` |
| `mcp-playwright-tools` | `playwright_*` | ~16 | `playwright_navigate`, `playwright_click`, `playwright_screenshot` |
| `mcp-sql-tools` | `db_*` | ~6 | `db_query`, `db_tables`, `db_count`, `db_list_schemas` |
| `mcp-mongo-tools` | `mongo_*` | ~6 | `mongo_find`, `mongo_aggregate`, `mongo_list_collections` |
| `mcp-embeddings-tools` | `embeddings_*` | ~6 | `embeddings_search`, `embeddings_reindex`, `embeddings_stats` |
| `mcp-graph-tools` | `graph_*` | ~6 | `graph_query`, `graph_write`, `graph_schema` |
| Misc (API, SIMOGE) | `api_*` | ~5 | `api_get`, `api_post`, `api_health` |

Ogni worker vede solo i tool dichiarati nel suo manifest (`tools:` allowlist). Il `WorkerChatClientFactory` filtra il set completo.

## 3 livelli di tool policy (architettura enforcement)

I nomi dei tool attraversano tre livelli di enforcement, TUTTI devono usare gli stessi nomi MCP reali:

| Livello | Componente | Quando | Effetto | File |
|---------|-----------|--------|---------|------|
| **1. Manifest allowlist** | `WorkerChatClientFactory` | Creazione ChatClient | Filtra tool prima che il LLM li veda (il LLM non sa che esistono) | `WorkerChatClientFactory.java:84-90` |
| **2. Task-level policy** | `PolicyEnforcingToolCallback` | Ogni chiamata tool a runtime | Blocca esecuzione tool non in `HookPolicy.allowedTools` (il LLM li vede ma non li puo' usare) | `PolicyEnforcingToolCallback.java:113-125` |
| **3. Path ownership** | `PathOwnershipEnforcer` | Solo write/read tools | Blocca tool che scrivono fuori da `ownsPaths` o leggono fuori da `relevantFiles` | `PathOwnershipEnforcer.java:49-99` |

**Stato attuale**: Livello 1 ✅ (corretto dopo B13). Livello 2 ✅ (B16 — HOOK_MANAGER schema corretto con nomi MCP). Livello 3 ✅ (B14 — template non hardcoda piu', B15 — projectPath wired).

**Nomi tool sparsi in 6 file diversi** — necessaria centralizzazione (#27):

| # | File | Nomi usati | Stato |
|---|------|------------|-------|
| 1 | `HookPolicyResolver.java:35-46` | `fs_list, fs_read, fs_write, fs_search` | ✅ Corretto |
| 2 | `PolicyProperties.java:43` | `ToolNames.WRITE_TOOLS` (default) | ✅ Corretto (#27) |
| 3 | `PathOwnershipEnforcer.java:117` | `ToolNames.isReadTool/isWriteTool` | ✅ Corretto (#27) |
| 4 | `hook-manager.agent.yml:34` | `fs_list, fs_read, fs_write, fs_search, fs_grep, bash_execute` | ✅ Corretto (B16) |
| 5 | `application.yml.mustache:30-32` | Rimosso (usa default PolicyProperties) | ✅ Corretto (B14) |
| 6 | Worker generati (`toolAllowlist()`) | `fs_list, fs_read, fs_write, fs_search` | ✅ Corretto |

---
---

# Sessione 8 — Fix bug critici + resilienza ✅ → [PIANO_HISTORY.md]

---
---

# Roadmap items #19-#26

## #19 ✅ — Retry manuale via DB `TO_DISPATCH` → [PIANO_HISTORY.md]

---

## #20 ✅ — Decisione modello LLM per task (planner) → [PIANO_HISTORY.md]

**Problema**: il modello LLM e' fissato a build-time nel manifest del worker (`.agent.yml` → `spec.model.name`).
Il planner non puo' scegliere il modello per task in base alla complessita'.

**Soluzione**: il planner include `modelHint` nel piano generato. L'orchestratore lo propaga al
dispatch come override del modello default del worker.

**Design**:
- `PlanItem.modelHint` — nuovo campo (nullable, es. `claude-haiku-4-5-20251001`)
- `PlanSchema` (output planner) — nuovo campo opzionale `modelHint` per task
- `AgentTask.modelOverride` — propagato nel messaggio Redis al worker
- `WorkerTaskConsumer` — se `modelOverride` presente, override `ChatOptions.model` a runtime
- `planner.agent.md` arricchito con linee guida: haiku (CRUD/boilerplate), sonnet (standard), opus (architettura/security)
- Rispetto tier Anthropic API Proxy: il modello viene comunque validato dal proxy

**File**: `PlanItem.java`, `PlanSchema.java`, `AgentTask.java`, `AgentTaskProducer.java`,
`WorkerTaskConsumer.java`, `planner.agent.md`, Flyway (`plan_items.model_hint`)

**Sforzo**: 2g. **Dipendenze**: nessuna.

---

## #21 — Redis topic splitting per workerType

**Problema**: singolo stream `agent-tasks` con filtering client-side in `shouldProcess()`.
Ogni worker riceve TUTTI i messaggi.

**Soluzione futura**: un Redis Stream per workerType (`agent-tasks:BE`, `agent-tasks:FE`).
Flag `messaging.redis.topic-per-type: true/false` per backward compatibility.

**Priorita'**: bassa — overhead trascurabile con <10 worker types.

**Sforzo**: 1g. **Dipendenze**: nessuna.

---

## #22 ✅ — Orchestrator singleton (leader election) → [PIANO_HISTORY.md]

---

## #23 ✅ — Enrichment Pipeline Activation → [PIANO_HISTORY.md]

---

## #24 ✅ — Tool configurabili (L1 toolHints ✅ / L2 TOOL_MANAGER ✅)

**Problema**: i worker domain (BE, FE, AI_TASK) partono con **0 tools abilitati** perche':
1. Il HOOK_MANAGER (che genera HookPolicy con `allowedTools`) non viene mai eseguito (pipeline enrichment disconnessa, vedi #23)
2. Senza HookPolicy nel task, `PolicyEnforcingToolCallback` non sa quali tool permettere
3. Il fallback statico (`config/generated/hooks-config.json`) potrebbe non essere configurato
4. Risultato: il worker puo' solo generare testo (chiamata LLM pura), non interagire col filesystem

**Soluzione a 2 livelli**: L1 (toolHints da planner) ✅ implementato. L2 (TOOL_MANAGER) ✅ implementato.

**Livello 2 — TOOL_MANAGER come enrichment worker** ✅:
Worker dedicato che analizza il task + codebase e genera una `HookPolicy` precisa (come HOOK_MANAGER,
ma per singolo task, non per l'intero piano). Implementato in S15:

- Manifest YAML (`agents/manifests/tool-manager.agent.yml`): Haiku model, read-only FS tools
- SKILL.md prompt (`.claude/agents/tool-manager/SKILL.md`): regole per tipo worker, least privilege
- Worker Maven module (`execution-plane/workers/tool-manager-worker/pom.xml`)
- `EnrichmentInjectorService`: fan-out injection TM-* per domain task, DAG: CM→RM→TM-*→domain
- `EnrichmentProperties`: campo `includeToolManager` (default false, opt-in)
- Policy resolution: TM result > HM result > Static fallback
- 10 nuovi test (6 enrichment + 4 HookManagerService merge)
- Infrastruttura pre-esistente: `WorkerType.TOOL_MANAGER`, `TaskCompletedEventHandler:126`,
  `HookManagerService.storeToolManagerResult()`, `HookPolicyResolver` default allowlist

**File**: `PlanItem.java`, `PlanSchema.java`, `AgentTask.java`, `WorkerChatClientFactory.java`,
`planner.agent.md`, Flyway (`plan_items.tool_hints`), `agents/manifests/*.agent.yml` (fix nomi B13)

**Sforzo**: 1.5g (Livello 1: 0.5g + fix B13 0.5g, Livello 2: 1g). **Dipendenze**: #25 per `bash_execute`/`python_execute`. #23 per Livello 2.

---

## #25 ✅ — mcp-bash-tool + mcp-python-tool → [PIANO_HISTORY.md]

---

## #26 — Cost tracking + auto-split (L1 ✅ / L2 ✅ S15)

**Problema**: task molto costosi consumano budget senza possibilita' di controllo granulare.
Non c'e' un meccanismo per spezzare automaticamente task troppo grandi.

**Soluzione a 2 livelli**: L1 (cost tracking per task) ✅ S14. L2 (auto-split) ✅ S15.

**Livello 2 — Auto-split per task costosi** (threshold configurabile):
Se un task supera una soglia configurabile di token stimati (via GP prediction o euristica),
l'orchestratore lo spezza in sub-task piu' piccoli prima del dispatch.

- `task.cost.threshold.tokens` — soglia configurabile (default: es. 50_000 input tokens)
- `task.cost.threshold.action` — `WARN` (solo log) | `SPLIT` (auto-split) | `BLOCK` (richiedi approvazione)
- **Meccanismo split**: il planner viene richiamato con il task singolo + istruzione "decomponilo in sub-task"
  → genera N sub-task piu' piccoli → vengono aggiunti al piano come child plan
- **GP prediction** (#11): se il GP predice un costo elevato, attiva lo split PRIMA del dispatch
- **Post-hoc**: se un task completa con costo > threshold, log warning per calibrare la soglia

```java
// In OrchestrationService, prima del dispatch:
if (costEstimator.exceedsThreshold(item)) {
    switch (costProperties.getThresholdAction()) {
        case WARN -> log.warn("Task {} estimated cost exceeds threshold", item.getKey());
        case SPLIT -> taskSplitter.splitAndReplace(item);  // Richiama planner per decomposizione
        case BLOCK -> item.transitionTo(BLOCKED); // Richiede approvazione manuale
    }
}
```

**File**: `PlanItem.java` (campo `tokenUsage`), `AgentResult.java` (campo `tokenUsage`),
`TaskCostEstimator.java` (NEW), `TaskSplitterService.java` (NEW), `CostProperties.java` (NEW),
`OrchestrationService.java` (check pre-dispatch), Flyway (`plan_items.token_usage`)

**Sforzo**: 2g (Livello 1: 0.5g, Livello 2: 1.5g). **Dipendenze**: #11 GP (per prediction), #20 modelHint (costo varia per modello).

---

## #27 ✅ — Centralizzazione nomi tool (ToolNames registry) → [PIANO_HISTORY.md]

## #28 — Monitoring Dashboard UI (real-time) ✅ → S14

**Problema**: l'orchestratore espone 17 endpoint REST + SSE events, ma non ha nessun frontend.
L'utente non ha visibilita' su cosa sta succedendo durante l'esecuzione se non leggendo i log.

**Infrastruttura gia' esistente**:
- `SseEmitterRegistry` — SSE per-plan con late-join replay (7 event types)
- `GET /api/v1/plans/{id}/graph?format=mermaid` — DAG Mermaid color-coded per stato
- `GET /api/v1/plans/{id}/events` — stream SSE JSON
- `GET /api/v1/plans/{id}/items/{itemId}/attempts` — storia dispatch
- `GET /api/v1/rewards/stats` — ELO leaderboard per worker profile
- `PlanGraphService` — genera Mermaid con icone worker, durata, token

**Design: pagina singola HTML** (stile dashboard SOL — vanilla JS, nessun framework):

### 4 pannelli

```
┌─────────────────────────────────────┬──────────────────────────┐
│                                     │                          │
│         DAG Live (Mermaid)          │      Event Stream        │
│    nodi cambiano colore via SSE     │   feed cronologico       │
│    click nodo → Worker Detail       │   filtri per tipo/worker │
│                                     │                          │
├─────────────────────────────────────┼──────────────────────────┤
│                                     │                          │
│        Worker Detail                │        Stats             │
│    tool calls, token, reasoning     │   budget, elapsed,       │
│    errori, file modificati          │   completamento, ELO     │
│                                     │                          │
└─────────────────────────────────────┴──────────────────────────┘
```

**1. DAG Live** — `GET /graph?format=mermaid` renderizzato con Mermaid.js.
I nodi cambiano colore in tempo reale via SSE events: DISPATCHED→arancio, RUNNING→blu, DONE→verde, FAILED→rosso.
Click su un nodo apre il pannello Worker Detail.

**2. Event Stream** — `EventSource` su `/plans/{id}/events`.
Feed cronologico con badge colorati per tipo evento. Filtri per event type e worker.
Auto-scroll con pausa su hover.

**3. Worker Detail** — click su nodo DAG. REST calls:
- `GET /plans/{id}/items/{itemId}/attempts` → storia dispatch
- Provenance: model, toolsUsed, tokenUsage, traceId
- Reasoning (G2, se implementato)
- File modifications (G3, se implementato)
- Conversation log (G1, se implementato)

**4. Stats** — polling periodico (5s) o derivato da SSE events:
- Token budget: barra progresso (consumato/totale per workerType)
- Completamento: tasks done / total (barra progresso)
- Elapsed time
- ELO leaderboard: `GET /rewards/stats` (tabella top 10 worker profiles)

### Nuovi SSE event types necessari (backend)

Per visibilita' worker-interna servono 3 nuovi event types:

| Event Type | Emesso da | Payload | Uso UI |
|------------|-----------|---------|--------|
| `TOOL_CALL_START` | `PolicyEnforcingToolCallback` (pre-execute) | `{taskKey, toolName, inputPreview}` | Event Stream: "FE-001 sta chiamando fs_read..." |
| `TOOL_CALL_END` | `PolicyEnforcingToolCallback` (post-execute) | `{taskKey, toolName, outcome, durationMs}` | Event Stream: "fs_read completato (150ms)" |
| `TOKEN_UPDATE` | `AbstractWorker` (post-tool-call) | `{taskKey, estimatedTokens, budgetPct}` | Stats: aggiorna barra budget in real-time |

**Architettura emissione**: worker → Redis stream `agent-events` → orchestratore `WorkerEventListener` → `SseEmitterRegistry.broadcast()`.
Riusa lo stesso pattern dei plan events ma su uno stream separato per non inquinare il flusso principale.

### Tech stack

- HTML statico servito da nginx (stessa infrastruttura dashboard SOL)
- Vanilla JS + CSS (nessun framework — coerenza con `proxy/home/index.html`)
- Mermaid.js (CDN) per DAG rendering
- `EventSource` API nativa per SSE
- Responsive: 2 colonne desktop, stack verticale mobile

### File coinvolti

| File | Azione |
|------|--------|
| `monitoring.html` (NEW) | Pagina singola con 4 pannelli |
| `PolicyEnforcingToolCallback.java` | Emettere `TOOL_CALL_START`/`TOOL_CALL_END` via Redis |
| `AbstractWorker.java` | Emettere `TOKEN_UPDATE` dopo ogni tool call |
| `SseEmitterRegistry.java` | Supportare nuovi event types |
| `SpringPlanEvent.java` | Aggiungere costanti per i 3 nuovi tipi |
| `nginx.conf` | Servire `/monitor/` → file statico |

**Sforzo**: 2g frontend + 1g backend (nuovi events) = **3g totale**.
**Dipendenze**: #5 (SSE gia' implementato), G1 (per Worker Detail completo), G4 (per Stats Prometheus).
**Priorita'**: ALTA — senza UI il framework e' una black box.

## #29 — Worker Lifecycle Management (start, stop, kill, singleton)

**Problema**: il framework non ha nessun meccanismo di controllo lifecycle runtime.
L'unico modo per fermare un worker e' `docker kill`. Non c'e' modo di cancellare un piano in esecuzione.
Non c'e' garanzia singleton per task (B18 — double processing possibile).

### Stato attuale — cosa manca

| Capability | Stato | Workaround attuale |
|------------|-------|-------------------|
| Cancellare un piano | ❌ | Nessuno (aspettare che finisca o `docker kill` tutti i worker) |
| Pausare un piano | ⚠️ parziale | Logica esiste in `OrchestrationService`, ma **nessun endpoint REST** |
| Killare un task RUNNING | ❌ | `docker kill` del container worker (messaggio resta in PEL) |
| Timeout per task | ⚠️ parziale | Solo 120s timeout HTTP Spring AI (tool call possono bloccare oltre) |
| Start/stop worker individuale | ❌ | `docker start/stop` manuale |
| Singleton per task | ❌ B18 | Nessun lock distribuito |
| Lista worker attivi | ❌ | `docker ps` manuale |

### Valutazione architetturale: 3 opzioni

Il framework deve scegliere come il lifecycle dei worker viene gestito. Tre architetture a confronto:

**Opzione A — Long-running workers (architettura attuale)**
```
Worker container (long-running) ──listen──► Redis stream ──pick──► task ──execute──► result
                                  ↑                                                    │
                                  └────────────────── loop ────────────────────────────┘
```
Ogni worker e' un container Docker separato, sempre attivo, che ascolta su Redis stream.

**Opzione B — Ephemeral workers (un processo/container per task)**
```
Orchestrator ──docker run──► Worker container (1 per task) ──execute──► result ──exit──►
```
L'orchestratore lancia un container per ogni task, che termina dopo l'esecuzione.

**Opzione C — Single JVM monolitica, thread-per-task**
```
Orchestrator JVM ──spawn thread──► Worker virtual thread (1 per task) ──execute──► result ──GC──►
                       │
                       ├── un solo processo JVM con orchestratore + TUTTI i worker
                       ├── ogni task = 1 virtual thread (Java 21, ~1KB stack)
                       ├── singleton naturale: ConcurrentHashMap<taskKey, Future>
                       └── kill: Thread.interrupt() + CancellationToken in-memory
```
Un'unica JVM per tutto. Massima semplicita', minimo isolamento.

**Opzione D — JVM-per-WorkerType, thread-per-task internamente (RACCOMANDATA)**
```
                  ┌─────────────────────────────────────────────┐
                  │  Orchestrator JVM                            │
                  │  ├── WorkerExecutor[AI_TASK]  ──────────────┼──► AI_TASK JVM (1GB)
                  │  ├── WorkerExecutor[REVIEW]   ──────────────┼──► REVIEW JVM (512MB)
                  │  ├── WorkerExecutor[HOOK_MGR]  (in-process) │    (leggero, thread locale)
                  │  └── WorkerExecutor[CTX_MGR]   (in-process) │    (leggero, thread locale)
                  └─────────────────────────────────────────────┘

Dentro ogni JVM worker:
  WorkerRuntime ──spawn──► Virtual thread (task-1) ──execute──► result
                ──spawn──► Virtual thread (task-2) ──execute──► result
                    │
                    ├── singleton per task: ConcurrentHashMap<taskKey, Future>
                    ├── N task concorrenti dello STESSO tipo
                    └── kill: Future.cancel(true) + CancellationToken in-memory
```
**Un JVM per ogni tipo di worker**. Ogni JVM e' un runtime leggero che riceve task dall'orchestratore
e li esegue come virtual thread. Worker leggeri (manager) possono restare in-process nell'orchestratore.

Vantaggi chiave rispetto a C:
- **Isolamento per tipo**: se AI_TASK esplode la memoria, REVIEW e l'orchestratore non sono impattati
- **Sizing per tipo**: 1GB ad AI_TASK (pesante, context grandi), 256MB a HOOK_MANAGER (leggero)
- **Scaling per tipo**: 2 istanze AI_TASK, 1 REVIEW — indipendentemente
- **Stesso singleton**: dentro ogni JVM, `ConcurrentHashMap` garantisce 1 thread per taskKey
- **Stessa cancellation**: `AtomicBoolean` + `Thread.interrupt()` in-memory (dentro la JVM)

### Confronto architetture

| Aspetto | A: Long-running (attuale) | B: Ephemeral container | C: Single JVM | D: JVM-per-type (racc.) |
|---------|--------------------------|----------------------|--------------|----------------------|
| **Singleton per task** | ❌ (serve lock Redis) | ✅ (1 container = 1 task) | ✅ (`ConcurrentHashMap`) | ✅ (`ConcurrentHashMap`) |
| **Kill task** | ❌ (`docker kill`, PEL) | ⚠️ `docker stop` (~10s) | ✅ `Future.cancel(true)` | ✅ `Future.cancel(true)` |
| **Start/stop** | Manuale (`docker`) | Automatico | Automatico | Automatico |
| **Latenza startup** | ~0 (in ascolto) | ~10-30s (JVM warmup) | ~0 (thread) | ~0 (thread, JVM calda) |
| **Isolamento memoria** | ✅ processo separato | ✅ processo separato | ❌ heap condiviso | ✅ **per tipo** (JVM separate) |
| **Isolamento fault** | ✅ | ✅ | ❌ OOM uccide tutto | ✅ **OOM uccide solo quel tipo** |
| **Scaling per tipo** | ⚠️ N container manuali | ✅ | ❌ tutto insieme | ✅ **sizing e replica per tipo** |
| **PEL / double processing** | ❌ B18 | ⚠️ restart = doppio | ✅ impossibile | ✅ impossibile |
| **Complessita' Redis** | Alta | Media | Bassa | Bassa (dispatch orchestratore→JVM via API/gRPC) |
| **Complessita' operativa** | Media (N container) | Alta (build per task) | **Bassa** (1 processo) | Media (N+1 processi, ma sizing prevedibile) |
| **Cancellation** | ❌ nessuna | ⚠️ SIGTERM | ✅ in-memory | ✅ in-memory |
| **Debugging** | Difficile (N log) | Difficile | ✅ 1 log | Buono (1 log per tipo, correlati) |
| **Risorse totali** | N×~200MB | N×~200MB (ripetuto) | 1×~1GB | ~2GB (orchestr 256MB + AI 1GB + REVIEW 512MB + mgr 256MB) |

### Raccomandazione: Opzione D (JVM-per-WorkerType + thread-per-task)

**Perche' D e non C**: l'opzione C (monolite) e' piu' semplice ma fragile — un worker AI_TASK che
consuma 1GB di context porta giu' l'orchestratore e tutti gli altri worker. Con D, ogni tipo ha il suo
heap: AI_TASK puo' avere 1GB senza rischiare l'orchestratore (256MB). L'isolamento per tipo e' il
compromesso ideale tra semplicita' (thread in-memory) e robustezza (processi separati).

**Perche' D e non A/B**: i virtual thread di Java 21 rendono il modello thread-per-task efficiente
quanto un event loop — ~1KB per thread, zero overhead per I/O-bound (chiamate LLM, tool MCP HTTP).
Non serve la complessita' di Redis PEL, XCLAIM, lock distribuiti.

**Worker leggeri in-process**: HOOK_MANAGER e CONTEXT_MANAGER possono restare come thread
nell'orchestratore (opzione C parziale) — non hanno context grandi e non rischiano OOM.
Solo AI_TASK e REVIEW (che chiamano LLM con context potenzialmente grandi) vanno su JVM separate.

**Comunicazione orchestratore → JVM worker**: l'orchestratore invoca il `WorkerRuntime` di ogni JVM
via HTTP REST interno (endpoint `/tasks` per submit, `/tasks/{id}/cancel` per kill, `/tasks` GET per stato).
Alternativa: gRPC per latenza minima. In fase 1 basta HTTP.

**Quando semplificare a C**: se il framework gira su una macchina con poca RAM (< 2GB),
o se i task sono tutti leggeri, l'opzione C (monolite) e' accettabile come punto di partenza.
La migrazione da C a D e' incrementale: estrarre un WorkerType alla volta su JVM separata.

### Design: 5 componenti

**A. WorkerRuntime** (vive dentro ogni JVM worker — cuore del modello D)

Ogni JVM worker contiene un `WorkerRuntime` che riceve task e li esegue come virtual thread.
Nell'opzione D, ogni `WorkerRuntime` gestisce un solo `WorkerType` (es. AI_TASK).
Nell'opzione C (monolite), un unico `WorkerRuntime` gestisce tutti i tipi.

```java
public class WorkerRuntime {
    private final WorkerType workerType;  // il tipo gestito da questa JVM
    private final Map<String, WorkerHandle> runningTasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Accetta un task — singleton enforcement: 1 thread per taskKey */
    public boolean submit(AgentTask task, WorkerManifest manifest) {
        if (runningTasks.containsKey(task.taskKey())) {
            log.warn("Task {} gia' in esecuzione su {} — skip duplicate", task.taskKey(), workerType);
            return false;
        }

        WorkerInstance worker = workerFactory.create(manifest, task);
        Future<?> future = executor.submit(() -> {
            try {
                AgentResult result = worker.run();
                resultCallback.onCompleted(task.taskKey(), result);
            } catch (CancellationException e) {
                resultCallback.onCancelled(task.taskKey());
            } finally {
                runningTasks.remove(task.taskKey());
            }
        });
        runningTasks.put(task.taskKey(), new WorkerHandle(future, worker));
        return true;
    }

    public boolean cancel(String taskKey) {
        WorkerHandle handle = runningTasks.get(taskKey);
        if (handle != null) {
            handle.worker().cancel();           // AtomicBoolean flag
            handle.future().cancel(true);       // Thread.interrupt()
            return true;
        }
        return false;
    }

    public Map<String, WorkerStatus> getRunningTasks() {
        return runningTasks.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().status()));
    }
}

record WorkerHandle(Future<?> future, WorkerInstance worker) {
    WorkerStatus status() {
        return new WorkerStatus(worker.type(), worker.taskKey(), worker.startTime(), worker.tokenCount());
    }
}
```

**Virtual threads** (Java 21): ogni task gira su un virtual thread — leggero (~1KB stack), nessun pool fisso.
`newVirtualThreadPerTaskExecutor()` e' gia' usato nel framework (vedi `ParallelToolCallingManager`).
Zero overhead per task in attesa di I/O (chiamata LLM, tool MCP HTTP).
Una JVM worker puo' gestire **N task concorrenti** dello stesso tipo (es. 3 AI_TASK in parallelo).

**B. WorkerRuntimeController** (API REST interna della JVM worker)

Ogni JVM worker espone un endpoint HTTP interno per ricevere comandi dall'orchestratore:

```
POST /tasks           → body: AgentTask JSON → WorkerRuntime.submit() → 200/409 (duplicate)
POST /tasks/{id}/cancel → WorkerRuntime.cancel(id) → 200/404
GET  /tasks           → WorkerRuntime.getRunningTasks() → lista task attivi + status
GET  /health          → stato JVM (heap, threads, uptime)
```

L'orchestratore chiama queste API per dispatchare, cancellare, e monitorare i task.
Porta interna per tipo (es. AI_TASK :8100, REVIEW :8101). Non esposta all'esterno.

**C. CancellationToken** (in-memory, nessun Redis)

```java
public class WorkerInstance {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() { cancelled.set(true); }

    // Controllato da PolicyEnforcingToolCallback prima di ogni tool call
    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }
}
```

Nessun Redis per la cancellazione — il flag e' in-memory (stessa JVM del worker).
`cancel()` setta il flag + `Thread.interrupt()` → il tool call HTTP in corso riceve `InterruptedException`.

**D. Endpoint di controllo** (orchestratore — `PlanController.java`)

L'orchestratore espone endpoint pubblici che internamente delegano alle JVM worker:

```
POST /api/v1/plans/{id}/pause                  → RUNNING → PAUSED (esiste logica, aggiungere endpoint)
POST /api/v1/plans/{id}/cancel                 → RUNNING → CANCELLED (nuovo stato terminale)
    → per ogni task RUNNING: chiama POST worker-jvm:port/tasks/{taskKey}/cancel
    → per ogni task WAITING/QUEUED: transizione diretta a CANCELLED
POST /api/v1/plans/{id}/items/{itemId}/cancel  → cancella singolo task RUNNING
GET  /api/v1/workers                           → aggrega getRunningTasks() da tutte le JVM worker
```

Nuovo stato `PlanStatus.CANCELLED` — terminale, irreversibile:
- Task WAITING/QUEUED → CANCELLED (mai eseguiti)
- Task RUNNING → orchestratore chiama cancel sulla JVM worker → interrupt + flag → worker termina
- Task DONE/FAILED → invariati (gia' terminali)

**E. Migrazione incrementale** (da long-running a JVM-per-type)

| Fase | Cosa cambia | Cosa resta |
|------|-------------|-----------|
| **Fase 1 (C)** | Tutto in-process: orchestratore spawna tutti i worker come virtual thread nella stessa JVM | Redis streams non usati per dispatch, solo per persistenza. Un'unica JVM. Semplice ma fragile. |
| **Fase 2 (D)** | Estrarre AI_TASK e REVIEW su JVM separate con `WorkerRuntimeController` | Manager workers (HOOK_MANAGER, CONTEXT_MANAGER) restano in-process nell'orchestratore. Redis solo cache+eventi. |
| **Fase 3 (D+)** | Ogni WorkerType su JVM separata. Docker Compose con un container per tipo. Scaling orizzontale per tipo. | Orchestratore leggero (256MB), coordina via HTTP le JVM worker. |

**Fase 1** e' il punto di partenza (opzione C — monolite). **Fase 2** e' il target (opzione D — isolamento per tipo).
Fase 3 e' opzionale, solo se serve scaling orizzontale.

**Esempio Docker Compose (fase 2-3)**:
```yaml
services:
  orchestrator:
    image: agent-framework:latest
    profiles: [orchestrator]
    environment:
      WORKER_RUNTIMES: '{"AI_TASK":"http://worker-ai:8100","REVIEW":"http://worker-review:8101"}'
    mem_limit: 256m
  worker-ai:
    image: agent-framework:latest
    profiles: [worker]
    environment:
      WORKER_TYPE: AI_TASK
      SERVER_PORT: 8100
    mem_limit: 1g
  worker-review:
    image: agent-framework:latest
    profiles: [worker]
    environment:
      WORKER_TYPE: REVIEW
      SERVER_PORT: 8101
    mem_limit: 512m
  # HOOK_MANAGER e CONTEXT_MANAGER: in-process nell'orchestratore (leggeri)
```

Stessa immagine Docker, profilo diverso. L'orchestratore usa una mappa `WorkerType → URL` per il dispatch.
Un tipo non nella mappa viene eseguito in-process (manager leggeri).

### File coinvolti

| File | Azione |
|------|--------|
| `WorkerRuntime.java` (NEW) | Runtime worker: riceve task, spawna virtual thread, singleton per taskKey |
| `WorkerRuntimeController.java` (NEW) | API REST interna (POST /tasks, POST /tasks/{id}/cancel, GET /tasks, GET /health) |
| `WorkerFactory.java` (NEW) | Costruisce `WorkerInstance` con ChatClient, tools, policy |
| `WorkerInstance.java` (NEW) | Worker con CancellationToken in-memory (`AtomicBoolean`) |
| `WorkerHandle.java` (NEW) | Record: `Future` + `WorkerInstance` + status |
| `WorkerDispatcher.java` (NEW) | Orchestratore: mappa WorkerType→URL, dispatch via HTTP o in-process |
| `PlanStatus.java` | Aggiungere `CANCELLED` |
| `ItemStatus.java` | Aggiungere `CANCELLED` |
| `PlanController.java` | Endpoint `pause`, `cancel`, `cancel item`, `GET /workers` |
| `OrchestrationService.java` | Usare `WorkerDispatcher` invece di Redis dispatch |
| `PolicyEnforcingToolCallback.java` | Check `workerInstance.isCancelled()` prima di ogni tool call |

**Sforzo**: 1g (A: WorkerRuntime + factory) + 0.5g (B: API controller) + 0.5g (C: cancellation) + 0.5g (D: endpoint orchestratore) + 1g (E: migrazione fase 1→2) = **3.5g totale**.
**Dipendenze**: B17 (CompactingToolCallingManager — obbligatorio per fase 1 in-process, consigliato per fase 2+).
**Priorita'**: ALTA — abilita kill, singleton, e semplifica l'architettura (da Redis PEL a HTTP diretto).

---

# Roadmap items #30-#34 — Blockchain-Inspired Enhancements

Cinque concetti ispirati alla blockchain — senza blockchain vera — per aggiungere garanzie
crittografiche di integrita' al framework. Costo infrastrutturale zero: i primitivi crittografici
(hash chain, firme Ed25519, commitment) danno le stesse garanzie senza consenso distribuito.

## #30 — Hash Chain Tamper-Proof su `plan_event` ✅

**Implementato**: catena hash SHA-256 tamper-proof su `plan_event`.

**Componenti**:
- `PlanEvent.java` — +2 campi (`eventHash`, `previousHash`), costruttore 9-arg
- `PlanEventStore.append()` — computa `SHA-256(previousHash|eventType|payload|occurredAt)`, GENESIS_HASH = "0"×64
- `HashChainVerifier.java` — verifica O(N) sequenziale, SECURITY WARNING + INTEGRITY_VIOLATION event se rotto
- `HashChainVerificationResult.java` — record con valid, eventCount, brokenAtSequence, reason
- `GET /api/v1/plans/{id}/verify-integrity` — endpoint REST per verifica on-demand
- `V27__hash_chain_tamper_proof.sql` — ALTER TABLE, integrità forward-only (eventi pre-migrazione con hash vuoto skippati)
- `HashUtil.sha256()` — riusato da `agent-common` (condiviso con 6 items futuri: #31, #32, #45, #46, #48)
- Test: 7 HashChainVerifierTest + 5 PlanEventStoreHashTest. 933 test totali verdi.

---

## #31 — Verifiable Compute (Firma Crittografica Output Worker)

**Problema**: l'orchestratore riceve `AgentResult` dal worker via Redis, ma non ha modo di
verificare che il risultato provenga effettivamente dal worker dichiarato. Un attore malevolo
con accesso a Redis potrebbe iniettare risultati falsi. Con worker esterni (SaaS, multi-tenant),
il rischio diventa concreto.

**Soluzione**: ogni worker firma crittograficamente il proprio output con Ed25519.
L'orchestratore verifica la firma prima di processare il risultato.

**Design**:

```java
// In agent-common (shared, zero dipendenze esterne)
public record SignedResultEnvelope(
    String resultJson,                // payload originale
    String provenanceJson,            // Provenance serializzata
    String policyHash,                // SHA-256 della HookPolicy ricevuta
    String workerSignature,           // Ed25519 signature (Base64)
    String workerPublicKey,           // Ed25519 public key (Base64) — per key discovery
    String signedAt                   // ISO-8601 timestamp
) {}
```

- `WorkerKeyManager.java` (NEW, in worker-sdk): gestione keypair Ed25519 per worker
  - Chiavi generate al primo avvio, persistite in `/keys/{workerType}.key` (volume Docker)
  - Rotazione: parametro `worker.key-rotation-days: 90`, rigenera e re-registra
  - Public key registrata all'orchestratore via endpoint `POST /api/v1/workers/keys`
- `ResultSigner.java` (NEW, in worker-sdk): firma il risultato prima di pubblicarlo
  - `sign(resultJson, provenance, policyHash) → SignedResultEnvelope`
  - Chiamato in `AbstractWorker.process()` dopo `execute()`, prima di `resultProducer.publish()`
- `SignatureVerifier.java` (NEW, in orchestrator): verifica la firma all'arrivo del risultato
  - `verify(SignedResultEnvelope, registeredPublicKey) → boolean`
  - Chiamato in `OrchestrationService.onTaskCompleted()` come primo step
- `WorkerKeyRegistry.java` (NEW, in orchestrator): registry delle public key per worker type
  - Persistito in DB (Flyway V11: tabella `worker_keys`)
  - Cache in-memory `ConcurrentHashMap<String, PublicKey>`

**Chiavi Java**: `java.security.KeyPairGenerator.getInstance("Ed25519")` (Java 15+, no BouncyCastle).

**Failure path**: firma invalida → item marcato `FAILED` con `failureReason: "SIGNATURE_VERIFICATION_FAILED"`,
evento `INTEGRITY_VIOLATION` nel log. Nessun retry automatico (potrebbe essere attacco).

**Abilitato di default**: `worker.signing.enabled: true` (default true — nessun progetto in corso, nessun rischio backward compatibility).
Se disabilitato (`false`), `onTaskCompleted()` accetta risultati senza firma.

**Sforzo**: 2g. **Dipendenze**: promuovere `HashUtil` da worker-sdk a agent-common.
**Impatto**: fondamentale per scenari multi-tenant e worker esterni.

**Implementazione S18** (building blocks crittografici in `agent-common`):
- `Ed25519Signer.java` — utility statica: `generateKeyPair()`, `sign(byte[], PrivateKey)`, `verify(byte[], String, PublicKey)`, `encodePublicKey/decodePublicKey`, `encodePrivateKey/decodePrivateKey`. Pure Java 21 (`java.security` EdDSA), zero dipendenze esterne. Jagerman-safe (nessun factorial).
- `SignedResultEnvelope.java` — record: `resultJson`, `workerSignature`, `workerPublicKey`, `signedAt`. Factory `sign()` (payload = `resultJson + "|" + signedAt`), `verify(PublicKey trustedKey)` con TOFU mode (null → usa embedded key).
- 12 test unitari (@Nested: KeyPair 3, SignVerify 4, Envelope 5). 38 test agent-common totali verdi.
- **Scope escluso**: worker-SDK wiring, Flyway migration, key registry endpoint, AgentResultConsumer modification.

---

## #32 — Policy-as-Code Immutabile (Commitment crittografico HookPolicy) ✅

**Problema**: le HookPolicy generate dal HOOK_MANAGER sono conservate in-memory
(`ConcurrentHashMap` in `HookManagerService`). Un bug, una race condition, o un attore
malevolo con accesso al processo potrebbe alterare la policy tra la generazione e l'enforcement.
Il worker riceve la policy nell'`AgentTask`, ma non puo' verificare che corrisponda
a quella originale.

**Soluzione**: commitment crittografico — quando HOOK_MANAGER genera una policy, il sistema
calcola `policy_hash = SHA-256(canonical_json(policy))` e lo persiste in DB.
Il worker riceve sia la policy che il suo hash. `PolicyEnforcingToolCallback` verifica
il match prima di applicare la policy.

**Implementazione** (completata 2026-03-09):

- `PolicyHasher` (agent-common): canonical JSON senza Jackson (hand-built, chiavi ordinate
  alfabeticamente, liste ordinate), 7 test (determinismo, order-invariance, golden test)
- `HashedPolicy` record in `HookManagerService`: coppia `(HookPolicy, String hash)`,
  calcolata al momento dello storage in `storePolicies()` e `storeToolManagerResult()`
- `resolvePolicyWithHash()`: metodo dedicato che restituisce `Optional<HashedPolicy>`;
  `resolvePolicy()` delega ad esso per backward compatibility
- `AgentTask.policyHash` (campo 14 in entrambe le versioni orchestrator/worker-sdk)
- `PlanItem.policyHash` (VARCHAR(64), Flyway V28)
- `PolicyEnforcingToolCallback`: ThreadLocal `EXPECTED_POLICY_HASH`, verifica hash PRIMA
  dell'allowlist check. Mismatch → JSON error `POLICY_TAMPERED` (coerente con denial pattern)
- `AbstractWorker.process()`: set/clear ThreadLocal nella stessa posizione degli altri ThreadLocal
- Hash calcolato DOPO `pidBudgetController.adjustPolicy()` (il PID modifica `maxTokenBudget`)

**Sforzo**: 1g. **Dipendenze**: #23 (Enrichment Pipeline — senza HOOK_MANAGER attivo, non ci sono policy).
**Impatto**: chiude il gap di trust tra generazione e enforcement delle policy.
**Sinergia**: #31 (Ed25519 Verifiable Compute) — il commitment hash protegge la policy in transito,
la firma Ed25519 protegge il risultato del worker. Insieme formano una catena di trust completa.

---

## #33 — Token Economics (Contabilita' Double-Entry per Budget)

**Problema**: il sistema di budget attuale (`PlanTokenUsageService`) e' un contatore monotonico:
somma i token consumati e confronta con il ceiling. Manca il concetto di "produzione" di valore —
un task che ha avuto un alto reward ha "restituito" valore al sistema. Non c'e' visibilita'
sul ROI per-task o per-workerType.

**Soluzione**: contabilita' double-entry con token virtuali. Ogni piano parte con un "pool" di token.
Ogni task "spende" dal pool (debit) e il reward "restituisce" una frazione (credit).
Il saldo netto mostra l'efficienza reale del piano.

**Design**:

```
Piano budget: 200.000 token
+-- CM-001: debit 5.000, credit 0 (infra, no reward)
+-- BE-001: debit 45.000, credit 13.500 (reward 0.90 x 15.000 base)
+-- BE-002: debit 80.000, credit 8.000 (reward 0.40 x 20.000 base)
|           ^ retry: debit 30.000 aggiuntivi
+-- FE-001: debit 20.000, credit 9.000 (reward 0.90 x 10.000 base)
+-- REVIEW: debit 10.000, credit 0 (infra)
    ─────────────────
    Total debit:  190.000
    Total credit:  30.500
    Net spend:    159.500
    Efficiency:   16.1% returned
```

- Flyway V11: tabella `token_ledger` (`plan_id`, `item_id`, `entry_type` ENUM('DEBIT','CREDIT'), `amount`, `balance_after`, `description`, `created_at`)
- `TokenLedgerService.java` (NEW):
  - `debit(planId, itemId, amount, description)` — registra spesa, aggiorna saldo
  - `credit(planId, itemId, amount, description)` — registra ritorno basato su reward
  - `getBalance(planId)` — saldo corrente
  - `getLedger(planId)` — storico completo
  - Credit formula: `baseCredit = tokenBudgetPerType x aggregatedReward`
  - Credits emessi solo per domain worker (BE, FE, AI_TASK), non per infra (CM, HOOK_MANAGER, REVIEW)
- `PlanController.java`: `GET /api/v1/plans/{id}/budget/ledger` — tabella double-entry
- `OrchestrationService`: integrazione debit al dispatch, credit al reward computation

**Invariante contabile**: `SUM(debit) - SUM(credit) = net_spend`. Verificabile con query SQL.

**Beneficio**: visibilita' sull'efficienza dei worker. Un workerType che ha costantemente
`credit/debit < 0.1` ha un pessimo rapporto costo/qualita'. Alimenta decisioni GP e ELO.

**Sforzo**: 1.5g. **Dipendenze**: nessuna (il reward system esiste gia').
**Impatto**: medio — migliora l'osservabilita', non cambia il comportamento.

---

## #34 — Federazione Multi-Server (Design Interfacce)

**Problema**: il framework e' single-server. Se evolvesse verso federazione — piu' orchestratori
che collaborano su piani condivisi, o agent di organizzazioni diverse — servirebbe un protocollo
di sincronizzazione degli eventi e di trust tra server. Oggi e' premature implementarlo, ma
definire le interfacce permette di non precludere l'evoluzione.

**Soluzione (solo interfacce, nessuna implementazione)**: definire SPI per federazione.

**Design**:

```java
// In agent-common — interfacce che i futuri provider implementeranno

/** Identita' crittografica di un server nel cluster federato */
public record ServerIdentity(
    String serverId,         // UUID stabile
    String displayName,      // "SOL-1", "SOL-2"
    String publicKey,        // Ed25519 public key (Base64)
    String endpoint,         // https://sol-2.example.com/api/v1/federation
    Instant registeredAt
) {}

/** Sync di eventi tra server federati */
public interface FederationEventSync {
    /** Pubblica un evento locale ai peer */
    void broadcast(PlanEvent event, String signature);

    /** Riceve eventi da un peer, verifica firma, merge nella sequenza locale */
    void receive(PlanEvent event, String signature, ServerIdentity sender);

    /** Richiede eventi mancanti (late-join, riconnessione) */
    List<PlanEvent> requestMissing(UUID planId, long fromSequence, ServerIdentity peer);
}

/** Dispatch di task a worker su server remoti */
public interface FederationTaskRouter {
    /** Determina quale server ha il worker migliore per questo task */
    ServerIdentity route(AgentTask task, List<ServerIdentity> availableServers);

    /** Dispatcha un task a un server remoto */
    void dispatchRemote(AgentTask task, ServerIdentity target);
}

/** Consenso federato per operazioni critiche */
public interface FederationConsensus {
    /** Propone un'azione che richiede consenso (es. plan cancellation, policy override) */
    CompletableFuture<ConsensusResult> propose(String action, Object payload);
}

public record ConsensusResult(
    boolean approved,
    int votesFor, int votesAgainst,
    Map<String, String> serverVotes  // serverId -> "approve"/"reject"
) {}
```

**Protocollo sync eventi**: Merkle tree degli hash eventi (#30) per riconciliazione
efficiente. Ogni server mantiene il Merkle root del proprio ramo. Al sync, confronto root →
scambio solo dei sottoalberi divergenti.

**Conflitti**: CRDT (Conflict-free Replicated Data Types) per lo stato del piano.
`ItemStatus` come stato convergente: `max(status_A, status_B)` con ordinamento
`WAITING < DISPATCHED < DONE < FAILED`. Mai conflitto semantico.

**Auth inter-server**: mTLS con certificati Ed25519 (riusa le chiavi del #31).
JWT federato: ogni server emette JWT firmati con la propria chiave, gli altri verificano
con la public key dal registry.

**Cosa NON implementare ora**:
- Nessun provider concreto (solo SPI)
- Nessuna migrazione DB
- Nessun endpoint REST
- Solo interfacce Java + Javadoc + test delle interfacce (contract test)

**File**: `agent-common/.../federation/ServerIdentity.java`, `FederationEventSync.java`,
`FederationTaskRouter.java`, `FederationConsensus.java`, `ConsensusResult.java`

**Sforzo**: 1g (solo interfacce + Javadoc). **Dipendenze**: #30 (hash chain per Merkle), #31 (chiavi Ed25519 per mTLS).
**Impatto**: nessuno oggi, ma sblocca l'evoluzione futura senza breaking changes.

---

## Idee aggiuntive blockchain-inspired → promosse a #45-#48

Le idee A-D originariamente elencate qui sono state promosse a roadmap items completi:
- **A. Merkle Tree per DAG Verification** → #45
- **B. Commit-Reveal per Council Votes** → #46 (reframed: Verifiable Council Deliberation)
- **C. Reputation Staking** → #47
- **D. Content-Addressable Storage per Artifact** → #48

Aggiunto inoltre:
- **#49 — Quadratic Voting per Council Weighting** (Mechanism Design, Vitalik Buterin 2019)

Dettagli completi nella sezione "Advanced Mechanisms (#45-#49)" sotto.

---

## Riepilogo items blockchain-inspired e priorita'

| # | Titolo | Sforzo | Dipendenze | Valore |
|---|--------|--------|------------|--------|
| 30 | Hash Chain Tamper-Proof | 0.5g | nessuna | Alto (audit/compliance) |
| 31 | Verifiable Compute (firma worker) | 2g | HashUtil in agent-common | Alto (multi-tenant) |
| 32 | Policy-as-Code Immutabile | 1g | #23 | Medio-Alto (trust) |
| 33 | Token Economics Double-Entry | 1.5g | nessuna | Medio (osservabilita') |
| 34 | Federazione (solo interfacce) | 1g | #30, #31 | Basso oggi, alto futuro |

**Ordine consigliato**: #30 → #32 → #31 → #33 → #34
(#30 e' fondazione per tutti; #32 sblocca trust policies; #31 e' il piu' complesso ma autonomo;
#33 e' indipendente; #34 solo interfacce, ultimo)

**Totale**: ~6 giorni di lavoro.

---

# Mathematical Foundations (#35-#43)

Roadmap items ispirati a branche matematiche non ancora esplorate nel framework.
Ogni branca risolve un problema concreto dell'orchestrazione multi-agent.

---

## #35 — Context Quality Scoring (Teoria dell'Informazione) ✅

**Implementato**: metriche information-theoretic per valutare la qualita' del contesto CM.

**Tre componenti scoring** (composite score [0, 1]):

| Componente | Peso | Misura |
|-----------|------|--------|
| **File Relevance** | 0.45 | Proxy MI — ratio file CM-selected usati dal worker (fuzzy path match) |
| **Entropy Score** | 0.30 | Sigmoid su #deps e dimensione — penalizza contesti troppo ampi o troppo stretti |
| **KL Divergence Score** | 0.25 | Media geometrica coverage bidirezionale (proxy 1 - D_KL) |

**Integrazione**:
- `task_outcomes.context_quality_score` (V23 migration) — GP training data
- `RewardComputationService.injectContextQualityScore()` — 4° reward source (weight 0.15)
- `BayesianSuccessPredictor` — feature slot [1027]
- `TaskCompletedEventHandler:131-139` — side-effect #7, wired

**Feedback observability**: `ContextQualityFeedback` record con unusedSelectedFiles, missingFiles,
suggestion. Loggato a INFO per ogni task completato (no migrazione extra, il GP usa solo il numero).

**File**: `ContextQualityService.java`, `ContextQualityFeedback.java`, `ContextQualityServiceTest.java` (22 test),
`RewardComputationServiceTest.java` (+5 test injectContextQualityScore). 916 test verdi.

---

## #36 — Worker Pool Sizing (Queueing Theory) ✅

**Problema**: quanti worker servono? Quanto aspettera' un task in coda? Se il piano ha 20 task BE
ma un solo worker BE, qual e' il throughput atteso? Non c'e' modo di predire colli di bottiglia.

**Soluzione**: modelli di queueing theory per predire tempi di attesa e dimensionare i worker pool.

**Design**:

- **Legge di Little**: `L = lambda * W` (task in coda = tasso arrivo x tempo medio).
  `lambda` = task ready per dispatch / tempo, `W` = durata media task per workerType (da `plan_items.durationMs`).
  Pre-calcolo: dato un piano con N task BE e durata media 2 min → `W_total = N * 2min / num_workers`.

- **Erlang C**: probabilita' che un task debba aspettare, dato c worker e carico `rho = lambda / (c * mu)`.
  Se `P(wait) > 0.5` → suggerisce di aggiungere worker.
  ```java
  double erlangC(int c, double rho) {
      double a = c * rho;
      double erlangB = Math.pow(a, c) / factorial(c);
      // ... formula ricorsiva standard
      return erlangB / (erlangB + (1 - rho) * sum);
  }
  ```

- **Critical Path Method**: il DAG definisce un ordinamento parziale.
  Il percorso critico = sequenza piu' lunga di dipendenze.
  `criticalPathDuration = sum(durata stimata dei task sul percorso critico)`.
  Task fuori dal percorso critico possono aspettare senza impattare il tempo totale.

- `QueueAnalyzer.java` (NEW): analisi pre-dispatch
  - `analyze(Plan plan) → QueueMetrics`
  - `QueueMetrics`: estimatedCompletionTime, optimalWorkerCount (per tipo), bottleneckWorkerType, criticalPath
- `PlanController.java`: `GET /api/v1/plans/{id}/queue-analysis` → QueueMetrics (pre-esecuzione) o live metrics
- Dati storici: `plan_items` con `durationMs` per calibrare lambda e mu

**File**: `QueueAnalyzer.java` (NEW), `QueueMetrics.java` (NEW, in agent-common),
`PlanController.java` (endpoint), `CriticalPathCalculator.java` (NEW — topological sort + max-path sul DAG)

**Sforzo**: 1.5g. **Dipendenze**: nessuna (usa dati storici in `plan_items`).
**Impatto**: alto — predice colli di bottiglia prima dell'esecuzione, guida il sizing nel modello JVM-per-type (#29).

**Implementazione** (2026-03-09, S17):
- `QueueAnalyzer.java` — @Service: Erlang C (Jagerman recursion) + Little's Law + CPM delegation via `CriticalPathCalculator`
- `QueueAnalysisResult` / `WorkerTypeAnalysis` record: per-tipo P(wait), L, W_q, recommended consumers, saturated flag
- Riusa `QueuingCapacityPlanner` per E[S] storici, `CriticalPathCalculator` per makespan/critical path
- `GET /{id}/queue-analysis` endpoint in `PlanController`
- `QueueAnalyzerTest.java` — 15 test: 5 Erlang C, 3 Little's Law, 5 plan analysis, 4 CPM integration

---

## #37 — Adaptive Token Budget (Teoria del Controllo — PID)

**Problema**: il budget token e' un ceiling statico. Se un task consuma troppo, il piano va in PAUSED.
Non c'e' meccanismo adattivo — il budget non si aggiusta in base a come sta andando l'esecuzione.

**Soluzione**: controllore PID che aggiusta il `maxTokenBudget` nei HookPolicy dei task successivi
in base all'errore cumulativo (budget previsto vs consumato).

**Design**:

```
u(t) = Kp * e(t) + Ki * integral(e) + Kd * de/dt

e(t) = budget_stimato_task - budget_consumato_task
Kp = 0.5   (reazione proporzionale all'errore)
Ki = 0.1   (correzione drift cumulativo)
Kd = 0.2   (reazione alla velocita' di cambiamento)
```

- **P** (proporzionale): task consuma 35k invece di 20k previsti → e = -15k → riduce budget task successivi
- **I** (integrale): accumulo errore → se sistematicamente over-budget, riduce progressivamente
- **D** (derivativa): se la velocita' di consumo accelera, interviene prima

- `PidBudgetController.java` (NEW):
  - `update(planId, actualTokens, estimatedTokens) → adjustedBudget`
  - Stato persistito: `error_integral`, `last_error` (per termine derivativo)
  - Vincoli: output clampato tra `minBudget` (non sotto 5k) e `maxBudget` (non sopra piano)
  - Anti-windup: integrale limitato per evitare saturazione
- Integrazione: `OrchestrationService.dispatchReadyItems()` chiama PID prima di costruire `AgentTask`
  - Il PID modifica `HookPolicy.maxTokenBudget` per il task corrente
  - Se output PID negativo (budget in eccesso) → alloca surplus a task successivi
- `application.yml`: `budget.pid.kp: 0.5, budget.pid.ki: 0.1, budget.pid.kd: 0.2, budget.pid.enabled: true`

**Stabilita'**: i coefficienti Kp, Ki, Kd devono soddisfare il criterio di Routh-Hurwitz.
Con i valori proposti, il sistema e' stabile (nessuna oscillazione). Verificabile con simulazione
su dati storici `plan_items.tokensUsed`.

**File**: `PidBudgetController.java` (NEW), `OrchestrationService.java` (integrazione dispatch),
`HookManagerService.java` (PID override del maxTokenBudget), `application.yml`

**Sforzo**: 1g. **Dipendenze**: #32 (policy immutabile — il PID deve agire *prima* del commitment hash).
**Impatto**: alto — budget dinamico riduce PAUSED inutili e ottimizza l'allocazione.

---

## #38 — State Machine Verification (Logica Temporale LTL/CTL) ✅

**Problema**: la state machine di `PlanItem` e `Plan` ha transizioni complesse (ralph-loop, retry,
sub-plan, approval, cancellation). Non c'e' garanzia formale che non esistano deadlock, livelock,
o stati irraggiungibili. I bug nelle transizioni si scoprono solo a runtime.

**Soluzione**: model checking con proprieta' espresse in logica temporale. Verifica statica
esaustiva di tutte le transizioni possibili.

**Design**:

Proprieta' da verificare:
```
P1: sempre(DISPATCHED -> eventualmente(DONE | FAILED | CANCELLED))     // no deadlock
P2: sempre(retryCount <= maxRetries)                                     // bounded retry
P3: sempre(ralphLoopCount <= maxRalphLoops)                              // bounded quality loops
P4: sempre(allItemsTerminal -> planTerminal)                             // plan completion
P5: mai(CANCELLED -> eventualmente(DISPATCHED))                          // CANCELLED terminale
P6: sempre(AWAITING_APPROVAL -> eventualmente(WAITING | FAILED))         // approval risolto
```

- `StateMachineVerifier.java` (NEW): enumerazione esaustiva degli stati raggiungibili
  - Input: `ItemStatus[]` transizioni ammesse, vincoli (maxRetry, maxRalph)
  - Output: `VerificationResult` con proprieta' verificate/violate + counterexample
  - Spazio stati: ~7 stati item x 5 stati plan x 3 retry x 2 ralph = ~210 stati (trattabile)
  - Eseguito come **test JUnit** (compilazione-time, non runtime)

- Transizioni ammesse (grafo esplicito):
  ```java
  Map<ItemStatus, Set<ItemStatus>> ALLOWED = Map.of(
      WAITING,            Set.of(DISPATCHED, CANCELLED),
      DISPATCHED,         Set.of(DONE, FAILED, CANCELLED),
      DONE,               Set.of(WAITING),           // solo ralph-loop
      FAILED,             Set.of(WAITING, TO_DISPATCH, CANCELLED),
      AWAITING_APPROVAL,  Set.of(WAITING, FAILED),
      TO_DISPATCH,        Set.of(DISPATCHED),
      CANCELLED,          Set.of()                    // terminale
  );
  ```

- `StateMachineVerifierTest.java` (NEW): test che verifica P1-P6 a ogni build.
  Se una refactoring rompe una proprieta' → il build fallisce con counterexample.

**P4 (DONE → WAITING) e' intenzionalmente violata**: dal ralph-loop. Il verifier la segnala
come "controlled violation" con annotazione `@AllowedViolation("ralph-loop, bounded by maxRalphLoops")`.

**File**: `StateMachineVerifier.java` (NEW), `StateMachineVerifierTest.java` (NEW),
`ItemStatus.java` (aggiungere metodo `allowedTransitions()`)

**Sforzo**: 1g. **Dipendenze**: nessuna.
**Impatto**: medio-alto — trova deadlock e transizioni illegali a compile-time, non a runtime.

**Implementazione** (2026-03-09):
- `StateMachineVerifier.java` — BFS su spazio prodotto `(ItemStatus, retryCount, ralphLoopCount)`, verifica P1-P6
- `StateMachineVerificationResult.java` — record con `PropertyResult` per ogni proprieta'
- `AllowedViolation.java` + `AllowedViolations.java` — annotation repeatable per violazioni controllate
- `@AllowedViolation(property="P4")` applicata su `ItemStatus.DONE` (ralph-loop bounded)
- `StateMachineVerifierTest.java` — 10 test compile-time, 943 test totali green

---

## #39 — Policy Lattice Composition (Teoria dei Reticoli) ✅

**Problema**: le HookPolicy provengono da fonti multiple (HOOK_MANAGER, planner toolHints, config statica).
Non c'e' un meccanismo formale per comporle. Quale vince? Come si combinano allowedTools da fonti diverse?

**Soluzione**: le policy formano un reticolo (lattice) dove `meet` (intersezione) produce la policy
piu' restrittiva. La composizione e' deterministica e associativa.

**Design**:

```java
// HookPolicy come elemento di un meet-semilattice
public class PolicyLattice {

    /** Meet: intersezione (policy piu' restrittiva) */
    public static HookPolicy meet(HookPolicy a, HookPolicy b) {
        return new HookPolicy(
            intersection(a.allowedTools(), b.allowedTools()),     // tools comuni
            intersection(a.ownedPaths(), b.ownedPaths()),         // path comuni
            intersection(a.allowedMcpServers(), b.allowedMcpServers()),
            a.auditEnabled() || b.auditEnabled(),                 // audit se almeno una lo richiede
            min(a.maxTokenBudget(), b.maxTokenBudget()),          // budget piu' basso
            intersection(a.allowedNetworkHosts(), b.allowedNetworkHosts()),
            max(a.requiredHumanApproval(), b.requiredHumanApproval()), // approval piu' stringente
            min(a.approvalTimeoutMinutes(), b.approvalTimeoutMinutes()),
            max(a.riskLevel(), b.riskLevel()),                    // risk piu' alto
            min(a.estimatedTokens(), b.estimatedTokens()),
            a.shouldSnapshot() || b.shouldSnapshot()              // snapshot se almeno una lo richiede
        );
    }

    /** Composizione multi-source */
    public static HookPolicy compose(HookPolicy hookManager, HookPolicy planner, HookPolicy staticConfig) {
        return meet(meet(hookManager, planner), staticConfig);
    }
}
```

- Ordinamento parziale: `a <= b` sse `a` e' piu' restrittiva di `b` (per ogni campo)
- Proprieta' lattice: `meet(a, a) = a` (idempotente), `meet(a, b) = meet(b, a)` (commutativa),
  `meet(meet(a, b), c) = meet(a, meet(b, c))` (associativa)
- `BOTTOM` = policy che vieta tutto (zero tool, zero path)
- `TOP` = policy che permette tutto (wildcard)

- Integrazione: `HookManagerService.resolvePolicy()` usa `PolicyLattice.compose()` invece di fallback a cascata
- ItemStatus CRDT (per #34 federazione): `max(status_A, status_B)` formalizzato come join nel lattice
  `WAITING < DISPATCHED < DONE < FAILED < CANCELLED`

**File**: `PolicyLattice.java` (NEW, in agent-common), `HookManagerService.java` (usare compose),
`PolicyLatticeTest.java` (NEW — verifica proprieta' lattice con property-based testing)

**Sforzo**: 0.5g. **Dipendenze**: nessuna.
**Impatto**: medio — formalizza un pattern gia' presente informalmente, previene errori di composizione.

**Implementazione** (2026-03-09):
- `PolicyLattice.java` — meet-semilattice statico: `meet()`, `compose()`, `TOP`, `BOTTOM`, wildcard handling
- `PolicyLatticeTest.java` — 17 test: 3 assiomi lattice + 2 costanti + 7 campi + 3 compose + 2 wildcard
- Fix pre-existing: `TokenLedgerServiceTest.java` — costruttore 4-arg (metrics, eventPublisher, properties)

---

## #40 — Shapley Value per Reward Distribution (Mechanism Design)

**Problema**: il reward e' assegnato solo al domain worker che produce il risultato finale.
I worker infrastrutturali (CM, HOOK_MANAGER, SCHEMA_MANAGER) che hanno contribuito al successo
non ricevono credit. Questo distorce le metriche ELO e GP per gli infra worker.

**Soluzione**: Shapley Value per distribuire equamente il reward lungo il DAG di dipendenze.

**Design**:

Lo Shapley Value di un worker `i` nella coalizione `N`:
```
phi_i = sum over S subset N\{i} of:
    |S|! * (|N|-|S|-1)! / |N|! * [v(S union {i}) - v(S)]
```

Dove `v(S)` = valore della coalizione S = reward del task se solo i worker in S avessero contribuito.

**Approssimazione pratica** (il calcolo esatto e' O(2^n), ma il DAG ha struttura):
- Il DAG limita le coalizioni possibili (un worker non puo' contribuire senza i suoi predecessori)
- Monte Carlo sampling: campiona K permutazioni del DAG, calcola il contributo marginale medio
- K = 100 permutazioni → errore < 5% per piani tipici (5-20 task)

```java
public class ShapleyCalculator {
    /** Calcola Shapley approssimato per ogni task nel piano */
    public Map<String, Double> computeShapley(Plan plan, double totalReward) {
        Map<String, Double> values = new HashMap<>();
        for (int k = 0; k < MONTE_CARLO_SAMPLES; k++) {
            List<String> permutation = randomTopologicalOrder(plan.getDag());
            Set<String> coalition = new HashSet<>();
            for (String taskKey : permutation) {
                double marginal = marginalContribution(taskKey, coalition, plan);
                values.merge(taskKey, marginal / MONTE_CARLO_SAMPLES, Double::sum);
                coalition.add(taskKey);
            }
        }
        return values;
    }
}
```

- `marginalContribution()`: usa il `processScore` del task + quanto i task successivi
  migliorano con/senza questo task nel contesto
- `ShapleyCalculator.java` (NEW): calcolo Monte Carlo
- `RewardComputationService`: integrazione — dopo aggregatedReward, calcola Shapley e distribuisce
  una quota di reward ai predecessori nel DAG
- `plan_items`: `shapley_value DOUBLE PRECISION` (Flyway V12) — quanto credit ha ricevuto dal DAG
- `worker_elo_stats`: aggiornamento ELO include Shapley (non solo reward diretto)

**Effetto**: i worker infra (CM, HOOK_MANAGER) ricevono credit proporzionale al loro contributo.
L'ELO e il GP riflettono il valore reale, non solo chi produce l'output finale.

**Sforzo**: 2g. **Dipendenze**: nessuna (reward system gia' presente).
**Impatto**: alto — corregge una distorsione fondamentale nelle metriche, incentiva qualita' degli infra worker.

---

## #41 — Topological Pattern Detection (Persistent Homology)

**Problema**: le metriche scalari (reward, ELO, token) non catturano la *struttura* dei pattern
di esecuzione. Certi problemi ricorrenti (cluster di fallimenti correlati, cicli di retry,
regioni dello spazio parametri mai esplorate) hanno una forma topologica.

**Soluzione**: Persistent Homology sugli embeddings 1024-dim dei `task_outcomes` per identificare
feature topologiche stabili.

**Design**:

- **Betti Numbers**: beta_0 = cluster distinti (profili worker separati), beta_1 = cicli (pattern retry ricorrenti),
  beta_2 = vuoti (regioni inesplorate dello spazio dei task)
- **Persistence Diagram**: feature che persistono su molte scale = segnale; feature effimere = rumore
- **Rips Complex**: costruito sugli embeddings `task_outcomes.embedding` (pgvector 1024-dim, gia' indicizzato HNSW)

Implementazione:
- Libreria Java: [javaplex](https://github.com/appliedtopology/javaplex) o implementazione custom
  del Vietoris-Rips complex (per 1024-dim embeddings, sampling necessario: ~200 punti max)
- `TopologicalAnalyzer.java` (NEW):
  - `analyze(List<TaskOutcomeEmbedding>) → TopologyReport`
  - `TopologyReport`: bettiNumbers, persistenceDiagram, significantFeatures
- `PlanController.java`: `GET /api/v1/analytics/topology` → report con interpretazione
  - beta_1 > 0: "Esistono pattern ciclici di retry — investigare causa root"
  - beta_0 alto: "I worker operano in cluster separati — bassa trasferibilita' cross-profile"
  - beta_2 > 0: "Regioni dello spazio dei task mai esplorate — considerare UCB exploration"

**Frequenza**: analisi batch (non real-time). Eseguita dopo ogni 50+ task_outcomes completati.
Risultati cachati in Redis (key: `agentfw:topology:{hash(filtro)}`).

**File**: `TopologicalAnalyzer.java` (NEW), `TopologyReport.java` (NEW, in agent-common),
`PlanController.java` (endpoint analytics)

**Sforzo**: 2.5g. **Dipendenze**: `task_outcomes` con embeddings (#15), RAG pipeline attiva (#23).
**Impatto**: medio — fornisce insight avanzati, non cambia il comportamento. Valore per debugging e tuning.

---

## #42 — Global Task Assignment (Ottimizzazione Combinatoria)

**Problema**: il GP engine ottimizza worker-per-task (locale). Non considera l'assegnamento *globale*
— dato l'intero piano con N task e M worker profiles, qual e' l'assegnamento che minimizza
il tempo totale o massimizza la qualita' complessiva?

**Soluzione**: Hungarian Algorithm per assegnamento ottimale + Critical Path Method per scheduling.

**Design**:

- **Cost Matrix**: `C[i][j]` = costo stimato di assegnare task_i a worker_profile_j.
  Costo = GP-predicted reward negato (massimizzare reward = minimizzare costo negato).
  Se il profile non supporta il workerType → costo = infinito.

- **Hungarian Algorithm** (O(n^3)): trova l'assegnamento che minimizza il costo totale.
  Per piani tipici (5-30 task) → < 1ms. Trattabile anche per 100+ task.

- **Critical Path Method** (CPM):
  - Calcola il percorso critico (sequenza piu' lunga nel DAG pesato per durata stimata)
  - Task sul percorso critico: assegnare il worker migliore (GP mu piu' alta)
  - Task fuori dal percorso critico: possono tollerare worker meno ottimali (budget saving)

```java
public class GlobalAssignmentSolver {
    public AssignmentResult solve(Plan plan, List<WorkerProfile> profiles) {
        double[][] costMatrix = buildCostMatrix(plan, profiles);  // GP predictions
        int[] assignment = hungarianAlgorithm(costMatrix);        // O(n^3)

        List<String> criticalPath = cpCalculator.findCriticalPath(plan.getDag());
        // Boost: task su critical path ricevono il profile ottimale (override Hungarian se necessario)

        return new AssignmentResult(assignment, criticalPath, estimatedCompletion);
    }
}
```

- `GlobalAssignmentSolver.java` (NEW): Hungarian + CPM
- `CriticalPathCalculator.java` (NEW, riutilizzabile da #36 QueueAnalyzer)
- Integrazione: `GpWorkerSelectionService` → se `global-assignment.enabled: true`,
  usa `GlobalAssignmentSolver` invece di selezione per-task
- `PlanController.java`: `GET /api/v1/plans/{id}/assignment-preview` → mostra assegnamento proposto prima dell'esecuzione

**File**: `GlobalAssignmentSolver.java` (NEW), `CriticalPathCalculator.java` (NEW, condiviso con #36),
`GpWorkerSelectionService.java` (integrazione), `AssignmentResult.java` (NEW)

**Sforzo**: 2g. **Dipendenze**: GP engine (#15) per le predizioni di costo.
**Impatto**: alto — ottimizzazione globale vs locale, riduce il tempo totale del piano.

---

## #43 — Differential Privacy per Metriche Federate

**Problema**: nello scenario federato (#34), i server condividono metriche (ELO, reward, token usage).
Ma queste metriche rivelano informazioni sul codice e i pattern dei clienti. Un server curioso
potrebbe inferire il tipo di lavoro svolto da un altro server analizzando le metriche.

**Soluzione**: epsilon-differential privacy — aggiungere rumore calibrato alle metriche
prima di condividerle. Garanzia matematica che un singolo task non influenza significativamente l'output.

**Design (solo interfacce + implementazione base, coerente con #34)**:

```java
// In agent-common
public interface DifferentialPrivacyMechanism {
    /** Aggiunge rumore Laplace calibrato */
    double privatize(double trueValue, double sensitivity, double epsilon);

    /** Composizione: budget di privacy dopo K query */
    double remainingBudget(double initialEpsilon, int queriesUsed);
}

// Implementazione
public class LaplaceMechanism implements DifferentialPrivacyMechanism {
    public double privatize(double trueValue, double sensitivity, double epsilon) {
        double scale = sensitivity / epsilon;
        return trueValue + laplaceSample(scale);
    }
}
```

- `sensitivity` per ELO = 32 (K-factor massimo cambiamento per singolo task)
- `sensitivity` per reward = 2.0 (range [-1, +1])
- `epsilon = 1.0` → rumore ~32 punti ELO → utile per ranking comparativo, inutile per reverse-engineering
- Composizione: dopo K query, `epsilon_totale = K * epsilon_singola`. Budget finito → limitare le query.

- `DifferentialPrivacyMechanism.java` (NEW, in agent-common): interfaccia SPI
- `LaplaceMechanism.java` (NEW): implementazione Laplace
- `FederationMetricsExporter.java` (NEW): applica DP prima di esportare metriche ai peer
- Integrazione con `FederationEventSync` (#34): eventi broadcast includono metriche privatizzate
- `application.yml`: `federation.privacy.epsilon: 1.0, federation.privacy.budget-per-day: 10.0`

**File**: `DifferentialPrivacyMechanism.java` (NEW), `LaplaceMechanism.java` (NEW),
`FederationMetricsExporter.java` (NEW), `application.yml`

**Sforzo**: 1g. **Dipendenze**: #34 (Federazione — senza federazione, nessuna metrica da condividere).
**Impatto**: medio — necessario solo per scenari multi-tenant con data privacy requirements.

---

## Riepilogo unificato — Tutti i 32 items (#30-#61)

### Grafo di dipendenze

```
Dipendenze INTERNE (tra #30-#43):

  #30 Hash Chain ─────────────────┐
       │                          │
       ├──► #34 Federazione ◄─────┤
       │         │                │
       │         └──► #43 DP      │
       │                          │
  #31 Verifiable Compute ─────────┘

  #32 Policy Immutabile
       │
       └──► #37 PID Budget

  #36 Queueing ─── condivide CriticalPathCalculator ───► #42 Global Assignment

Dipendenze INTERNE (tra #45-#49):

  #45 Merkle DAG ──────────────────► #34 Federazione (DAG fingerprints)
  #46 Verifiable Council ~~~~~~~~~~► #49 Quadratic Voting (complementare)
  #47 Reputation Staking ~~~~~~~~~~► #40 Shapley (sinergia reward)
  #48 Content-Addressable ─────────► #31 Verifiable Compute (firma su content_hash)

  Tutti #45, #46, #48 ─────────────► HashUtil (agent-common dopo #30)

Dipendenze INTERNE (tra #50-#61):

  #50 Portfolio ~~~~~~~~~~~~► #53 Bayesian (covarianza come input)
  #51 Market Making ────────► #36 Queueing Theory (throughput)
  #51 Market Making ~~~~~~~~► #56 Criticality (inventory → load)
  #54 Causal ~~~~~~~~~~~~~~~► #53 (probabilita' come input)
  #59 Tropical ─────────────► #36/#42 (CriticalPathCalculator refactor)
  #60 Wasserstein ~~~~~~~~~~► #55 Replicator (distribuzione profili)

Dipendenze ESTERNE (da roadmap items precedenti):

  #23 Enrichment Pipeline ──► #32, #35, #41
  #15 GP Engine ────────────► #35, #41, #42, #47, #50, #52, #53
  #36 Queueing Theory ──────► #51
  #36/#42 CriticalPath ─────► #51, #59
  EmbeddingModel ───────────► #61

Legenda: ──► dipendenza, ~~~► sinergia (non bloccante)
```

### Tier di implementazione

```
TIER 0 — Nessuna dipendenza (possono partire subito, in parallelo)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  #30  Hash Chain Tamper-Proof         0.5g  fondazione crittografica  ✅
  #33  Token Economics Double-Entry    1.5g
  #36  Worker Pool Sizing (Queueing)   1.5g  condivide CriticalPathCalculator con #42
  #38  State Machine Verification      1.0g  test compilazione, nessun rischio runtime  ✅
  #39  Policy Lattice Composition      0.5g  leggero, formalizza pattern esistente  ✅
  #40  Shapley Value Reward            2.0g
                                       ────
                                       7.0g totale Tier 0

TIER 1 — Dipende da Tier 0 o da dipendenze esterne
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  #31  Verifiable Compute (Ed25519)    2.0g  richiede promozione HashUtil (da #30)
  #32  Policy-as-Code Immutabile       1.0g  richiede #23 (Enrichment Pipeline)
                                       ────
                                       3.0g totale Tier 1

TIER 2 — Dipende da Tier 1
━━━━━━━━━━━━━━━━━━━━━━━━━━━
  #34  Federazione (interfacce)        1.0g  richiede #30 + #31
  #37  Adaptive Token Budget (PID)     1.0g  richiede #32 (il PID aggiusta PRIMA del commitment)
                                       ────
                                       2.0g totale Tier 2

TIER 3 — Dipende da dipendenze esterne pesanti o Tier 2
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  #35  Context Quality (Info Theory)   2.0g  richiede #23 + #15  ✅
  #42  Global Assignment (Hungarian)   2.0g  richiede #15, condivide CPCalc con #36  ✅
  #41  Topological Pattern Detection   2.5g  richiede #23 + #15 + dati sufficienti in task_outcomes
  #43  Differential Privacy            1.0g  richiede #34 (Federazione)
                                       ────
                                       7.5g totale Tier 3
```

### Tabella completa ordinata per Tier

| Tier | # | Branca | Titolo | Sforzo | Dip. interne | Dip. esterne | Valore |
|------|---|--------|--------|--------|-------------|-------------|--------|
| 0 | **30** | Crittografia | Hash Chain Tamper-Proof | 0.5g | — | — | Alto |
| 0 | **38** | Verifica Formale | State Machine Verification | 1.0g | — | — | Medio-Alto |
| 0 | **39** | Teoria Reticoli | Policy Lattice Composition | 0.5g | — | — | Medio |
| 0 | **36** | Queueing Theory | Worker Pool Sizing | 1.5g | — | — | Alto |
| 0 | **40** | Mechanism Design | Shapley Value Reward | 2.0g | — | — | Alto |
| 0 | **33** | Token Economics | Double-Entry Budget | 1.5g | — | — | Medio |
| 1 | **31** | Crittografia | Verifiable Compute (Ed25519) | 2.0g | #30 | — | Alto |
| 1 | **32** | Crittografia | Policy-as-Code Immutabile | 1.0g | — | #23 | Medio-Alto |
| 2 | **37** | Teoria Controllo | Adaptive Token Budget (PID) | 1.0g | #32 | — | Alto |
| 2 | **34** | Crittografia | Federazione (interfacce) | 1.0g | #30, #31 | — | Futuro |
| 3 | **35** | Info Theory | Context Quality Scoring | 2.0g | — | #23, #15 | Alto |
| 3 | **42** | Ottimizzazione | Global Task Assignment | 2.0g | (#36 shared) | #15 | Alto |
| 3 | **41** | Topologia | Topological Pattern Detection | 2.5g | — | #23, #15 | Medio |
| 3 | **43** | Diff. Privacy | Metriche Federate Private | 1.0g | #34 | — | Medio |
| — | | | | | | | |
| 0 | **45** | Crittografia | Merkle Tree per DAG | 1.5g | — | HashUtil | Alto |
| 0 | **47** | Teoria dei Giochi | Reputation Staking | 2.0g | — | GP engine, EloStats | Alto |
| 0 | **48** | Crittografia | Content-Addressable Storage | 2.0g | — | HashUtil | Medio-Alto |
| 1 | **46** | Crittografia | Verifiable Council | 1.5g | — | HashUtil | Medio-Alto |
| 2 | **49** | Mechanism Design | Quadratic Voting (Buterin) | 2.5g | #46 (compl.) | — | Alto |
| — | | | | | | | |
| 0 | **52** | Finanza | Worker Greeks (B-S) | 1.5g | — | GP engine | Medio-Alto |
| 0 | **55** | Complessi | Replicator Dynamics | 1.5g | — | ELO data | Medio |
| 0 | **56** | Complessi | Criticality Monitor (Sandpile) | 1.5g | — | — | Alto | ✅ |
| 0 | **58** | Matematica | Spectral DAG Decomposition | 2.0g | — | EJML lib | Alto |
| 0 | **59** | Matematica | Tropical Scheduler | 1.5g | — | — | Medio-Alto |
| 0 | **61** | Matematica | Submodular Council Selection | 2.0g | — | EmbeddingModel | Alto |
| 1 | **50** | Finanza | Portfolio Theory (Markowitz) | 2.0g | — | GP engine (#15) | Alto |
| 1 | **51** | Finanza | Market Making (Avellaneda) | 2.0g | — | #36 Queueing | Alto |
| 1 | **53** | Finanza | Bayesian Success Prediction | 2.0g | — | GP engine (#15) | Alto |
| 1 | **57** | Complessi | Swarm Intelligence (ACO) | 2.0g | — | Reward data | Medio-Alto |
| 2 | **54** | Finanza | Causal Inference (Pearl) | 2.5g | — | task_outcomes | Alto |
| 2 | **60** | Matematica | Wasserstein Distance (OT) | 2.0g | — | task_outcomes | Alto |

### Note sulle dipendenze critiche

1. **#30 e' il prerequisito universale**: la promozione di `HashUtil` a `agent-common` (necessaria per #30)
   sblocca #31, che a sua volta sblocca #34 e #43. Fare #30 per primo e' obbligatorio.

2. **#23 (Enrichment Pipeline) blocca 4 items**: #32, #35, #41, e indirettamente #37 (via #32).
   Se #23 non e' ancora implementato, il Tier 1-3 deve aspettare. Alternativa: #32 puo' essere
   implementato con HookPolicy statiche (test-only) senza #23, poi attivato quando #23 e' pronto.

3. **#15 (GP Engine) blocca 3 items**: #35, #41, #42. Queste feature usano i `task_outcomes`
   con embeddings generati dal GP engine. Senza GP, mancano i dati di training.

4. **#36 e #42 condividono `CriticalPathCalculator`**: implementare #36 prima di #42 consente
   di riusare il codice. Il `CriticalPathCalculator` calcola il percorso critico nel DAG — topological
   sort + longest path (O(V+E), banale per DAG).

5. **#37 (PID) deve agire PRIMA di #32 (commitment hash)**: il PID aggiusta `maxTokenBudget`
   nella HookPolicy. Se la policy viene "sigillata" con hash commitment (#32), il PID deve
   completare la sua regolazione *prima* del commitment. Ordine: PID aggiusta → hash sigilla → dispatch.

6. **#40 (Shapley) e' autonomo ma potenzia #33 (Token Economics)**: Shapley distribuisce reward
   lungo il DAG; Token Economics traccia debit/credit. Insieme danno visibilita' completa su
   chi spende e chi contribuisce.

7. **#43 (DP) ha senso solo con #34 (Federazione)**: senza federazione, non ci sono metriche
   da condividere e la privacy e' irrilevante. Implementare solo se #34 viene attivato.

8. **#45, #46, #48 dipendono da HashUtil in agent-common**: dopo #30, `HashUtil` e' accessibile
   dall'orchestratore. Senza #30, duplicare localmente (pragmatico ma non ideale).

9. **#49 richiede robustezza nel parsing LLM**: il Quadratic Voting funziona solo se il member LLM
   restituisce JSON strutturato valido. Fallback obbligatorio: raw text con peso standard.
   Feature flag `council.quadratic-voting-enabled` (default false).

10. **#47 (Reputation Staking) potenzia #40 (Shapley)**: lo stake pre-dispatch rende la distribuzione
    Shapley piu' significativa — i profili che rischiano di piu' ricevono reward proporzionalmente.

11. **GP Engine (#15) prerequisito per #50, #52, #53**: leggono `task_outcomes` e usano predizioni GP
    per calcoli derivati (covarianze, Greeks, probabilita').

12. **#59 (Tropical) refactora #36/#42**: `CriticalPathCalculator` riscritto su algebra tropicale,
    backward compatible. Fare #59 dopo #36 per semplificare il refactor.

13. **#56 (Criticality) indipendente**: piu' alto rapporto valore/sforzo tra #50-#61, partenza immediata.

14. **#61 e #49 complementari**: #61 seleziona *chi* partecipa al council, #49 pesa *quanto*.
    Insieme formano un sistema completo di composizione del council.

15. **Flyway V15 solo per #53**: gli altri items #50-#61 operano su dati esistenti o in-memory/Redis.

### Ordine consigliato di implementazione

```
Fase 1 (fondazioni, ~3.5g):      #30 ✅ → #38 ✅ → #39 ✅
Fase 2 (pratico, ~5g):           #36 ✅ → #33 ✅ → #40 ✅
Fase 3 (trust, ~4g):             #31 ✅ → #32 ✅ → #37
Fase 4 (avanzato, ~7g):          #34 → #35 ✅ → #42 ✅ → #41 → #43
Fase 5 (advanced, ~5.5g):        #45 → #47 → #48  (parallelo)
Fase 6 (~1.5g):                  #46
Fase 7 (~2.5g):                  #49
Fase 8a (fondazioni, ~6.5g):     #56 ✅ → #59 → #55 → #52
Fase 8b (strutturale, ~6.0g):    #58 → #61 → #57
Fase 8c (economico, ~6.0g):      #50 → #51 → #53
Fase 8d (avanzato, ~4.5g):       #54 → #60
                                   ─────────────────────
                                   Totale: ~52g (#30-#61)
```

### Codice condiviso tra items

| Classe | Usata da | Modulo |
|--------|----------|--------|
| `HashUtil.java` (promossa) | #30, #31, #32, #45, #46, #48 | agent-common |
| `CriticalPathCalculator.java` | #36, #42, #51, #59 | orchestrator/graph |
| `PolicyLattice.java` | #39, #32 (canonical JSON) | agent-common |
| `WorkerEloStats.java` | #47, #55 | orchestrator/reward |
| `CouncilService.java` | #46, #49, #61 | orchestrator/council |
| `TropicalSemiring.java` | #59 | orchestrator/graph |
| `CovarianceMatrix.java` | #50 | orchestrator/budget |
| `TaskOutcomeService.java` | #50, #52, #53, #54, #55, #60 | orchestrator/gp |
| `GpWorkerSelectionService.java` | #50, #52, #53, #60 | orchestrator/gp |
| `PlanGraphService.java` | #58, #59 | orchestrator/graph |
| `AnalyticsController.java` | #55, #60 | orchestrator/api |

**Totale complessivo (#30-#61)**: ~52 giorni di lavoro
(Mathematical Foundations ~19.5g + Advanced Mechanisms ~10g + Research Domains ~22.5g).

---

# Advanced Mechanisms (#45-#49)

Roadmap items derivati dalle idee blockchain-inspired (A-D, promosse a #45-#48)
piu' Quadratic Voting (#49, Vitalik Buterin 2019). Branche: crittografia applicata,
teoria dei giochi, mechanism design.

---

## #45 — Merkle Tree per DAG Verification (Crittografia)

**Problema**: il DAG di un piano e' serializzato e trasmesso via REST (`GET /api/v1/plans/{id}/dag`)
e SSE. Non esiste garanzia crittografica che la struttura ricevuta dal client corrisponda a quella
persistita nel DB. Un bug nel serializer, un proxy di caching, o un attore con accesso al DB potrebbe
alterare i `dependsOn` di un nodo senza che il cambiamento venga rilevato.
`Plan.workingTreeDiffHash` traccia il diff git, non la struttura del DAG.
`PlanGraphService.toJson()` e `toMermaid()` non includono nessun hash.

**Soluzione**: ogni `PlanItem` ottiene un `dagHash = SHA-256(taskKey || workerType || title || sort(predecessors_dagHashes))`.
Il sort deterministico dei predecessori garantisce che lo stesso DAG logico produca sempre lo stesso hash.
Il `Plan.merkleRoot` e' il SHA-256 combinato degli hash dei nodi sink (foglie del DAG).

**Design**:

```java
// DagHashService.java (NEW)
@Service
public class DagHashService {

    public void recomputeHashes(Plan plan) {
        Map<String, String> hashByKey = new LinkedHashMap<>();

        List<PlanItem> ordered = plan.getItems().stream()
            .sorted(Comparator.comparingInt(PlanItem::getOrdinal))
            .toList();

        for (PlanItem item : ordered) {
            String predecessorHashes = item.getDependsOn().stream()
                .sorted()
                .map(key -> hashByKey.getOrDefault(key, "0".repeat(64)))
                .collect(Collectors.joining("|"));

            String nodeInput = item.getTaskKey()
                + "|" + item.getWorkerType().name()
                + "|" + item.getTitle()
                + "|" + predecessorHashes;

            String hash = HashUtil.sha256(nodeInput);
            hashByKey.put(item.getTaskKey(), hash);
            item.setDagHash(hash);
        }

        // Sink nodes = nodi che non sono predecessori di nessun altro
        Set<String> allDependencies = plan.getItems().stream()
            .flatMap(i -> i.getDependsOn().stream())
            .collect(Collectors.toSet());

        String merkleInput = plan.getItems().stream()
            .filter(i -> !allDependencies.contains(i.getTaskKey()))
            .map(PlanItem::getDagHash)
            .sorted()
            .collect(Collectors.joining("|"));

        plan.setMerkleRoot(HashUtil.sha256(merkleInput));
    }

    public Optional<String> verify(Plan plan) {
        // Ricomputa e confronta — restituisce primo taskKey corrotto
        // ... (stessa logica di recomputeHashes ma con confronto)
    }
}
```

- Ricalcolo: in `PlannerService.decompose()` dopo costruzione DAG, e in `addDependency()` (missing-context loop)
- `PlanController.java`: `GET /api/v1/plans/{id}/dag-verify` → `{planId, merkleRoot, valid, brokenAtKey}`
- `PlanGraphService.toJson()`: include `dagHash` in ogni nodo e `merkleRoot` nel root JSON
- Flyway V11: `ALTER TABLE plan_items ADD COLUMN dag_hash VARCHAR(64)`,
  `ALTER TABLE plans ADD COLUMN merkle_root VARCHAR(64)`

**File**: `DagHashService.java` (NEW), `PlanItem.java` (+dagHash), `Plan.java` (+merkleRoot),
`PlannerService.java` (chiama recomputeHashes), `PlanGraphService.java` (include hash in JSON),
`PlanController.java` (+endpoint), Flyway V11

**Sforzo**: 1.5g. **Dipendenze**: `HashUtil` (opz. aspetta #30 per promozione a agent-common, oppure duplica locale).
**Impatto**: alto — ogni piano ha un fingerprint crittografico verificabile. Prerequisito per federazione (#34).

---

## #46 — Verifiable Council Deliberation (Crittografia)

**Problema**: i member LLM del council sono gia' consultati in parallelo (`consultMembersParallel()`),
quindi l'anchoring bias e' gia' mitigato. Il problema reale e' diverso: tra la consultazione
e la sintesi, il testo transita in memoria senza garanzia crittografica di integrita'.
Non esiste audit trail delle deliberazioni raw — solo il `CouncilReport` finale viene persistito,
perdendo i view grezzi dei singoli membri.

**Soluzione**: commit-reveal per integrita' verificabile. Ogni view grezzo viene hashato con un nonce,
il commitment viene persistito in DB, e il verifier controlla il match prima di passare alla sintesi.

**Design**:

```java
// CouncilCommitment.java (NEW)
public record CouncilCommitment(
    String memberProfile,
    String commitHash,    // SHA-256(rawOutput | nonce)
    String nonce,         // UUID, generato per-member per-session
    String rawOutput,     // il view grezzo del membro
    Instant committedAt
) {
    public boolean verify() {
        return HashUtil.sha256(rawOutput + "|" + nonce).equals(commitHash);
    }
}
```

- `CouncilService.consultMembersParallel()`: modificato per restituire `List<CouncilCommitment>`
  invece di `Map<String,String>`. Ogni view viene committato con nonce UUID prima della sintesi.
- `verifyAndExtract()`: verifica tutti i commitments. Output con hash non corrispondente
  viene scartato dalla sintesi con log `SECURITY WARNING`.
- Tabella `council_commitments`: plan_id, session_type, task_key, member_profile,
  commit_hash, nonce, raw_output, committed_at, verified, verification_failed
- `PlanController.java`: `GET /api/v1/plans/{id}/council-audit` → lista commitments con flag verified

**File**: `CouncilCommitment.java` (NEW), `CouncilCommitmentRepository.java` (NEW),
`CouncilService.java` (modifica), `CouncilController.java` (NEW, endpoint audit),
Flyway V12 (`council_commitments` table)

**Sforzo**: 1.5g. **Dipendenze**: `HashUtil`. Nessuna dipendenza bloccante.
**Impatto**: medio-alto — audit trail crittografico delle deliberazioni. Base per federazione (#34).

---

## #47 — Reputation Staking (Teoria dei Giochi)

**Problema**: la selezione GP UCB ottimizza per expected reward ma non considera il rischio.
Un profilo con `mu=0.7, sigma2=0.4` (alta incertezza) viene trattato come uno con
`mu=0.7, sigma2=0.01` (prevedibile). Non c'e' meccanismo di accountability pre-task:
un profilo che fallisce sistematicamente vede scendere l'ELO, ma senza costo immediato.

**Soluzione**: prima del dispatch, il profilo "mette in gioco" una quota della propria reputazione (ELO)
commisurata alla difficolta' del task. Successo → stake + bonus. Fallimento → stake perso.

**Design**:

```
stake = base_stake * (1 + complexity_factor)

base_stake        = 0.05 * workerElo (5% dell'ELO corrente)
complexity_factor = sigma2 dal GP (clampato a [0.0, 2.0])

Liquidazione successo:  workerElo += stake * 0.30 (bonus)
Liquidazione fallimento: workerElo -= stake (perso)

UCB adjusted: UCB_with_stake = mu + kappa * sigma - (stake / workerElo)
```

- `ReputationStakingService.java` (NEW): `stake(profile, gpSigma2)`, `settle(profile, success, amount)`,
  `stakePenalty(profile, stake)`
- `WorkerEloStats.java`: +3 campi (`staked_reputation`, `total_staked`, `total_forfeited`),
  +2 metodi (`addStake()`, `settleStake()`)
- `OrchestrationService`: chiama `stake()` pre-dispatch, `settle()` in `onTaskCompleted()`
- `GpWorkerSelectionService`: UCB adjustment con stake penalty
- `PlanItem.java`: +`stakedAmount` (registra quanto stakato per questo task)
- Flyway V13: ALTER TABLE `worker_elo_stats` (+3 colonne), ALTER TABLE `plan_items` (+staked_amount)

**Cold start**: profili senza record ELO skippano lo staking (stake=0). Graceful degradation
con GP disabilitato: sigma2=0 → stake basale senza complexity bonus.

**File**: `ReputationStakingService.java` (NEW), `WorkerEloStats.java` (modifica),
`OrchestrationService.java` (modifica), `GpWorkerSelectionService.java` (modifica),
`PlanItem.java` (modifica), Flyway V13

**Sforzo**: 2g. **Dipendenze**: `EloRatingService` e GP engine (gia' implementati). Sinergia con #40 (Shapley).
**Impatto**: alto — self-selection game-theoretic. Riduce varianza fallimenti.

---

## #48 — Content-Addressable Storage per Artifact (Crittografia)

**Problema**: i risultati dei worker sono salvati inline in `plan_items.result` (TEXT).
Nessuna deduplicazione: due task con output identico → due copie. Nessuna verifica di integrita'.
`AgentResult.promptHash` e `modelId` sono riservati ma sempre null.

**Soluzione**: Content-Addressable Store (CAS) ispirato a Git objects.
`SHA-256(content)` come chiave primaria. Deduplica automatica, verifica integrita' gratuita.

**Design**:

```sql
CREATE TABLE artifact_store (
    content_hash  VARCHAR(64)  PRIMARY KEY,
    content       TEXT         NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    access_count  BIGINT       NOT NULL DEFAULT 1
);

ALTER TABLE plan_items
    ADD COLUMN result_hash VARCHAR(64) REFERENCES artifact_store(content_hash),
    ADD COLUMN prompt_hash VARCHAR(64);
```

```java
@Service
public class ArtifactStore {
    public String save(String content) {
        String hash = HashUtil.sha256(content);
        repository.findById(hash).ifPresentOrElse(
            existing -> { existing.incrementAccessCount(); repository.save(existing); },
            () -> repository.save(new ArtifactBlob(hash, content, content.getBytes(UTF_8).length))
        );
        return hash;
    }

    public Optional<String> get(String hash) {
        return repository.findById(hash).map(blob -> {
            String recomputed = HashUtil.sha256(blob.getContent());
            if (!recomputed.equals(hash))
                throw new ArtifactCorruptedException("hash mismatch: " + hash);
            return blob.getContent();
        });
    }
}
```

- `OrchestrationService.onTaskCompleted()`: salva nel CAS, setta `result_hash`.
  Mantiene `result` inline per backward compatibility (migrazione graduale).
- `AbstractWorker.java` (worker-sdk): popola `promptHash` con SHA-256 del prompt inviato al LLM.
- Flyway V14 con backfill: hash degli `result` esistenti → popola `artifact_store` e `result_hash`.
- Endpoint: `GET /api/v1/analytics/artifact-dedup` → stats deduplicazione.

**File**: `ArtifactStore.java` (NEW), `ArtifactBlob.java` (NEW entity), `ArtifactRepository.java` (NEW),
`ArtifactCorruptedException.java` (NEW), `OrchestrationService.java` (modifica),
`PlanItem.java` (+resultHash, +promptHash), `AbstractWorker.java` (modifica, promptHash),
Flyway V14, `ArtifactController.java` (NEW, endpoint analytics)

**Sforzo**: 2g. **Dipendenze**: `HashUtil`. Sinergia con #31 (Verifiable Compute: firma sul content_hash).
**Impatto**: medio-alto — deduplicazione ~20-35%, integrita' verificabile, `promptHash` finalmente popolato.

---

## #49 — Quadratic Voting per Council Weighting (Mechanism Design — Vitalik Buterin)

**Problema**: nel `CouncilService`, tutti i membri hanno peso uguale nella sintesi.
Una raccomandazione critica del `security-specialist` ("non usare MD5 per password")
ha lo stesso peso di un suggerimento stilistico del `ui-ux-specialist` ("bottoni arrotondati").
Non esiste meccanismo per esprimere la **forza** delle preferenze.

**Soluzione**: Quadratic Voting (Vitalik Buterin, 2019 — "Quadratic Payments: A Primer").
Ogni membro riceve N voice credits (basati su ELO). Allocare k voti a una raccomandazione
costa k^2 crediti. Il costo marginale crescente impedisce la dominazione di un singolo membro.

**Design**:

```
Budget per membro: N_credits = 100 + floor((elo - 1600) / 100) * 10
                   (range: 70-160 crediti)

Costo di k voti su raccomandazione j: k^2
Vincolo: sum_j k^2_ij <= N_credits per ogni membro i
Peso finale raccomandazione j: W_j = sum_i k_ij

Esempio: 8^2 + 6^2 = 64 + 36 = 100 crediti (budget esaurito)
         → 8 voti su "bcrypt cost=12" + 6 voti su "rate limiting"
```

**Perche' quadratic e non lineare**: con costo lineare (plutocracy), un membro con ELO alto
puo' dominare ogni raccomandazione. Con QV, concentrare voti e' costoso — incentiva la diversificazione.

```java
@Service
public class QuadraticVotingService {
    private static final int BASE_VOICE_CREDITS = 100;

    public int computeVoiceCredits(double eloRating) {
        int bonus = (int) Math.floor((eloRating - 1600.0) / 100.0) * 10;
        return Math.max(70, Math.min(160, BASE_VOICE_CREDITS + bonus));
    }

    public ValidationResult validateBudget(MemberVoteAllocation alloc, int credits) {
        long cost = alloc.recommendations().stream()
            .mapToLong(r -> (long) r.votesAllocated() * r.votesAllocated())
            .sum();
        return new ValidationResult(cost <= credits, ...);
    }

    public List<WeightedRecommendation> aggregate(List<MemberVoteAllocation> allocs) {
        Map<String, Integer> totalVotes = new LinkedHashMap<>();
        for (var alloc : allocs)
            for (var rec : alloc.recommendations())
                totalVotes.merge(normalize(rec.text()), rec.votesAllocated(), Integer::sum);
        return totalVotes.entrySet().stream()
            .sorted(Map.Entry.<String,Integer>comparingByValue().reversed())
            .map(e -> new WeightedRecommendation(e.getKey(), e.getValue()))
            .toList();
    }

    public record Recommendation(String id, String text, int votesAllocated, String rationale) {}
    public record MemberVoteAllocation(String memberProfile, String analysis,
                                        List<Recommendation> recommendations, int voiceCreditsUsed) {}
    public record WeightedRecommendation(String text, int totalVotes) {}
}
```

- Prompt member aggiornato: include budget voice credits, formato JSON per raccomandazioni con voti.
  Fallback: se il LLM non produce JSON valido, usa il raw text con peso standard (pre-#49).
- `CouncilService.synthesize()`: riceve le raccomandazioni weighted-by-QV ordinate per influenza,
  il COUNCIL_MANAGER sa che "bcrypt cost=12" ha 14 voti e "bottoni arrotondati" ha 2.
- `CouncilProperties.java`: +`quadraticVotingEnabled` (default false), +`baseVoiceCredits` (default 100)

**File**: `QuadraticVotingService.java` (NEW), `CouncilService.java` (modifica),
`CouncilProperties.java` (modifica), `prompts/council/member-qv.prompt.md` (NEW)

**Sforzo**: 2.5g. **Dipendenze**: nessuna bloccante. Complementare a #46 (le allocazioni QV sono incluse
nel commitment per audit trail). Sinergia con #40 (Shapley: QV totalVotes come proxy del contributo).
**Impatto**: alto — prima metrica di intensita' delle preferenze nel council.

---

## Riepilogo items Advanced Mechanisms (#45-#49)

### Grafo di dipendenze

```
#45 Merkle DAG ──────────────────────────────► #34 Federazione (futuro, DAG fingerprints)

#46 Verifiable Council ~~~~~~~~~~~~~~~~~~~~~~► #49 Quadratic Voting
(complementare, non bloccante)                 (QV allocations nel commitment audit)

#47 Reputation Staking ~~~~~~~~~~~~~~~~~~~~~► #40 Shapley (sinergia reward)

#48 Content-Addressable ────────────────────► #31 Verifiable Compute (firma su content_hash)

Tutti #45, #46, #48 ────────────────────────► HashUtil (worker-sdk, o agent-common dopo #30)

Legenda: ──► dipendenza, ~~~► sinergia (non bloccante)
```

### Tier di implementazione

```
TIER 0 — Partenza parallela (nessuna dipendenza interna tra #45-#49)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  #45  Merkle Tree per DAG Verification          1.5g
  #47  Reputation Staking                        2.0g
  #48  Content-Addressable Storage               2.0g
                                                 ────
                                                 5.5g totale Tier 0

TIER 1 — Dopo Tier 0 (riusa HashUtil e pattern)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  #46  Verifiable Council Deliberation           1.5g
                                                 ────
                                                 1.5g totale Tier 1

TIER 2 — Dopo Tier 1 (piu' efficace con #46 per audit)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  #49  Quadratic Voting per Council              2.5g
                                                 ────
                                                 2.5g totale Tier 2
```

### Tabella completa

| Tier | # | Branca | Titolo | Sforzo | Dip. interne | Dip. esterne | Valore |
|------|---|--------|--------|--------|-------------|-------------|--------|
| 0 | **45** | Crittografia | Merkle Tree per DAG | 1.5g | — | HashUtil | Alto |
| 0 | **47** | Teoria dei Giochi | Reputation Staking | 2.0g | — | GP engine, EloStats | Alto |
| 0 | **48** | Crittografia | Content-Addressable Storage | 2.0g | — | HashUtil | Medio-Alto |
| 1 | **46** | Crittografia | Verifiable Council | 1.5g | — | HashUtil | Medio-Alto |
| 2 | **49** | Mechanism Design | Quadratic Voting (Buterin) | 2.5g | #46 (compl.) | — | Alto |
| | | | **Totale** | **9.5g** | | | |

### Note sulle dipendenze critiche

1. **HashUtil denominatore comune di #45, #46, #48**: attualmente in `worker-sdk`, non accessibile
   dall'orchestratore. Opzione A: duplicare localmente (pragmatico). Opzione B: fare #30 prima (0.5g,
   promuove ad `agent-common`). Raccomandazione: #30 come prerequisito.

2. **#49 richiede robustezza nel parsing LLM**: il QV funziona solo se il member LLM restituisce JSON
   strutturato valido. Fallback obbligatorio: se parsing fallisce, usa raw text con peso standard.
   Feature flag `council.quadratic-voting-enabled` per abilitare/disabilitare.

3. **#48 backfill Flyway potenzialmente lento**: il `UPDATE plan_items SET result_hash = sha256(result)`
   su grandi dataset puo' richiedere minuti. Eseguire come migration separata con check idempotente.

4. **Flyway numbering**: V11 (#45), V12 (#46), V13 (#47), V14 (#48). #49 non richiede migration.

### Ordine consigliato di implementazione

```
Prerequisito (0.5g):  #30 promozione HashUtil → agent-common
Fase 1 (parallelo, ~5.5g):  #45 → #47 → #48
Fase 2 (~1.5g):             #46
Fase 3 (~2.5g):             #49
                             ─────────────
                             Totale: ~10g (incluso prerequisito)
```

### Codice condiviso tra items

| Classe | Usata da | Modulo |
|--------|----------|--------|
| `HashUtil.java` | #45, #46, #48 | agent-common (dopo #30) |
| `WorkerEloStats.java` | #47 | orchestrator/reward |
| `CouncilService.java` | #46, #49 | orchestrator/council |

**Totale Advanced Mechanisms**: ~9.5 giorni di lavoro (~10g con prerequisito #30).

---

# Research Domains (#50-#61)

Fase 8 della roadmap: ricerca in tre domini — intuizioni economico-finanziarie, gestione sistemi complessi,
matematica avanzata. I concetti sono selezionati per qualita' e impatto, non per presenza nel codebase.

Tre branche: **Finanza** (#50-#54), **Sistemi Complessi** (#55-#57), **Matematica Avanzata** (#58-#61).

---

## Dominio A: Intuizioni Economico-Finanziarie (#50-#54)

---

### #50 — Portfolio Theory per Worker Allocation (Markowitz Mean-Variance)

**Problema**: il budget token e' allocato con ceiling statici per worker type (`PlanRequest.Budget.perWorkerType`).
Non c'e' considerazione del trade-off rischio/rendimento. Un'allocazione "concentrata" (tutto il budget
su un singolo modello tier) ha alta varianza: se quel profilo fallisce, il piano perde l'intero investimento.
`TokenBudgetService` modula il limite con GP (`effectiveLimit = base * (1 + alpha * sigma^2) * clip(mu)`),
ma agisce task-per-task, non a livello globale di piano.

**Soluzione**: applicare l'ottimizzazione Mean-Variance di Markowitz. I worker profile diventano "asset":
il reward medio (`task_outcomes.actual_reward`) e' il "rendimento", la varianza dei reward e' il "rischio".
Il piano ha un "portafoglio" di allocazioni budget, e l'ottimizzatore trova la frontiera efficiente.

**Design**:

```
Formule:
  E[R_p] = sum(w_i * E[R_i])                            // rendimento atteso portafoglio
  sigma^2_p = sum_i sum_j (w_i * w_j * Cov(R_i, R_j))   // varianza portafoglio
  Sharpe Ratio = (E[R_p] - R_f) / sigma_p               // R_f = reward minimo accettabile (0.3)

Vincoli:
  sum(w_i) = 1 (allocazione completa)
  w_i >= 0 (no short selling)
  w_i <= 0.6 (diversificazione minima — nessun profilo prende piu' del 60%)
```

- **Matrice di covarianza**: calcolata da `task_outcomes` raggruppati per `worker_profile`.
  Richiede almeno 100 record per coppia di profili per stabilita' statistica.
  Fallback alla diagonale (solo varianza) con meno dati.
- **Risolutore**: scansione parametrica di `target_return` in 20 step e selezione del portafoglio
  con Sharpe Ratio massimo.
- `PortfolioOptimizer.java` (NEW, `orchestrator/budget/`): frontiera efficiente, pesi ottimali
- `CovarianceMatrix.java` (NEW): covarianza da `task_outcomes`, aggiornamento incrementale (Welford)
- `TokenBudgetService.java` (modifica): pesi dal portfolio invece di ceiling flat
- `PlanRequest.java`: `+riskTolerance: Double` (0.0-1.0, default null = backward compatible)
- `PlanController.java`: `GET /api/v1/plans/{id}/portfolio-analysis`
- Nessuna migrazione DB (dati gia' in `task_outcomes`)

**File**: `PortfolioOptimizer.java` (NEW), `CovarianceMatrix.java` (NEW), `TokenBudgetService.java` (modifica),
`PlanRequest.java` (+riskTolerance), `PlanController.java` (+endpoint)

**Sforzo**: 2g. **Dipendenze**: GP engine (#15, `task_outcomes` con >100 record per coppia profilo).
**Impatto**: alto — trasforma il budget statico in allocazione risk-adjusted.
**Riferimento**: Markowitz, H. (1952), "Portfolio Selection", *Journal of Finance*, 7(1), 77-91.

---

### #51 — Market Making per Task Queue Management (Bid-Ask + Inventory Risk)

**Problema**: il dispatch dei task e' FIFO all'interno del batch pronto (`findDispatchableItems()`).
Non c'e' load shedding: se il pool di worker BE ha 15 task pendenti ma throughput 2/ora, i nuovi task
arrivano allo stesso ritmo. Non c'e' riassegnamento di task stalli (un task pendente da ore senza pickup).
`AutoRetryScheduler` gestisce solo task FAILED, non task DISPATCHED che non tornano mai.

**Soluzione**: modellare ogni worker pool come un market maker con inventario. Lo spread (bid-ask) si allarga
quando l'inventario (task pendenti) supera il target (calcolato dal throughput storico). Task con priorita'
piu' alta (urgency, critical path) ricevono dispatch preferenziale. Task stalli subiscono decay e riassegnamento.

**Design**:

```
Parametri per worker type:
  target_inventory     = avg(tasks_dispatched_per_hour) * 2
  base_spread          = 1.0
  current_inventory    = count(DISPATCHED, non DONE/FAILED)

Spread adjustment:
  spread = base_spread * (1 + |current_inventory - target_inventory| / target_inventory)

Priority scoring:
  priority(task) = (1 / spread) * urgency_factor * critical_path_bonus
  urgency_factor     = 1.0 + (hours_since_ready / 4.0)
  critical_path_bonus = 2.0 se task e' sul percorso critico

Inventory decay:
  task pendente > decay_threshold_hours → riassegnato a profilo alternativo
  decay_threshold configurable: default 2h
```

- `MarketMakingDispatcher.java` (NEW, `orchestrator/orchestration/`): wrappa la logica di dispatch
- `InventoryTracker.java` (NEW): inventario per worker type in Redis DB 3
- `DispatchProperties.java` (modifica): `+inventory.target-multiplier`, `+inventory.decay-threshold-hours`
- `OrchestrationService.java` (modifica): ordina task per priorita' prima del dispatch
- `AutoRetryScheduler.java` (modifica): check staleness per task DISPATCHED > `decay_threshold_hours`

**File**: `MarketMakingDispatcher.java` (NEW), `InventoryTracker.java` (NEW), `DispatchProperties.java` (modifica),
`OrchestrationService.java` (modifica), `AutoRetryScheduler.java` (modifica)

**Sforzo**: 2g. **Dipendenze**: #36 (Queueing Theory, stime throughput), `CriticalPathCalculator`.
**Impatto**: alto — previene accumulo di task, riassegna stalli, riduce latenza del piano.
**Riferimento**: Avellaneda, M. & Stoikov, S. (2008), "High-frequency trading in a limit order book",
*Quantitative Finance*, 8(3), 217-224.

---

### #52 — Black-Scholes Greeks per Worker Risk Profiling

**Problema**: ELO e' un singolo numero (`WorkerEloStats.eloRating`). GP fornisce `(mu, sigma^2)`.
Nessuno dei due cattura *come* la performance cambia al variare dei parametri del task. Un profilo "be-java"
con mu=0.8 potrebbe avere mu=0.85 su task semplici e mu=0.2 su task complessi — il punto medio nasconde
il gradiente. Non c'e' analisi di sensitivita'.

**Soluzione**: modellare la qualita' del worker come "valore dell'opzione" e calcolare le Greeks
(derivate parziali) via finite differences sulle predizioni GP. Non Black-Scholes come modello
di pricing, ma come *metafora computazionale* per l'analisi di sensitivita'.

**Design**:

```
Greeks calcolate per finite differences sulle predizioni GP:

  Delta = dmu/d(difficulty)         // pendenza — come degrada la qualita' con la complessita'
  Gamma = d^2mu/d(difficulty)^2     // convessita' — la degradazione accelera?
  Vega  = dmu/d(sigma^2)           // sensitivita' all'incertezza (domini sconosciuti)
  Theta = dmu/d(time)              // velocita' di miglioramento (learning rate del profilo)

Finite differences centrali:
  Delta ≈ (mu(x+h) - mu(x-h)) / (2h)
  Gamma ≈ (mu(x+h) - 2*mu(x) + mu(x-h)) / h^2
```

- `WorkerGreeksService.java` (NEW, `orchestrator/gp/`): calcola le 4 Greeks per ogni profilo candidato
- `WorkerGreeks.java` (NEW, record): `{delta, gamma, vega, theta, profile, workerType}`
- `GpWorkerSelectionService.java` (modifica): arricchisce il `ProfileSelection` con le Greeks.
  Se `delta` fortemente negativo → penalizza il profilo nella selezione.
- `RewardController.java`: `GET /api/v1/workers/{profile}/greeks`
- Nessuna migrazione DB (Greeks calcolate on-the-fly)

**File**: `WorkerGreeksService.java` (NEW), `WorkerGreeks.java` (NEW), `GpWorkerSelectionService.java` (modifica),
`RewardController.java` (+endpoint)

**Sforzo**: 1.5g. **Dipendenze**: GP engine (#15) con dati sufficienti.
**Impatto**: medio-alto — analisi di sensitivita' per selezione profilo in scenari eterogenei.
**Riferimento**: Black, F. & Scholes, M. (1973), "The Pricing of Options and Corporate Liabilities",
*JPE*, 81(3), 637-654. Applicato come metafora per analisi di sensitivita'.

---

### #53 — Bayesian Success Prediction (Pre-Dispatch Probability)

**Problema**: nessuna stima probabilistica di successo prima dell'esecuzione. GP fornisce mu (reward atteso),
ma non `P(success)` come probabilita' calibrata. Lo status binario DONE/FAILED perde informazione
(un task DONE con reward 0.2 e' quasi un fallimento). Non esiste admission control.

**Soluzione**: regressione logistica Bayesiana sulle feature del task:
`P(success | embedding, profile, context_quality, budget) = sigmoid(beta . x)`.
Prior dal posterior GP. Calibrazione via Platt scaling.

**Design**:

```
Feature vector (dimensione: 1024 + 5):
  - task_embedding (1024 dim)
  - gp_mu (predizione GP)
  - gp_sigma2 (incertezza GP)
  - elo_at_dispatch (rating profilo)
  - context_quality_score (da #35, oppure 0)
  - log(budget_remaining) (tokens disponibili)

Soglie:
  - success_threshold = 0.5 (reward > 0.5 = successo)
  - dispatch_threshold = 0.3 (sotto → warning/blocco)
  - decompose_threshold = 0.5 (TUTTI i profili < 0.5 → suggerire decomposizione)

Calibrazione Platt: P_calibrated = sigmoid(a * P_raw + b)
```

- `BayesianSuccessPredictor.java` (NEW, `orchestrator/gp/`): pre-dispatch check
- `SuccessPrediction.java` (NEW, record): `{probability, calibratedProbability, threshold, action}`
  dove action = ALLOW, WARN, DECOMPOSE
- `OrchestrationService.java` (modifica): pre-dispatch check, `LOW_SUCCESS_PROBABILITY` event
- `PlanItem.java`: `+predictedSuccessProbability: Double`
- `PlanRequest.java`: `+admissionControl: Boolean` (default false)
- Flyway V15: `ALTER TABLE plan_items ADD COLUMN predicted_success_probability DOUBLE PRECISION`

**File**: `BayesianSuccessPredictor.java` (NEW), `SuccessPrediction.java` (NEW),
`OrchestrationService.java` (modifica), `PlanItem.java` (+campo), `PlanRequest.java` (+admissionControl)

**Sforzo**: 2g. **Dipendenze**: GP engine (#15), `task_outcomes` con >200 record.
**Impatto**: alto — admission control previene spreco di token su task destinati a fallire.
**Riferimento**: Gelman, A. et al. (2013), *Bayesian Data Analysis*, 3rd ed., CRC Press.

---

### #54 — Causal Inference per Root Cause Analysis (Pearl's Do-Calculus)

**Problema**: "Perche' il task X e' fallito?" — oggi l'unica risposta e' `failureReason` (testo libero
dal worker LLM). Non c'e' analisi causale strutturata. Non c'e' ragionamento controfattuale
("sarebbe riuscito con piu' budget?"). Le correlazioni ingannano: un profilo potrebbe avere ELO basso
non perche' e' debole, ma perche' riceve task piu' complessi (confondente).

**Soluzione**: costruire un DAG causale dei fattori di esecuzione e applicare il do-calculus di Pearl
per distinguere cause reali da confondenti.

**Design**:

```
DAG causale (domain knowledge + validazione statistica):

  context_quality ──────────────────────────────► task_success
  worker_experience (elo) ──► quality ──────────► task_success
  token_budget ──► duration ────────────────────► task_success
  task_complexity (embedding norm) ──► difficulty ► task_success

Intervento (do-calculus):
  P(success | do(budget=X)) vs P(success | budget=X)
  Backdoor adjustment: P(Y|do(X)) = sum_Z P(Y|X,Z) * P(Z)
```

- **PC Algorithm**: discovery delle archi causali da test di indipendenza condizionale
- `CausalDag.java` (NEW, `orchestrator/analytics/`): DAG causale (nodi + archi + confondenti)
- `RootCauseAnalyzer.java` (NEW): probabilita' interventionale per ogni potenziale causa
- `CausalAttribution.java` (NEW, record): `{factor, contribution, interventionalP, observationalP}`
- `PlanController.java`: `GET /api/v1/plans/{planId}/items/{taskKey}/root-cause`

**File**: `CausalDag.java` (NEW), `RootCauseAnalyzer.java` (NEW), `CausalAttribution.java` (NEW),
`PlanController.java` (+endpoint)

**Sforzo**: 2.5g. **Dipendenze**: `task_outcomes` con metadata ricchi.
**Impatto**: alto — trasforma il debugging da aneddotico a quantitativo.
**Riferimento**: Pearl, J. (2009), *Causality: Models, Reasoning, and Inference*, 2nd ed., Cambridge University Press.

---

## Dominio B: Sistemi Complessi (#55-#57)

---

### #55 — Evolutionary Game Theory per Worker Dynamics (Replicator Dynamics)

**Problema**: la distribuzione dei profili worker e' statica (configurata in `WorkerProfileRegistry`,
41 profili in 7 tipi). Non c'e' modello di come i profili dovrebbero evolvere nel tempo. Un profilo
"be-rust" potrebbe essere dominante (reward piu' alto) ma sotto-rappresentato, o viceversa.
Non c'e' previsione di quali profili "sopravviveranno" a regime.

**Soluzione**: equazione del replicatore:
`dx_i/dt = x_i * (pi_i - <pi>)` dove `x_i` = proporzione del profilo i, `pi_i` = payoff medio,
`<pi>` = payoff medio della popolazione. Profili con payoff sopra la media crescono.

**Design**:

```
Strategia Evolutivamente Stabile (ESS):
  Un mix x* e' ESS se nessun "mutante" puo' invadere.
  Condizione: pi(x*, x*) > pi(y, x*) per ogni y != x*

Simulazione:
  1. Calcola x_i corrente da task_outcomes (proporzione dispatches per profilo)
  2. Calcola pi_i da worker_elo_stats (avgReward)
  3. Simula replicator dynamics per T=100 step, dt=0.1
  4. Trova equilibrio, confronta con distribuzione corrente
  5. D_ESS = sum_i |x_i_current - x_i_equilibrium| → se > 0.3 → raccomanda ri-bilanciamento
```

- `ReplicatorDynamicsService.java` (NEW, `orchestrator/analytics/`)
- `WorkerPopulationReport.java` (NEW, record): `{currentDistribution, equilibriumDistribution,
  essDistance, recommendations, populationTrajectory[]}`
- `AnalyticsController.java` (NEW): `GET /api/v1/analytics/worker-dynamics`

**File**: `ReplicatorDynamicsService.java` (NEW), `WorkerPopulationReport.java` (NEW),
`AnalyticsController.java` (NEW)

**Sforzo**: 1.5g. **Dipendenze**: dati ELO per profilo (gia' in `worker_elo_stats`).
**Impatto**: medio — guida il tuning della distribuzione profili, identifica profili inutilmente mantenuti.
**Riferimento**: Maynard Smith, J. (1982), *Evolution and the Theory of Games*, Cambridge University Press.

---

### #56 — Self-Organized Criticality per Failure Cascade (Sandpile Model)

**Problema**: non c'e' modello di come i fallimenti si propagano nel sistema. Un CONTEXT_MANAGER fallito
blocca tutti i worker domain dipendenti. Un auto-retry puo' amplificare il fallimento (storm di retry).
Non c'e' early warning di fallimento sistemico — il sistema reagisce solo dopo che i task sono gia' falliti.

**Soluzione**: modello sandpile di Bak-Tang-Wiesenfeld. Ogni nodo (worker pool) ha un "carico" che aumenta
con task pendenti/falliti. Quando il carico supera una soglia, "topple" (cascata) ai vicini.
La distribuzione power-law delle dimensioni delle cascate emerge naturalmente.

**Design**:

```
Modello:
  load[workerType] = pending_count + failed_count * 2 + dispatched_stale_count * 1.5
  threshold[workerType] = target_inventory * 3

Topple event:
  Quando load[wt] > threshold[wt]:
    load[wt] -= threshold[wt]
    Per ogni wt_neighbor: load[wt_neighbor] += spillover (0.3 * threshold[wt] / num_neighbors)

Criticality index:
  C = max_wt(load[wt] / threshold[wt])
  C < 0.5: stabile, 0.5-0.8: warning, >= 0.8: alert
```

- `CriticalityMonitor.java` (NEW, `orchestrator/orchestration/`): traccia carico, check ogni 30s
- `SandpileSimulator.java` (NEW): simula distribuzione dimensioni cascate
- `PlanEvent.java`: `+SYSTEM_CRITICALITY` tipo evento

**File**: `CriticalityMonitor.java` (NEW), `SandpileSimulator.java` (NEW), `PlanEvent.java` (+tipo)

**Sforzo**: 1.5g. **Dipendenze**: nessuna (osservazionale).
**Impatto**: alto — early warning di fallimento sistemico. Previene cascate distruttive.
**Riferimento**: Bak, P., Tang, C. & Wiesenfeld, K. (1987), "Self-organized criticality",
*Physical Review Letters*, 59(4), 381-384.

---

### #57 — Swarm Intelligence per Workflow Discovery (ACO Pheromone)

**Problema**: le sequenze di task sono determinate dal planner LLM. Non c'e' apprendimento dalle sequenze
storiche di successo. Lo stesso pattern (es. CM → SM → HM → BE → REVIEW) viene "riscoperto" ogni volta.

**Soluzione**: modello di feromone Ant Colony Optimization. I "sentieri" sono sequenze di worker type.
Il feromone viene depositato proporzionalmente al reward del piano. L'evaporazione decay le sequenze vecchie.
I nuovi piani seguono probabilisticamente i sentieri ad alto feromone.

**Design**:

```
Matrice di feromone tau[workerType_from][workerType_to]: 12x12
Inizializzazione: tau_0 = 1.0

Deposito (al completamento piano):
  delta_tau[dep.wt][item.wt] += plan_reward / num_edges

Evaporazione (ogni ora):
  tau(t+1) = (1 - rho) * tau(t), rho = 0.1

Probabilita' transizione:
  P(next = wt_j | current = wt_i) = tau[i][j]^alpha / sum_k(tau[i][k]^alpha)
```

- `PheromoneService.java` (NEW, `orchestrator/orchestration/`): gestisce matrice, deposit, suggest
- `PheromoneMatrix.java` (NEW): `double[][]` + Redis DB 3
- `PlannerService.java` (modifica): include "suggested workflow patterns" nel prompt del planner

**File**: `PheromoneService.java` (NEW), `PheromoneMatrix.java` (NEW), `PlannerService.java` (modifica)

**Sforzo**: 2g. **Dipendenze**: piani completati con reward data.
**Impatto**: medio-alto — il sistema impara dai propri successi. Riduce carico sul planner LLM.
**Riferimento**: Dorigo, M. & Stutzle, T. (2004), *Ant Colony Optimization*, MIT Press.

---

## Dominio C: Matematica Avanzata (#58-#61)

---

### #58 — Spectral Graph Theory per DAG Decomposition (Laplacian Eigenvalues)

**Problema**: l'analisi del DAG e' limitata al topological sort e alla risoluzione delle dipendenze.
Non c'e' insight sulla struttura del grafo: quanto e' robusto? Dove sono i bottleneck?
Come partizionare il DAG in sub-piani indipendenti per esecuzione parallela?

**Soluzione**: calcolare la matrice Laplaciana `L = D - A` del DAG e analizzare lo spettro degli autovalori.

**Design**:

```
Matrice Laplaciana L (simmetrica):
  L[i][i] = degree(node_i)
  L[i][j] = -1 se arco (i,j) esiste, 0 altrimenti

Autovalori lambda_0 <= lambda_1 <= ... <= lambda_n:
  lambda_1 = Fiedler value (connettivita' algebrica)
    - lambda_1 alto → grafo robusto
    - lambda_1 basso → grafo fragile (singolo bottleneck)
  Vettore di Fiedler: partiziona il grafo in due cluster

Spectral gap = lambda_1 / lambda_max:
  - Alto → grafo "connesso" (molte dipendenze cross-task)
  - Basso → grafo "modulare" (sub-piani indipendenti)
```

- `SpectralAnalyzer.java` (NEW, `orchestrator/graph/`): spettro Laplaciana, Fiedler, partizione
- `SpectralMetrics.java` (NEW, record): `{fiedlerValue, spectralGap, partition, bottlenecks, eigenvalues}`
- `PlanGraphService.java` (modifica): `extractAdjacencyMatrix(Plan) -> double[][]`
- `PlanController.java`: `GET /api/v1/plans/{id}/spectral`
- Dipendenza: EJML (`org.ejml:ejml-simple:0.43`, ~300KB)

**File**: `SpectralAnalyzer.java` (NEW), `SpectralMetrics.java` (NEW), `PlanGraphService.java` (modifica),
`PlanController.java` (+endpoint), `pom.xml` (+ejml-simple)

**Sforzo**: 2g. **Dipendenze**: libreria EJML.
**Impatto**: alto — identifica bottleneck e suggerisce partizionamento per sub-piani paralleli (#9).
**Riferimento**: Fiedler, M. (1973), "Algebraic connectivity of graphs",
*Czech. Math. Journal*, 23(2), 298-305. Spielman, D. (2012), "Spectral Graph Theory", Yale.

---

### #59 — Tropical Geometry per Critical Path Scheduling (Min-Plus Algebra)

**Problema**: il calcolo del percorso critico e' ad-hoc (`CriticalPathCalculator` usa topological sort
+ longest path). Non c'e' algebra formale per ragionare sull'ottimizzazione dello schedule.
I tempi di inizio sono calcolati greedily senza prova di ottimalita'.

**Soluzione**: semianello tropicale `(R ∪ {+∞}, min, +)` dove "addizione" e' min e "moltiplicazione" e' +.
La matrice del DAG nel semianello tropicale codifica i cammini minimi/massimi algebricamente.

**Design**:

```
Semianello tropicale:
  a ⊕ b = min(a, b)           // "somma" tropicale
  a ⊗ b = a + b               // "prodotto" tropicale
  0_tropicale = +∞             // elemento neutro addizione
  1_tropicale = 0              // elemento neutro moltiplicazione

Earliest start time:
  est[j] = min_i(est[i] + duration[i])   ← formula tropicale

Chiusura di Kleene A* = I ⊕ A ⊕ A^2 ⊕ ... ⊕ A^(n-1) → percorso critico
```

- `TropicalSemiring.java` (NEW, `orchestrator/graph/`): operazioni (~100 righe, autocontenute)
- `TropicalScheduler.java` (NEW): earliest/latest start, slack, percorso critico
- `CriticalPathCalculator.java` (refactor): thin wrapper su `TropicalScheduler` per backward compatibility
- `PlanGraphService.java` (modifica): `computeSchedule(Plan) -> ScheduleView`
- Nessuna dipendenza esterna

**File**: `TropicalSemiring.java` (NEW), `TropicalScheduler.java` (NEW),
`CriticalPathCalculator.java` (refactor), `PlanGraphService.java` (modifica)

**Sforzo**: 1.5g. **Dipendenze**: nessuna (algebra autocontenuta).
**Impatto**: medio-alto — formalizzazione del critical path con garanzia di ottimalita'.
**Riferimento**: Speyer, D. & Sturmfels, B. (2009), "Tropical Mathematics",
*Mathematics Magazine*, 82(3), 163-173. Butkovic, P. (2010), *Max-linear Systems*, Springer.

---

### #60 — Optimal Transport per Distribution Alignment (Wasserstein Distance)

**Problema**: il confronto tra worker usa stime puntuali (ELO, GP mu). Non c'e' confronto delle
*distribuzioni* di output. Due worker con lo stesso reward medio ma varianza diversa sono trattati
identicamente. Non c'e' rilevamento di drift nelle performance dei modelli LLM.

**Soluzione**: distanza di Wasserstein (earth mover's distance) tra le distribuzioni di reward dei worker.
`W_1` misura il "costo di spostare massa" dalla distribuzione P alla distribuzione Q.

**Design**:

```
Casi d'uso:
  1. Worker substitution safety: W_1(P_A, P_B) piccolo → sicuro sostituire
  2. Version drift detection: W_1(P_week1, P_week2) per stesso profilo → alert se drift
  3. Capability matching: W_1(P_requirements, P_capability) → match score

Calcolo 1D:
  W_1(P, Q) = integral |F_P(x) - F_Q(x)| dx
  Implementazione: sort quantili, sum |p_i - q_i| / n
  Complessita': O(n log n)
```

- `WassersteinService.java` (NEW, `orchestrator/analytics/`): W_1 tra distribuzioni di reward
- `WorkerDriftMonitor.java` (NEW): scheduled check giornaliero
- `DriftResult.java` (NEW, record): `{profile, w1Distance, previousMean, currentMean, driftDetected}`
- `GpWorkerSelectionService.java` (modifica): penalizza profili con drift alto
- `PlanEvent.java`: `+WORKER_DRIFT_DETECTED`

**File**: `WassersteinService.java` (NEW), `WorkerDriftMonitor.java` (NEW), `DriftResult.java` (NEW),
`GpWorkerSelectionService.java` (modifica), `PlanEvent.java` (+tipo)

**Sforzo**: 2g. **Dipendenze**: `task_outcomes` con almeno 30 reward per profilo per finestra.
**Impatto**: alto — rileva degradazione performance modelli LLM (version drift).
**Riferimento**: Villani, C. (2009), *Optimal Transport: Old and New*, Springer.
Peyre, G. & Cuturi, M. (2019), "Computational Optimal Transport", *Foundations and Trends in ML*.

---

### #61 — Submodular Optimization per Agent Selection (Greedy with Guarantees)

**Problema**: la selezione dei membri del council usa regole fisse (4 manager + 4 specialist in
`CouncilService`). Non c'e' garanzia di copertura o diversita'. Aggiungere un membro che si sovrappone
con quelli esistenti spreca LLM call. Il `COUNCIL_EXECUTOR` ha max 8 thread.

**Soluzione**: modellare la copertura dei topic come funzione submodulare `f(S)` (rendimenti decrescenti):
`f(S ∪ {x}) - f(S) >= f(T ∪ {x}) - f(T)` quando `S ⊆ T`.
L'algoritmo greedy e' provabilmente `(1 - 1/e) ≈ 63%`-ottimale.

**Design**:

```
Funzione di copertura:
  f(S) = |topics_covered(S)| pesato per rilevanza alla spec del piano
  topics_covered(member) = set di topic estratti dal profilo dell'agent

Greedy con lazy evaluation (CELF):
  1. Calcola guadagno marginale per tutti i candidati
  2. Ordina per guadagno decrescente
  3. Seleziona il primo, aggiungi a S
  4. Ricalcola SOLO il prossimo candidato (lazy evaluation)
  Speedup: tipicamente 5-10x rispetto a greedy naive

Applicazione duale:
  A. Council member selection: f(S) = copertura topic spec
  B. Context file selection (CONTEXT_MANAGER): f(S) = copertura codice sorgente
```

- `SubmodularSelector.java` (NEW, `orchestrator/council/`): greedy + CELF
- `CoverageFunction.java` (NEW, interfaccia): `marginalGain(Set, Candidate)`
- `TopicCoverageFunction.java` (NEW): per council member selection
- `CouncilService.java` (modifica): usa `SubmodularSelector` con feature flag
- `CouncilProperties.java`: `+submodularSelectionEnabled`, `+maxMembers`

**File**: `SubmodularSelector.java` (NEW), `CoverageFunction.java` (NEW), `TopicCoverageFunction.java` (NEW),
`CouncilService.java` (modifica), `CouncilProperties.java` (modifica)

**Sforzo**: 2g. **Dipendenze**: `EmbeddingModel` (gia' presente in `TaskOutcomeService`).
**Impatto**: alto — diversifica il council, elimina ridondanza, estensibile alla selezione contesto.
**Riferimento**: Nemhauser, G., Wolsey, L. & Fisher, M. (1978), "Submodular set functions",
*Mathematical Programming*, 14(1), 265-294. Krause, A. & Golovin, D. (2014), "Submodular Function
Maximization", Cambridge University Press.

---

## Riepilogo items Research Domains (#50-#61)

### Grafo di dipendenze

```
INTERNE (#50-#61):
  #50 Portfolio ~~~~~~~~~~~~► #53 Bayesian (covarianza come input)
  #51 Market Making ────────► #36 Queueing Theory (throughput)
  #51 Market Making ~~~~~~~~► #56 Criticality (inventory → load)
  #54 Causal ~~~~~~~~~~~~~~~► #53 (probabilita' come input)
  #59 Tropical ─────────────► #36/#42 (CriticalPathCalculator refactor)
  #60 Wasserstein ~~~~~~~~~~► #55 Replicator (distribuzione profili)

ESTERNE:
  GP Engine (#15) ──────────► #50, #52, #53
  #36 Queueing Theory ──────► #51
  #36/#42 CriticalPath ─────► #51, #59
  EmbeddingModel ───────────► #61

Legenda: ──► dipendenza, ~~~► sinergia (non bloccante)
```

### Tier di implementazione

```
TIER 0 — Nessuna dipendenza interna (~10g)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  #52  Worker Greeks (B-S)               1.5g
  #55  Replicator Dynamics               1.5g
  #56  Criticality Monitor (Sandpile)    1.5g  ✅
  #58  Spectral DAG Decomposition        2.0g
  #59  Tropical Critical Path            1.5g
  #61  Submodular Council Selection      2.0g

TIER 1 — Dipende da Tier 0 o esterne (~8g)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  #50  Portfolio Theory                  2.0g
  #51  Market Making                     2.0g
  #53  Bayesian Success Prediction       2.0g
  #57  Swarm Intelligence (ACO)          2.0g

TIER 2 — Dipende da Tier 1 (~4.5g)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  #54  Causal Inference                  2.5g
  #60  Wasserstein Distance              2.0g
```

### Tabella completa

| Tier | # | Dominio | Titolo | Sforzo | Dip. esterne | Valore |
|------|---|---------|--------|--------|-------------|--------|
| 0 | **52** | Finanza | Worker Greeks (B-S) | 1.5g | GP engine | Medio-Alto |
| 0 | **55** | Complessi | Replicator Dynamics | 1.5g | ELO data | Medio |
| 0 | **56** | Complessi | Criticality Monitor | 1.5g | — | Alto |
| 0 | **58** | Matematica | Spectral DAG | 2.0g | EJML lib | Alto |
| 0 | **59** | Matematica | Tropical Scheduler | 1.5g | — | Medio-Alto |
| 0 | **61** | Matematica | Submodular Selection | 2.0g | EmbeddingModel | Alto |
| 1 | **50** | Finanza | Portfolio Theory | 2.0g | GP engine (#15) | Alto |
| 1 | **51** | Finanza | Market Making | 2.0g | #36 Queueing | Alto |
| 1 | **53** | Finanza | Bayesian Predictor | 2.0g | GP engine (#15) | Alto |
| 1 | **57** | Complessi | Swarm Intelligence | 2.0g | Reward data | Medio-Alto |
| 2 | **54** | Finanza | Causal Inference | 2.5g | task_outcomes | Alto |
| 2 | **60** | Matematica | Wasserstein Distance | 2.0g | task_outcomes | Alto |
| | | | **Totale Fase 8** | **22.5g** | | |

### Note sulle dipendenze critiche

1. **GP Engine (#15) prerequisito per #50, #52, #53**: leggono `task_outcomes` e usano predizioni GP.
2. **#36 (Queueing Theory) sblocca #51**: stime throughput per target_inventory.
3. **#59 (Tropical) refactora #36/#42**: `CriticalPathCalculator` riscritto su algebra tropicale, backward compat.
4. **#56 (Criticality) indipendente**: piu' alto rapporto valore/sforzo, partenza immediata.
5. **#61 e #49 complementari**: #61 seleziona *chi* nel council, #49 pesa *quanto*.
6. **Flyway**: solo V15 per #53. Gli altri operano su dati esistenti o in-memory/Redis.

### Ordine consigliato di implementazione

```
Fase 8a (fondazioni, ~6.5g):   #56 ✅ → #59 → #55 → #52
Fase 8b (strutturale, ~6.0g):  #58 → #61 → #57
Fase 8c (economico, ~6.0g):    #50 → #51 → #53
Fase 8d (avanzato, ~4.5g):     #54 → #60
                                ─────────────────────
                                Totale: ~22.5g (#50-#61)
```

### Codice condiviso tra items

| Classe | Usata da | Modulo |
|--------|----------|--------|
| `CovarianceMatrix.java` | #50 | orchestrator/budget |
| `CriticalPathCalculator.java` (refactored) | #36, #42, #51, #59 | orchestrator/graph |
| `TropicalSemiring.java` | #59 | orchestrator/graph |
| `TaskOutcomeService.java` (existing) | #50, #52, #53, #54, #55, #60 | orchestrator/gp |
| `WorkerEloStats.java` (existing) | #55 | orchestrator/reward |
| `PlanGraphService.java` (existing) | #58, #59 | orchestrator/graph |
| `CouncilService.java` (existing) | #61 | orchestrator/council |
| `GpWorkerSelectionService.java` (existing) | #50, #52, #53, #60 | orchestrator/gp |
| `AnalyticsController.java` (NEW) | #55, #60 | orchestrator/api |

**Totale Research Domains Fase 8**: ~22.5 giorni di lavoro.

Documentazione completa: [`docs/agent-framework/research-domains.md`](../docs/agent-framework/research-domains.md)

---

## Research Domains Extended — Fase 9 (#62-#76)

15 nuovi concetti avanzati. Documentazione completa: [`docs/agent-framework/research-domains-new.md`](../docs/agent-framework/research-domains-new.md)

### Tabella items Fase 9

| Tier | # | Dominio | Titolo | Sforzo | Valore |
|------|---|---------|--------|--------|--------|
| 0 | **64** | Behavioral | Prospect Theory (Kahneman-Tversky) | 1.5g | Alto |
| 0 | **65** | Online Learning | Hedge Algorithm (regret bounds) | 1.5g | Alto |
| 0 | **69** | Finance | Kelly Criterion (budget sizing) | 1.5g | Medio-Alto |
| 0 | **71** | Decision | Optimal Stopping (Secretary Problem) | 2.0g | Medio-Alto |
| 0 | **75** | Rationality | Calibration Audit (Dutch Book) | 1.5g | Alto |
| 1 | **62** | Game Theory | VCG Mechanism Design (task pricing) | 2.5g | Alto |
| 1 | **63** | Control | Model Predictive Control (scheduling) | 3.0g | Alto |
| 1 | **67** | Game Theory | Shapley Value (credit attribution) | 2.5g | Alto |
| 1 | **68** | Information | Fisher Information Metric (uncertainty) | 3.0g | Medio-Alto |
| 1 | **73** | Rationality | Value of Information (exploration) | 2.0g | Alto |
| 1 | **74** | Rationality | Goodhart's Law Mitigation (metric safety) | 2.0g | Alto |
| 1 | **76** | Rationality | Superrationality (multi-worker cooperation) | 2.5g | Medio-Alto |
| 2 | **66** | Finance | Real Options Theory (task deferral) | 2.0g | Medio-Alto |
| 2 | **70** | Contract | Contract Theory (SLA worker) | 2.5g | Medio |
| 2 | **72** | Decision | TDT/FDT Reflective Dispatch | 3.0g | Alto |
| | | | **Totale Fase 9** | **33.0g** | |

### Ordine implementazione Fase 9

```
Fase 9a (fondazioni, ~8.0g):      #64 → #65 → #69 → #71 → #75
Fase 9b (game theory, ~5.0g):     #62 → #67
Fase 9c (controllo+info, ~10.5g): #63 → #68 → #73 → #74
Fase 9d (avanzato, ~5.0g):        #66 → #70
Fase 9e (agent found., ~5.5g):    #76 → #72
                                   ─────────────────────
                                   Totale: ~33.0g (#62-#76)
```

### Dipendenze critiche Fase 9

- **GP Engine (#15)** prerequisito per 9 items su 15
- **#72 TDT e' la "meta-teoria"**: principio unificante per #63 (MPC), #73 (VoI), #76 (Superrationality)
- **#74 Goodhart + #75 Calibration**: coppia difensiva per robustezza metriche
- Nessuna Flyway migration — tutti in-memory o Redis DB 4

### Riepilogo sforzo complessivo Research Domains

```
Fase 8  (#50-#61):  12 items, ~22.5g  [research-domains.md]
Fase 9  (#62-#76):  15 items, ~33.0g  [research-domains-new.md]
Fase 10 (#77-#86):  10 items, ~24.0g  [research-domains-new.md]
Fase 11 (#87-#96):  10 items, ~22.5g  [research-domains-new.md]
Fase 12 (#97-#106): 10 items, ~21.5g  [research-domains-new.md]
───────────────────────────────────────
Totale:             57 items, ~123.5g

Consolidamento: research-domains-consolidation.md
  (matrice 57×21, Mermaid, coverage, priority ranking, theory clusters)
```

---

## Research Domains Extended — Fase 10 (#77-#86)

10 nuovi concetti da neuroscienze computazionali, teoria dell'informazione, fisica statistica, controllo robusto, distributed computing, complexity science, topologia e teoria delle categorie. Documentazione completa: [`docs/agent-framework/research-domains-new.md`](../docs/agent-framework/research-domains-new.md)

### Tabella items Fase 10

| Tier | # | Dominio | Titolo | Sforzo | Valore |
|------|---|---------|--------|--------|--------|
| 0 | **79** | Info Theory | MDL / Kolmogorov Complexity (plan quality) | 2.0g | Alto |
| 0 | **82** | Control | H-infinity Robust Control (worst-case dispatch) | 2.0g | Alto |
| 0 | **84** | Complexity | Edge of Chaos (exploration-exploitation auto-tune) | 2.0g | Alto |
| 1 | **77** | Neuroscience | Active Inference (Free Energy Principle) | 3.0g | Alto |
| 1 | **78** | Info Theory | Information Bottleneck (embedding compression) | 2.5g | Medio-Alto |
| 1 | **81** | Stat. Physics | Spin Glass & Simulated Annealing (dispatch ordering) | 2.0g | Medio-Alto |
| 1 | **83** | Distributed | Byzantine Fault Tolerance (worker reliability) | 2.5g | Alto |
| 2 | **80** | Stat. Physics | Renormalization Group (multi-scale decomposition) | 2.5g | Medio |
| 2 | **85** | Topology | Persistent Homology (embedding landscape) | 3.0g | Medio-Alto |
| 2 | **86** | Category Th. | Functorial Plan Semantics (compositionality) | 2.5g | Medio |
| | | | **Totale Fase 10** | **24.0g** | |

### Ordine implementazione Fase 10

```
Fase 10a (fondazioni, ~6.0g):       #79 → #82 → #84
Fase 10b (core, ~10.0g):            #77 → #78 → #81 → #83
Fase 10c (avanzato, ~8.0g):         #80 → #85 → #86
                                     ─────────────────────
                                     Totale: ~24.0g (#77-#86)
```

### Dipendenze critiche Fase 10

- **GP Engine (#15)** prerequisito per #77, #78, #82, #84 (tutti interagiscono col GP posterior o UCB)
- **#77 Active Inference è il nucleo**: unifica #78 (IB come caso speciale di free energy), influenza #84 (criticality come regime ottimale di free energy)
- **#79 MDL + #80 RG**: coppia complementare — MDL misura semplicità, RG opera trasformazioni multi-scala
- **#83 BFT + #74 Goodhart**: difesa congiunta — BFT contro worker inaffidabili, Goodhart contro metriche corrotte
- **#85 Persistent Homology**: richiede pgvector embeddings (TaskOutcomeService) + #78 IB (topologia dello spazio compresso)
- **#86 Functorial Semantics**: richiede PlannerService DAG + #80 RG (composizione multi-scala)
- Nessuna Flyway migration — tutti in-memory o Redis DB 4

---

## Research Domains Extended — Fase 11 (#87-#96)

10 nuovi concetti da formal methods, social choice theory, cybernetics, learning theory, compressed sensing, ergodic economics e queuing theory. Strategia: colmare le lacune di copertura sui componenti sotto-serviti (CouncilService, Redis Streams, RAG Pipeline). Documentazione completa: [`docs/agent-framework/research-domains-new.md`](../docs/agent-framework/research-domains-new.md)

### Tabella items Fase 11

| Tier | # | Dominio | Titolo | Sforzo | Valore |
|------|---|---------|--------|--------|--------|
| 0 | **87** | Formal Methods | Petri Nets (plan concurrency analysis) | 2.5g | Alto |
| 0 | **89** | Learning Theory | PAC-Bayes (GP convergence bounds) | 2.0g | Alto |
| 0 | **90** | Social Choice | Social Choice Theory (council aggregation) | 2.5g | Alto |
| 0 | **95** | Economics | Ergodic Economics (time-average budgets) | 2.0g | Medio-Alto |
| 0 | **96** | Queuing Theory | M/G/1 (stream capacity planning) | 2.0g | Alto |
| 1 | **88** | Formal Methods | CSP (worker communication protocol) | 2.5g | Alto |
| 1 | **91** | Social Systems | Diversity Prediction (council composition) | 2.0g | Alto |
| 1 | **93** | Learning Theory | Thompson Sampling (Bayesian exploration) | 2.0g | Alto |
| 1 | **94** | Compressed S. | Compressed Sensing (sparse RAG retrieval) | 2.5g | Alto |
| 2 | **92** | Cybernetics | VSM (viable system model, agent hierarchy) | 2.5g | Medio-Alto |
| | | | **Totale Fase 11** | **22.5g** | |

### Ordine implementazione Fase 11

```
Fase 11a (formal+voting, ~9.5g):   #87 → #90 → #89 → #88
Fase 11b (council+econ, ~6.5g):    #91 → #95 → #96
Fase 11c (avanzato, ~6.5g):        #93 → #94 → #92
                                     ─────────────────────
                                     Totale: ~22.5g (#87-#96)
```

### Dipendenze critiche Fase 11

- **CouncilService** target di 3 items: #90 (voting protocol), #91 (diversity), #92 (VSM S4) — colma la lacuna principale
- **Redis Streams** target di 2 items: #88 (CSP protocol verification), #96 (queuing capacity) — formalizza il messaging
- **RAG HybridSearchService** target di #94 (compressed sensing) — primo item teorico per il retrieval pipeline
- **#87 Petri Nets + #88 CSP complementari**: stato (places/tokens) vs processo (events/channels) — insieme coprono concorrenza statica e dinamica
- **#93 Thompson + #89 PAC-Bayes**: Thompson esplora campionando dalla GP posterior, PAC-Bayes bounds quando smettere di esplorare
- **#95 Ergodic + #69 Kelly**: Kelly e' caso speciale ergoico — Ergodic Economics fornisce la giustificazione profonda
- **#92 VSM meta-architettura**: mappa l'intero framework sui 5 sottosistemi di Beer (S1=Worker, S2=Redis, S3=Orchestration, S4=Council, S5=Human)
- Nessuna Flyway migration — tutti in-memory, Redis, o metriche runtime

---

## Research Domains Extended — Fase 12 (#97-#106)

10 nuovi concetti che colmano le ultime lacune di copertura sui componenti non mappati (SerendipityService, PlanEventStore, RewardComputationService, AbstractWorker, PlanSnapshotService, RalphLoopService, WorkerCapabilitySpec, RAG Services, HookManagerService, PlanGraphService). Documentazione completa: [`docs/agent-framework/research-domains-new.md`](../docs/agent-framework/research-domains-new.md). Consolidamento: [`docs/agent-framework/research-domains-consolidation.md`](../docs/agent-framework/research-domains-consolidation.md).

### Tabella items Fase 12

| Tier | # | Dominio | Titolo | Sforzo | Valore |
|------|---|---------|--------|--------|--------|
| 0 | **97** | Bayesian | Bayesian Surprise (serendipity detection) | 2.0g | Alto |
| 0 | **99** | RL Theory | Potential-Based Reward Shaping | 2.0g | Alto |
| 0 | **100** | Concurrency | Actor Model (worker isolation) | 2.5g | Alto |
| 0 | **101** | Distributed | Chandy-Lamport Snapshots (plan checkpointing) | 2.0g | Alto |
| 0 | **102** | Analysis | Fixed-Point Theory (iterative convergence) | 2.0g | Alto |
| 0 | **105** | Logic | LTL (hook policies, temporal verification) | 2.0g | Alto |
| 1 | **98** | Process | Process Mining (workflow discovery) | 2.5g | Alto |
| 1 | **103** | KR | Description Logic ALC (capability matching) | 2.0g | Medio-Alto |
| 1 | **104** | HCI/IR | Information Foraging (RAG optimization) | 2.5g | Alto |
| 1 | **106** | Swarm | Stigmergy (coordinamento indiretto) | 2.0g | Medio-Alto |
| | | | **Totale Fase 12** | **21.5g** | |

### Ordine implementazione Fase 12

```
Fase 12a (core, ~12.0g):          #97 → #99 → #100 → #101 → #102 → #105
Fase 12b (avanzato, ~9.5g):       #98 → #103 → #104 → #106
                                    ─────────────────────
                                    Totale: ~21.5g (#97-#106)
```

### Dipendenze critiche Fase 12

- **#98 Process Mining → #87 Petri Nets**: il workflow net scoperto dal process mining E' un Petri net
- **#100 Actor Model → #88 CSP**: complementari — Actor per isolamento, CSP per sincronizzazione
- **#104 Info Foraging → #94 Compressed Sensing**: entrambi ottimizzano retrieval RAG
- **#106 Stigmergy → #92 VSM**: meccanismo di coordinamento S2 nel VSM
- **GP Engine (#15)** prerequisito per #97, #99, #102 (prior/posterior, reward, convergenza)
- **OrchestrationService** target di #100, #101, #105, #106 (supervision, snapshot, policy, coordinamento)
- Nessuna Flyway migration — tutti in-memory, Redis, o metriche runtime

### Arricchimenti inclusi (Fasi 9-12)

Oltre ai 57 items, `research-domains-new.md` contiene:
- **10 pseudocodice** (items algoritmici: #62, #63, #67, #77, #81, #87, #89, #93, #94, #96)
- **10 esempi numerici** (con dati concreti dal framework: #62, #67, #69, #77, #81, #87, #93, #94, #95, #96)
- **15 implementation sketches** (Java signatures + data flow: #62, #64, #65, #73, #75, #77, #81, #87, #93, #96, #97, #99, #100, #102, #105)
- **27 cross-reference "Vedi anche"** (bidirezionali tra items correlati)

---

# Execution Sandbox (#44)

---

## #44 — Execution Sandbox Containerizzato (Framework + Worker Isolation)

**Problema**: i worker domain (BE, FE) generano codice ma non possono compilarlo ne' testarlo.
`mcp-bash-tool` (#25) permetterebbe l'esecuzione shell, ma sul filesystem del server SOL —
conflitti di librerie, versioni incompatibili, rischio sicurezza (un worker malintenzionato o un bug
potrebbe eseguire `rm -rf /data`). Servono:
1. Isolamento del toolchain: ogni worker type ha il proprio ambiente (JDK 21, GnuCOBOL, Go, etc.)
2. Isolamento del processo: nessun accesso al filesystem del server
3. Isolamento della rete: il codice compilato non puo' fare chiamate esterne
4. Resource limits: CPU, memoria, timeout per evitare DoS
5. Containerizzazione del framework stesso: l'orchestratore e i worker girano in container

**Soluzione**: Approccio C (dual-layer) — il framework gira in container Docker (orchestratore + N worker JVM),
e quando un worker deve compilare/testare, spawna un sandbox container effimero via Docker socket.

**Design a 2 livelli**:

**Livello 1 — Framework containerizzato (Docker Compose)**

```yaml
# /data/massimiliano/agent-framework/docker-compose.yml
services:
  orchestrator:
    build: .
    profiles: [orchestrator]
    environment:
      SPRING_PROFILES_ACTIVE: orchestrator
      WORKER_RUNTIMES: >
        {"AI_TASK":"http://worker-ai:8100",
         "REVIEW":"http://worker-review:8101"}
    mem_limit: 256m
    networks: [agent-net, shared]
    depends_on: [redis, postgres]

  worker-ai:
    build: .
    profiles: [worker]
    environment:
      SPRING_PROFILES_ACTIVE: worker
      WORKER_TYPE: AI_TASK
      SERVER_PORT: 8100
      DOCKER_HOST: unix:///var/run/docker.sock   # per spawning sandbox
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro  # Docker-in-Docker (socket)
      - workspace:/workspace                          # volume condiviso col sandbox
    mem_limit: 1g
    networks: [agent-net]

  worker-review:
    build: .
    profiles: [worker]
    environment:
      SPRING_PROFILES_ACTIVE: worker
      WORKER_TYPE: REVIEW
      SERVER_PORT: 8101
    mem_limit: 512m
    networks: [agent-net]

  # Manager leggeri: in-process nell'orchestratore (nessun container separato)
  # HOOK_MANAGER, CONTEXT_MANAGER, TASK_MANAGER → thread nell'orchestratore JVM

networks:
  agent-net:
    internal: true        # isolata, nessun accesso esterno
  shared:
    external: true        # per accedere a redis, postgres, proxy-ai
```

Nota: stessa immagine Docker per orchestratore e worker. Il profilo Spring (`SPRING_PROFILES_ACTIVE`)
determina il comportamento. `@Profile("orchestrator")` attiva `OrchestrationService`,
`@Profile("worker")` attiva `WorkerRuntime`.

**Livello 2 — Execution Sandbox (container effimeri per compilazione/test)**

Ogni worker spawna un container Docker effimero per compilare/testare il codice generato.
Il container e' distrutto dopo l'esecuzione.

```java
public class SandboxExecutor {
    private final DockerClient docker;  // com.github.docker-java (o ProcessBuilder → docker CLI)

    public SandboxResult execute(SandboxRequest request) {
        // 1. Scrivi il codice generato nel workspace volume
        writeToWorkspace(request.files(), request.workspacePath());

        // 2. Costruisci il comando docker run
        String containerId = docker.createContainer(
            ContainerConfig.builder()
                .image(request.sandboxImage())              // es. "agent-sandbox-java:21"
                .cmd(request.command())                      // es. ["mvn", "test", "-f", "/code/pom.xml"]
                .networkDisabled(true)                       // --network none
                .readonlyRootfs(true)                        // filesystem immutabile
                .memory(request.memoryLimit())                // 512 MB default
                .cpuQuota(100_000)                            // 1 CPU
                .user("1000:1000")                            // non-root
                .binds(List.of(
                    request.workspacePath() + ":/code:ro",   // codice sorgente read-only
                    request.outputPath() + ":/out:rw"        // output read-write
                ))
                .build()
        ).getId();

        // 3. Start + wait (con timeout)
        docker.startContainer(containerId);
        WaitResponse wait = docker.waitContainer(containerId, request.timeoutSeconds());

        // 4. Leggi output
        String stdout = docker.logs(containerId, STDOUT);
        String stderr = docker.logs(containerId, STDERR);
        int exitCode = wait.statusCode();

        // 5. Cleanup
        docker.removeContainer(containerId, true);  // force remove

        return new SandboxResult(exitCode, stdout, stderr, readOutputFiles(request.outputPath()));
    }
}
```

**Sandbox images pre-built**:

| Image | Base | Toolchain | Size |
|-------|------|-----------|------|
| `agent-sandbox-java:21` | eclipse-temurin:21-jdk-alpine | Maven 3.9, Gradle 8 | ~350 MB |
| `agent-sandbox-cobol` | alpine:3.21 | GnuCOBOL 3.2, gcc, copybook parser | ~150 MB |
| `agent-sandbox-go:1.22` | golang:1.22-alpine | go, golangci-lint | ~300 MB |
| `agent-sandbox-python:3.12` | python:3.12-slim | pip, pytest, ruff | ~200 MB |
| `agent-sandbox-node:22` | node:22-alpine | npm, pnpm, vitest | ~200 MB |
| `agent-sandbox-rust` | rust:1-alpine | cargo, clippy | ~500 MB |
| `agent-sandbox-cpp` | gcc:14 | gcc, cmake, make, valgrind | ~300 MB |
| `agent-sandbox-dotnet` | mcr.microsoft.com/dotnet/sdk:8.0-alpine | dotnet CLI | ~400 MB |

Le immagini sono pre-built e cached sul server. Al primo task di un tipo, il pull puo' richiedere tempo.
Dopo il primo pull, il container parte in ~1s (nessun warmup JVM, solo exec del compilatore).

**Integrazione con worker-sdk**:

- `SandboxExecutor.java` (NEW, in worker-sdk): spawna container, gestisce timeout/cleanup
- `SandboxRequest.java` (NEW): image, command, files, memory, timeout, networkDisabled
- `SandboxResult.java` (NEW): exitCode, stdout, stderr, outputFiles
- `mcp-bash-tool` (#25): delegato a `SandboxExecutor` invece di `ProcessBuilder` diretto
  - `bash_execute` → `SandboxExecutor.execute()` con l'immagine appropriata per il worker type
  - Totale isolamento: il "bash" del worker e' in realta' un container effimero
- `AbstractWorker`: nuovo metodo `executeSandbox(SandboxRequest)` accessibile a tutti i worker

**Sicurezza** (difesa in profondita'):

| Livello | Meccanismo | Cosa blocca |
|---------|-----------|-------------|
| 1 | `--network none` | Nessun accesso internet o rete Docker |
| 2 | `--read-only` + tmpfs selettivi | Filesystem immutabile (tranne /tmp e /out) |
| 3 | `--user 1000:1000` | Non-root, nessun capability Linux |
| 4 | `--memory 512m --cpus 1` | DoS prevention |
| 5 | Timeout (default 120s, max 600s) | Fork bomb, loop infiniti |
| 6 | Volume codice `:ro` | Il sandbox non puo' modificare il sorgente |
| 7 | No Docker socket nel sandbox | Il sandbox non puo' spaware altri container |
| 8 | seccomp profile (opzionale) | Blocca syscall pericolose (reboot, mount, etc.) |

**COBOL-specifico** (`agent-sandbox-cobol`):

```dockerfile
FROM alpine:3.21
RUN apk add --no-cache gnucobol gcc musl-dev make
# Copybook directory standard
RUN mkdir -p /copylib
WORKDIR /code
# Default: compila tutti i .cob, esegui test
ENTRYPOINT ["sh", "-c"]
CMD ["cobc -x -o /out/program *.cob && /out/program"]
```

- Copybook montati da volume condiviso: `-v /copylib:/copylib:ro`
- JCL runner simulato: parser minimale che traduce JCL → sequenza di comandi shell
- Supporto VSAM: file sequenziali simulati con file flat su /out
- DB2 simulato: SQLite come stand-in per query SQL embedded (preprocessore EXEC SQL)

**File da creare**:

| File | Modulo | Descrizione |
|------|--------|-------------|
| `SandboxExecutor.java` | worker-sdk | Spawna container Docker effimeri |
| `SandboxRequest.java` | agent-common | Record: image, command, files, limits |
| `SandboxResult.java` | agent-common | Record: exitCode, stdout, stderr, outputFiles |
| `SandboxProperties.java` | worker-sdk | Config: default image per workerType, limiti, timeout |
| `docker-compose.yml` | root | Framework containerizzato (orchestratore + N worker) |
| `Dockerfile.sandbox-java` | sandbox/ | Immagine sandbox Java 21 |
| `Dockerfile.sandbox-cobol` | sandbox/ | Immagine sandbox GnuCOBOL |
| `Dockerfile.sandbox-go` | sandbox/ | Immagine sandbox Go 1.22 |
| (altri Dockerfile per ciascun linguaggio) | sandbox/ | |

**File da modificare**:

| File | Modifica |
|------|----------|
| `AbstractWorker.java` | Aggiungere `executeSandbox()` method |
| `application.yml` | Config sandbox: images, limiti, Docker socket path |
| `mcp-bash-tool` (#25) | Usare `SandboxExecutor` come backend |

**Sforzo**: 3g (Livello 1 framework: 1g, Livello 2 sandbox: 1.5g, Dockerfile per linguaggi: 0.5g).
**Dipendenze**: #29 (Worker Lifecycle — per il modello JVM-per-type), #25 (mcp-bash-tool — per l'integrazione).
**Impatto**: **Molto alto** — sblocca compilazione/test nei worker, prerequisito per qualsiasi uso reale del framework.

**Totale Execution Sandbox**: ~3 giorni di lavoro.

---
---

# Pattern Claude Code → Agent Framework

Mappatura sistematica dei pattern architetturali di Claude Code che il framework puo' adottare.
Ogni pattern e' valutato per stato (✅ fatto, 🔧 pianificato, ❌ mancante) e roadmap item di riferimento.

## 1. Context Management

| # | Pattern Claude Code | Come funziona in Claude Code | Stato framework | Mapping / Roadmap |
|---|--------------------|-----------------------------|-----------------|-------------------|
| P1 | **Auto-compacting** | Quando il contesto si avvicina al limite, comprime automaticamente i messaggi precedenti in un riassunto. Il worker continua a lavorare con storia compressa. | 🔧 B17 L2 | Worker SDK stima token pre-call. Se >75% context window, chiama LLM per riassumere i tool result vecchi. Il worker non si interrompe mai. |
| P2 | **CLAUDE.md (project instructions)** | File di istruzioni progetto caricato all'avvio di ogni sessione. Contiene convenzioni, architettura, path critici. | ❌ | Il manifest ha `systemPrompt` ma e' generico. Manca un meccanismo per caricare istruzioni specifiche del **progetto target** (es. `cps4/CLAUDE.md`). Design: campo `projectInstructions` nel `Plan` → propagato come system message aggiuntivo. |
| P3 | **Persistent memory (MEMORY.md)** | Memoria che persiste tra sessioni. L'agente salva pattern, decisioni, preferenze utente. Consultata all'inizio di ogni conversazione. | ❌ | I worker sono stateless — ogni task parte da zero. Design: `WorkerMemory` nel DB, per-project. Dopo ogni task, il worker puo' scrivere "learnings" (pattern trovati, errori da evitare). Il CONTEXT_MANAGER li include nel contesto dei task successivi. |
| P4 | **Session resume** | Riprende da una conversazione precedente con tutto il contesto. | 🔧 parziale | Event Sourcing (#1) permette replay dello stato. Ma un worker non puo' "riprendere" un task interrotto — riparte da zero. Design: serializzare conversation history del worker nel DB → al retry, ricaricare come messaggi precedenti. |
| P5 | **System reminders** | Il sistema inietta messaggi di contesto nei risultati dei tool (es. hook feedback, plan mode status). | ❌ | I tool result vanno direttamente al LLM senza intercettazione. Design: `ToolResultEnricher` — intercetta i risultati dei tool e aggiunge contesto (es. "hai usato 65% del budget token", "questo file e' di proprieta' di FE-001"). |

## 2. Planning & Execution

| # | Pattern Claude Code | Come funziona in Claude Code | Stato framework | Mapping / Roadmap |
|---|--------------------|-----------------------------|-----------------|-------------------|
| P6 | **Plan mode** | Fase read-only: esplora il codebase, disegna l'approccio, chiede approvazione utente, poi implementa. Separazione netta tra planning e execution. | 🔧 parziale | Il Planner genera il piano (DAG), ma non c'e' una fase di "esplorazione" pre-planning. Il Council da' raccomandazioni ma non esplora il codice. Design: fase di **discovery** pre-planning dove un worker esplora il progetto e produce un report strutturato (vedi AUDIT_MANAGER dual-mode nelle note operative). |
| P7 | **TodoWrite (progress tracking)** | L'agente mantiene una lista di task con stati (pending/in_progress/completed). Aggiorna in tempo reale. L'utente vede il progresso. | ❌ | L'orchestratore traccia lo stato dei `PlanItem` ma i **worker interni** non tracciano il loro progresso sub-task. Design: il worker puo' emettere eventi `PROGRESS` via Redis stream (es. "leggendo file 3/11", "generando CSS"), visualizzati nella SSE dashboard. |
| P8 | **Phased execution** | Workflow strutturato: Phase 1 (explore), Phase 2 (design), Phase 3 (review), Phase 4 (implement). Ogni fase ha tool diversi. | ❌ | I worker hanno un unico turno di esecuzione. Design: `WorkerPhase` enum (EXPLORE, IMPLEMENT, VERIFY). Ogni fase puo' avere tool diversi (EXPLORE: solo read tools, IMPLEMENT: tutti, VERIFY: solo read + test). Il manifest dichiara le fasi supportate. |
| P9 | **Subagent delegation (Task tool)** | L'agente lancia sotto-agenti specializzati per task paralleli. Ogni subagent ha il suo contesto e tool set. | ✅ | Il framework E' questo — l'orchestratore delega a worker specializzati. Ma i worker non possono delegare a loro volta (no worker-to-worker). Design: opzionale — un worker potrebbe creare sub-task via API orchestratore. |
| P10 | **Parallel tool calls** | Chiama piu' tool contemporaneamente nella stessa risposta quando non ci sono dipendenze. | ❌ | Spring AI esegue le tool call in sequenza. Design: `ParallelToolCallingManager` (gia' presente in `spring-ai-reactive-tools`) — il worker SDK lo abilita. Il LLM genera multiple tool call, il manager le esegue in parallelo con virtual threads. |

## 3. Safety & Policy

| # | Pattern Claude Code | Come funziona in Claude Code | Stato framework | Mapping / Roadmap |
|---|--------------------|-----------------------------|-----------------|-------------------|
| P11 | **3-level permission system** | Auto-allow, prompt user, deny — per ogni tool, configurabile. | ✅ | 3 livelli: manifest allowlist, task-level policy, path ownership. Vedi sezione "3 livelli di tool policy". |
| P12 | **Pre/Post tool hooks** | Script shell eseguiti prima/dopo ogni tool call. PreToolUse puo' bloccare, PostToolUse puo' formattare. | 🔧 parziale | `HookPolicy` (LLM-generated) controlla cosa il worker puo' fare. Ma non ci sono hook **scriptabili** (shell command). Design: `PolicyEnforcingToolCallback` potrebbe supportare hook configurabili per tool (es. "dopo fs_write, esegui shellcheck"). |
| P13 | **Dangerous command detection** | Blocca comandi distruttivi (rm -rf, git push --force, DROP TABLE). | 🔧 parziale | `PathOwnershipEnforcer` blocca scritture fuori path. Ma non c'e' content inspection (il contenuto di un `fs_write` potrebbe essere distruttivo). Design: `ContentPolicyChecker` — analizza il contenuto scritto per pattern pericolosi. |
| P14 | **Secret scanning** | Scansiona contenuto per credenziali, API key, password prima di scrivere/committare. | ❌ | Nessun meccanismo. Design: hook PostToolUse su `fs_write` che scansiona per pattern di secret (regex: `sk-`, `password=`, `API_KEY=`). Se trovato, blocca la scrittura e notifica. |
| P15 | **Reversibility assessment** | Prima di azioni irreversibili, valuta il rischio e chiede conferma. Preferisce azioni sicure. | ❌ | I worker eseguono senza valutare la reversibilita'. Design: il manifest dichiara `dangerousTools` (lista). Prima dell'esecuzione, il `PolicyEnforcingToolCallback` richiede conferma esplicita (flag nel task) o logga warning nell'audit trail. |
| P16 | **Git safety protocol** | Mai force push, mai amend published, mai skip hooks. Preferisce commit nuovi. | ❌ | Nessun tool git nei worker (attualmente). Rilevante quando/se si aggiunge `mcp-bash-tool` (#25) o tool git dedicati. |

## 4. Quality & Review

| # | Pattern Claude Code | Come funziona in Claude Code | Stato framework | Mapping / Roadmap |
|---|--------------------|-----------------------------|-----------------|-------------------|
| P17 | **Proactive code review** | Dopo aver scritto codice, lancia automaticamente un agente di review. | 🔧 parziale | Ralph-Loop (#16) fa quality gate con feedback. Ma il review non e' un agente separato — e' integrato nel loop. Design: AUDIT_MANAGER post-task (gia' esistente) potrebbe essere rafforzato con review specifici per tipo (security, style, test coverage). |
| P18 | **Test running after changes** | Dopo ogni modifica significativa, esegue i test automaticamente. | ❌ | I worker non eseguono test (nessun tool shell). Con #25 (`mcp-bash-tool`), un worker potrebbe eseguire `mvn test` o `npm test`. Design: fase VERIFY (vedi P8) con esecuzione test automatica. |
| P19 | **Code simplification** | Agente dedicato che semplifica il codice dopo l'implementazione, mantenendo funzionalita'. | ❌ | Non previsto. Possibile come worker specializzato (`SIMPLIFIER`) nel DAG post-implementation. Bassa priorita'. |
| P20 | **Comment analysis** | Verifica che i commenti nel codice siano accurati rispetto all'implementazione. | ❌ | Non previsto. Possibile come check nell'AUDIT_MANAGER. Bassa priorita'. |

## 5. Communication & UX

| # | Pattern Claude Code | Come funziona in Claude Code | Stato framework | Mapping / Roadmap |
|---|--------------------|-----------------------------|-----------------|-------------------|
| P21 | **AskUserQuestion (human-in-the-loop)** | L'agente puo' chiedere chiarimenti all'utente durante l'esecuzione. Non procede con assunzioni. | ❌ | I worker sono completamente autonomi — nessun canale di comunicazione con l'utente durante l'esecuzione. Design: stato `WAITING_INPUT` nel PlanItem + endpoint SSE per domande al frontend + UI per rispondere. Alto impatto ma alta complessita'. |
| P22 | **Progress reporting** | L'utente vede in tempo reale cosa sta facendo l'agente (tool calls, file letti, task completati). | 🔧 parziale | SSE events per cambio stato task (QUEUED→RUNNING→DONE). Ma non c'e' visibilita' dentro l'esecuzione del worker (quali tool sta chiamando, quanti token usati). Design: worker emette eventi `TOOL_CALL` via Redis → SSE (#5). |
| P23 | **Output styles** | Modi diversi di comunicare (explanatory, concise, learning). L'agente adatta il tono. | ❌ | I worker producono output tecnico uniforme. Non rilevante per agenti autonomi — piu' rilevante per la presentazione dei risultati all'utente nella dashboard. |
| P24 | **Insight blocks** | Spiegazioni educative sui trade-off e le scelte implementative. | ❌ | I worker non spiegano le loro scelte. Design: il risultato del worker potrebbe includere una sezione `reasoning` (gia' presente come campo nel modello) resa visibile nella dashboard. Basso sforzo, alto valore informativo. |

## 6. Tool Architecture

| # | Pattern Claude Code | Come funziona in Claude Code | Stato framework | Mapping / Roadmap |
|---|--------------------|-----------------------------|-----------------|-------------------|
| P25 | **Dedicated tools over shell** | Preferisce Read/Write/Edit/Glob/Grep dedicati invece di cat/sed/grep via Bash. Piu' sicuro, piu' controllabile. | ✅ | Il framework usa tool MCP dedicati (`fs_read`, `fs_write`, `fs_search`, `fs_grep`). Nessun tool shell generico (correttamente). |
| P26 | **Deferred tool loading (ToolSearch)** | I tool non essenziali vengono caricati on-demand. Riduce il clutter nel contesto iniziale del LLM. | ❌ | Tutti i tool dichiarati nel manifest sono caricati subito nel ChatClient. Con ~548 tool totali, un worker che dichiara `tools: ALL` vedrebbe troppi tool. Design: `ToolSearch` MCP tool — il worker chiede "cerco tool per database" e riceve i nomi disponibili. Poi li chiama. Riduce il contesto iniziale. |
| P27 | **Background tasks** | Lancia task in background e continua a lavorare. Controlla il risultato dopo. | ✅ | Il framework e' intrinsecamente asincrono — tutti i task sono "background" dal punto di vista dell'orchestratore. I worker producono risultati via Redis stream. |
| P28 | **Worktrees (isolation)** | Crea copie isolate del repo per lavorare in parallelo senza conflitti. | ❌ | I worker condividono lo stesso filesystem. Se due worker scrivono nello stesso file → conflitto. Design: `WorkerWorkspace` — ogni worker riceve una directory di lavoro isolata (worktree git o tmpdir). Il risultato viene merged dall'orchestratore. Alto impatto per parallelismo sicuro. |

## Priorita' pattern

| Priorita' | Pattern | Perche' | Sforzo | Roadmap |
|-----------|---------|---------|--------|---------|
| **CRITICA** | P1 Auto-compacting | Il framework crasha oggi (B17) | 2g | B17 |
| **ALTA** | P2 Project instructions | I worker non conoscono le convenzioni del progetto | 0.5g | Nuovo |
| **ALTA** | P7 Progress tracking | L'utente non sa cosa sta succedendo dentro un worker | 1g | #5 (SSE) |
| **ALTA** | P28 Worktrees | Prerequisito per parallelismo sicuro tra worker | 2g | Nuovo |
| **ALTA** | P22 Progress reporting | Visibilita' tool call in tempo reale | 1g | #5 (SSE) |
| **MEDIA** | P5 System reminders | Budget token, ownership info nei tool result | 0.5g | Nuovo |
| **MEDIA** | P6 Discovery phase | Pre-planning exploration migliora qualita' piano | 1g | Nota AUDIT_MANAGER |
| **MEDIA** | P8 Phased execution | Separazione EXPLORE/IMPLEMENT/VERIFY | 1.5g | Nuovo |
| **MEDIA** | P10 Parallel tool calls | Performance — gia' supportato da Spring AI | 0.5g | spring-ai-reactive-tools |
| **MEDIA** | P14 Secret scanning | Sicurezza — previene leak di credenziali | 0.5g | Nuovo |
| **MEDIA** | P3 Persistent memory | I worker imparano tra task successivi | 1.5g | Nuovo |
| **MEDIA** | P21 Human-in-the-loop | Riduce assunzioni errate, ma complesso | 3g | Nuovo |
| **BASSA** | P26 Deferred tool loading | Utile solo con molti tool per worker | 1g | Nuovo |
| **BASSA** | P4 Session resume | Retry riprende da dove si era fermato | 1g | Nuovo |
| **BASSA** | P18 Test after changes | Richiede #25 (bash tool) prima | 0.5g | Dopo #25 |
| **BASSA** | P15 Reversibility assessment | Safety net per azioni distruttive | 0.5g | Nuovo |

**Totale sforzo stimato**: ~18g per tutti, ~7g per i pattern CRITICA+ALTA.

---
---

# Observability & Tracking

Quadro completo di cosa il framework traccia (decisioni, errori, modifiche, storico) e cosa manca.

## Stato attuale — cosa e' gia' implementato

| Capability | Storage | Retention | File chiave |
|------------|---------|-----------|-------------|
| **Tool call audit** (nome, worker, outcome, durata, input preview, violations) | SLF4J `audit.tools` + `AuditManagerService` REST (in-memory 10k) | Log sink esterno | `ToolAuditLogger.java`, `PolicyEnforcingToolCallback.java` |
| **Token tracking** (prompt, completion, total per task) | `PlanTokenUsage` (DB, per plan+workerType), `Provenance.TokenUsage` | Permanente (DB) | `AbstractWorker.recordTokenUsage()`, `PlanTokenUsageRepository.java` |
| **Error tracking** (failure reason per tentativo) | `DispatchAttempt.failureReason`, `PlanItem.failureReason`, `Plan.failureReason` | Permanente (DB) | `DispatchAttempt.java`, `OrchestrationService.java` |
| **Event sourcing** (7 tipi: PLAN_STARTED, TASK_DISPATCHED, TASK_COMPLETED, TASK_FAILED, PLAN_COMPLETED, PLAN_PAUSED, PLAN_RESUMED) | `PlanEvent` (append-only, sequence number per plan) | Permanente (DB) | `PlanEventStore.java`, `SpringPlanEvent.java` |
| **Dispatch attempts** (uno per retry: success, duration, failure reason) | `DispatchAttempt` entity | Permanente (DB) | `DispatchAttempt.java` |
| **Provenance** (model, promptHash, skillsHash, toolsUsed, traceId, timestamps) | Embedded in `AgentResult` → salvato con `PlanItem` | Permanente (DB) | `Provenance.java` |
| **Structured logging** (MDC: task_key, worker_type, attempt, trace_id, policy_active) | SLF4J `metrics.worker` | Log sink esterno | `WorkerMetricsInterceptor.java` |
| **Token budget enforcement** (check pre-dispatch, per-plan budget) | `Plan.budgetJson` | Permanente (DB) | `OrchestrationService.dispatchReadyItems()` |
| **SSE replay** (late-join riceve tutti gli eventi passati) | Legge da `PlanEvent` | Permanente | `SseEmitterRegistry.java` |

## 5 gap di observability

### G1 — Conversation history (✅ → S14)

**Problema**: i turni LLM non vengono salvati — solo il risultato finale (`PlanItem.result`).
Impossibile fare debug post-mortem ("perche' il worker ha letto file X prima di Y?"), impossibile fare session resume (P4).

**Impatto**: ALTO — senza conversation history non si puo' implementare ne' auto-compacting (P1/B17), ne' session resume (P4), ne' persistent memory (P3).

**Design proposto**: salvare il JSON completo dei messaggi Spring AI come JSONB in `DispatchAttempt.conversationLog`.
Questo e' il design piu' leggero — nessuna nuova entity, solo un campo aggiuntivo.
L'`AbstractWorker` serializza `chatClient.getMessages()` dopo ogni esecuzione.
Per retention: truncare a N turni (es. ultimi 50) se la conversazione e' troppo lunga.

**Sforzo**: 1g. **Pattern collegati**: P4 (session resume), P3 (persistent memory), P1 (auto-compacting).

### G2 — Decision reasoning (✅ → S13 PIANO_HISTORY.md)

### G3 — File modification tracking (✅ → S14)

**Problema**: quando un worker chiama `fs_write`, il tool audit logga "fs_write, SUCCESS, 150ms" — ma non **quale file**, non **cosa ha scritto**, non il **diff**.
Non c'e' rollback granulare, non c'e' review delle modifiche pre-merge.

**Impatto**: ALTO — prerequisito per worktrees (P28), code review automatico (P17), e rollback sicuro.

**Design proposto**: `FileModification` entity (NEW):
```
taskKey (FK → PlanItem)
filePath (VARCHAR)
operation (ENUM: CREATE, UPDATE, DELETE)
contentHashBefore (SHA-256, nullable per CREATE)
contentHashAfter (SHA-256, nullable per DELETE)
diffPreview (TEXT, max 5000 char, prime righe del diff)
occurredAt (TIMESTAMP)
```
Popolato da hook in `PolicyEnforcingToolCallback`: dopo ogni `fs_write` SUCCESS, intercetta il path dal tool input,
calcola hash del file. Opzionale: `git diff <file>` per il diffPreview.

**Sforzo**: 1.5g. **Pattern collegati**: P28 (worktrees), P13 (dangerous command detection), P15 (reversibility).

### G4 — Prometheus/Micrometer metrics (✅ → S12)

**Problema**: zero metriche applicative esposte. Il monitoring stack del server (Prometheus + Grafana) non ha visibilita' sul framework.
L'unica fonte sono i log strutturati — che richiedono parsing e non supportano alerting nativo.

**Impatto**: ALTO — senza metriche non c'e' dashboard operativa, non c'e' alerting, non c'e' capacity planning.

**Design proposto**: Spring Boot Actuator + `micrometer-registry-prometheus` (dipendenza gia' disponibile):

| Tipo | Nome metrica | Tags | Dove |
|------|-------------|------|------|
| Counter | `agent.tasks.total` | workerType, outcome (SUCCESS/FAILED) | `OrchestrationService.onTaskCompleted/Failed` |
| Timer | `agent.tasks.duration` | workerType | `WorkerMetricsInterceptor` |
| Gauge | `agent.tasks.active` | — | `OrchestrationService` (count RUNNING items) |
| Counter | `agent.tokens.total` | workerType | `PlanTokenUsageRepository.incrementTokensUsed` |
| Counter | `agent.tools.calls` | toolName, outcome (SUCCESS/DENIED/FAILED) | `PolicyEnforcingToolCallback` |
| Counter | `agent.plans.total` | outcome (COMPLETED/FAILED/PAUSED) | `OrchestrationService.completePlan` |
| Histogram | `agent.tools.duration` | toolName | `PolicyEnforcingToolCallback` |

Endpoint: `/actuator/prometheus` → scrape da Prometheus esistente → dashboard Grafana.

**Sforzo**: 0.5g. **Pattern collegati**: monitoring stack server SOL (Prometheus + Grafana gia' operativi).

### G5 — Persistent audit (✅ → S13 PIANO_HISTORY.md)

### G6 — Worker event pipeline (✅ → S14)

**Problema**: il server MCP (`simoge-mcp`, ~548 tool) esegue le tool call ma **non logga in modo strutturato
chi ha chiamato cosa e quando**. Le informazioni si perdono:
- Il **worker** (AI_TASK, REVIEW) chiama un tool via Spring AI MCP Client
- Il **server MCP** (Spring Boot) riceve la richiesta ed esegue — ma non sa quale worker, quale task, quale piano
- Il `ToolAuditLogger` nel framework logga lato client (worker SDK), ma il server MCP non ha visibilita'
- Se piu' worker chiamano lo stesso tool concorrentemente, nel log del server MCP e' impossibile correlare

**Impatto**: ALTO — prerequisito per la Monitoring Dashboard (#28): la UI deve mostrare "worker AI-001 ha chiamato
`azure_list_vms` alle 14:32, durata 1.2s, 15 risultati". Senza audit lato MCP, manca meta' del quadro.

**Design proposto**: audit log strutturato nel server MCP con propagazione identita':

**1. Propagazione identita' (framework → MCP server)**:
Il worker SDK inietta header custom nelle richieste MCP:
```
X-Agent-Task-Key: AI-001
X-Agent-Worker-Type: AI_TASK
X-Agent-Plan-Id: plan-42
X-Agent-Trace-Id: abc123
```
Spring AI MCP Client supporta custom headers via `McpClientConfig`. Il server MCP li legge con un `HandlerInterceptor`.

**2. Audit log strutturato (MCP server)**:
```java
@Component
public class McpToolAuditInterceptor implements HandlerInterceptor {
    // Logga in formato JSON strutturato — aggregabile da Vector/Loki → Grafana
    // Oppure: scrive su endpoint REST dell'orchestratore (push audit events)
}
```

Record di audit per ogni tool call:
```json
{
  "timestamp": "2026-03-01T14:32:15Z",
  "tool": "azure_list_vms",
  "taskKey": "AI-001",
  "workerType": "AI_TASK",
  "planId": "plan-42",
  "traceId": "abc123",
  "durationMs": 1200,
  "outcome": "SUCCESS",
  "resultCount": 15,
  "inputPreview": "{\"resource_group\": \"rg-prod\"}"
}
```

**3. Aggregazione nella UI** (#28):
La Monitoring Dashboard puo' consumare gli audit MCP in due modi:
- **Pull**: la UI interroga il server MCP (endpoint `/audit/events?taskKey=AI-001`)
- **Push**: il server MCP pubblica su Redis stream → orchestratore → SSE → dashboard

Il modo push e' preferibile: il server MCP emette eventi su `mcp:audit` Redis stream, l'orchestratore
li legge e li aggrega con gli eventi del framework (PlanEvent + ToolAudit) per una vista unificata.

**4. Statistiche aggregabili**:
- Tool piu' chiamati (per piano, per worker type)
- Latenza media per tool (per identificare bottleneck: es. `azure_create_deployment` lento)
- Tasso di errore per tool
- Storico chiamate per task (timeline nella dashboard)

**Sforzo**: 1g (0.5g propagazione header + interceptor MCP, 0.5g aggregazione Redis + endpoint).
**Pattern collegati**: G4 (Prometheus), G5 (persistent audit), #28 (dashboard), P22 (progress reporting).

## Riepilogo sforzo observability

| Gap | Sforzo | Priorita' | Dipendenze |
|-----|--------|-----------|------------|
| G1 Conversation history ✅ S14 | 1g | ALTA | — |
| G2 Decision reasoning | ✅ S13 | — | — |
| G3 File modification tracking ✅ S14 | 1.5g | ALTA | — |
| G4 Prometheus metrics | ✅ S12 | — | — |
| G5 Persistent audit | ✅ S13 | — | — |
| G6 Worker event pipeline ✅ S14 | 1g | ALTA | header propagation nel worker SDK |
| **Totale** | **5g** | | |

---
---

# Note operative

## Worker 0/3 tools — osservazione dall'uso reale

I worker eseguono con 0 tool abilitati, producendo solo output testuale nel DB (risultato LLM puro,
nessuna interazione filesystem). Il log mostra "0/3 tools".

**Causa root**: la pipeline di enrichment disconnessa (#23) non genera CONTEXT_MANAGER ne'
HOOK_MANAGER. Senza HOOK_MANAGER → nessuna HookPolicy → nessun tool abilitato.

**Workaround temporaneo** (prima di #23): configurare tool di default statici in `application.yml`
per ogni worker, oppure usare `ToolAllowlist.ALL` come fallback quando la policy e' assente.

**Fix definitivo**: #23 (enrichment pipeline) + #24 (toolHints/TOOL_MANAGER).

## Council — visibilita' e misurabilita'

Il Council e' attivo (pre-planning + COUNCIL_MANAGER in-plan), ma il suo impatto non e' facilmente
misurabile.

**Proposta**:
- Logging strutturato: raccomandazioni Council vs decisioni planner
- Metrica: % raccomandazioni seguite
- A/B testing: `council.enabled: false`
- Collegamento GP (#11): reward include indirettamente l'effetto Council

## AUDIT_MANAGER come fornitore di contesto pre-esistente

**Osservazione dal run CPS v4 #2**: il Council produce raccomandazioni architetturali concrete
(CSP headers, SVG sanitization, IIFE per app.js, footer sync comments), ma i worker non sanno
**cosa c'e' gia'** nel progetto. L'AUDIT_MANAGER dovrebbe partecipare anche **prima** del planning
(o come primo task enrichment), non solo come audit post-facto.

**Ruolo proposto** (dual-mode):
1. **Pre-planning** (input al Council): analizza lo stato attuale del progetto (file esistenti,
   struttura, dipendenze, cio' che e' gia' implementato) e produce un report di contesto.
   Il Council riceve questo report come input aggiuntivo per formulare raccomandazioni piu' precise.
2. **Post-task** (audit tradizionale): verifica il lavoro prodotto dai worker (come oggi).

**Collegamento con enrichment pipeline (#23)**:
- L'AUDIT_MANAGER puo' essere auto-injected come dipendenza comune insieme a CONTEXT_MANAGER
- DAG arricchito: `AUDIT_MANAGER-001 + CM-001 → RAG-001 → [domain workers]`
- Il risultato dell'AUDIT_MANAGER (cosa c'e') + CONTEXT_MANAGER (file rilevanti) + RAG (semantica)
  formano il contesto completo per i domain worker

**Impatto**: evita che i worker rigenerino cio' che esiste gia' o producano conflitti con lo stato attuale.
Particolarmente utile per progetti iterativi (run #2, #3...) dove molto e' gia' stato fatto.

---
---

# Storico implementazioni

Le sezioni completate (✅) sono state spostate in **PIANO_HISTORY.md** per mantenere
questo documento focalizzato sul piano di evoluzione futuro.

**PIANO_HISTORY.md** contiene:
- Feature implementate: #1, #2, #3, #4, #6, #11, #12, #14, #15, #16, #17, #18 (motivazioni architetturali complete)
- RAG Pipeline: piano dettagliato 3 sessioni, struttura modulo, config YAML, fonti
- Bug fix: B1-B7, B9, B13-B16 (sessione S8)
- Session log: S1, S2, S3, S5, S6, S7, S8-serendipity, S8-bugfix, S8-workers, S8-research, S9
- Riepilogo file per sessione (tabella riassuntiva)

_→ Dettagli in PIANO_HISTORY.md_
