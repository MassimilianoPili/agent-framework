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

### 13. Council Taste Profile ✅ → [documentazione/01-fondazioni-core.md](documentazione/01-fondazioni-core.md)

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

Verifica effettiva del codice nel repository (non solo piano). Aggiornato: 2026-03-14.

## Non implementati (1 item — nessun codice)

| # | Item |
|---|------|
| 44 | Execution Sandbox Containerizzato |

**Nota**: #21 implementato S20. #41 coperto da #85 (PersistentHomologyService).
#32, #34 gia' implementati (PolicyHasher, FederationMetricsExporter).

## Parzialmente implementati (5 item — gap operativi/UI, non codice Java)

| # | Item | Cosa manca |
|---|------|------------|
| 7 | Context Cache (TASK_MANAGER) | TASK_MANAGER worker (bloccato da tracker-mcp) |
| 8 | DAG + Mermaid | Miglioramenti UI frontend |
| 9 | Hierarchical Plans | Estensioni previste (item futuri) |
| 33 | Token Economics | Dashboard Grafana |
| 36 | Worker Pool Sizing | Dashboard Grafana, live monitoring |
| 40 | Shapley Value | Dashboard Grafana |

**Promossi a completati**: #35 (pesi configurabili via `ContextQualityProperties` + 4 test),
#37 (PidBudgetController 170 righe + 10 test + integrazione OrchestrationService),
#38 (LTL verifier integrato in `checkPlanCompletion` + event `LTL_VERIFICATION` + 2 test).

**Nota**: #5 (SSE TrackerSync), #10 (L3 enforcement), #21 (topic splitting), #30 (hash chain),
#31 (Ed25519), #39 (PolicyLattice), #42 promossi a "completati" (S16-S20).
#8, #9 presenti dall'initial commit — richiedono estensioni.

---
---

# Bug noti e fix

19 bug fixati (B1-B19 ✅). Dettagli fix: [documentazione/01-fondazioni-core.md](documentazione/01-fondazioni-core.md) (S8-bugfix).

- **B8** ✅ `buildContextJson()`: dipendenze mancanti non piu' iniettate come `"{}"` placeholder — escluse dal JSON. Log distingue fonti (DB vs cache).
- **B12** ✅ `gpTaskOutcomeService`: null-check gia' presente su tutti i path. Aggiunto test di verifica.

---

## Mappatura nomi tool (B13) ✅

Tutti i 6 file corretti (B13, B14, B15, B16). Centralizzazione in `ToolNames.java` (#27 ✅).
Dettagli: PIANO_HISTORY.md.

---
---

# Sessione 8 — Fix bug critici + resilienza ✅ → [PIANO_HISTORY.md]

---
---

# Roadmap items #19-#26

## #19 ✅ — Retry manuale via DB `TO_DISPATCH` → [PIANO_HISTORY.md]

---

## #20 ✅ — Decisione modello LLM per task (planner) → [PIANO_HISTORY.md]

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

L1: planner genera `toolHints` → fallback se HookPolicy assente. L2: `TOOL_MANAGER` worker dedicato (Haiku, read-only). `EnrichmentInjectorService` fan-out. Policy resolution: TM > HM > Static. 10 test. S15.

---

## #25 ✅ — mcp-bash-tool + mcp-python-tool → [PIANO_HISTORY.md]

---

## #26 ✅ — Cost tracking + auto-split (L1 ✅ / L2 ✅ S15)

L1: `CostEstimationModel` per task. L2: auto-split task costosi via planner callback. Commit: `b4abca2`.

---

## #27 ✅ — Centralizzazione nomi tool (ToolNames registry) → [PIANO_HISTORY.md]

---

## #28 ✅ — Monitoring Dashboard UI (real-time) → S14

4 pannelli: DAG Live (Mermaid.js), Event Stream (SSE), Worker Detail, Stats. 3 nuovi SSE event types (TOOL_CALL_START/END, TOKEN_UPDATE). Vanilla JS. Commit: `4f47cdf`.

---

## #29 ✅ — Worker Lifecycle Management (kill, singleton, JVM-per-type)

Opzione D (JVM-per-WorkerType). Phase 1: tutto in-process (`a09df71`). Phase 2: hybrid REST dispatch + HTTP callback (`9a4c580`). `WorkerRuntime`, `WorkerRuntimeController`, `WorkerDispatcher`, `CancellationToken`. `RedisTaskLockService` (SETNX + Lua release + heartbeat 60s). Dettagli: PIANO_HISTORY.md.

---

# Roadmap items #30-#34 — Blockchain-Inspired Enhancements ✅

Tutti implementati. Dettagli: [documentazione/03-mathematical-foundations.md](documentazione/03-mathematical-foundations.md).

## #30 ✅ — Hash Chain Tamper-Proof su `plan_event`

`HashChainVerifier`, SHA-256 chain, `GET /verify-integrity`. V27 migration. 12 test. S15.

## #31 ✅ — Verifiable Compute (Ed25519)

`Ed25519Signer`, `SignedResultEnvelope`, `WorkerSigningService` (worker-sdk), `SignatureVerificationService` + `WorkerKey` entity (orchestrator). V29 migration. TOFU key discovery. 12 test crypto + integration. S18-S19.

## #32 ✅ — Policy-as-Code Immutabile (Commitment Hash)

SHA-256 commitment hash su HookPolicy. `PolicyCommitmentService`, `PolicyVerificationInterceptor`. Commit: `e8ee221`. S18.

## #33 ✅ — Token Economics (Double-Entry Ledger)

`TokenLedger` entity, `TokenLedgerService` (debit/credit/creditShapley), `TokenLedgerResponse` DTO, V24 migration, Prometheus metriche, 25 test. S16.

## #34 ✅ — Federazione Multi-Server (Design Interfacce)

`FederationProtocol`, `FederationService`, `FederationController` + DP noise. 8 test. S16.

---

# Roadmap items #35-#43 — Mathematical Foundations ✅

## #35 ✅ — Context Quality Scoring (Teoria dell'Informazione)

`ContextQualityService`, file relevance + entropy proxy + KL divergence, 4° reward source (0.15). S15. Commit: `75c484b`.
Pesi configurabili via `ContextQualityProperties` (`gp.context-quality.weights.*`), default 0.45/0.30/0.25.

## #36 ✅ — Worker Pool Sizing (Queueing Theory)

`QueueAnalyzer` (Erlang C + Little's Law + CPM), `QueuingCapacityPlanner` (M/G/1 P-K), `CriticalPathCalculator`, `TropicalScheduler`. 15 test. S17.

## #37 ✅ — Adaptive Token Budget (PID Controller)

`PidBudgetController` (PID in-memory per planId×workerType), integrazione OrchestrationService. 10 test. S16. Commit: `4ee1d76`.

## #38 ✅ — State Machine Verification (LTL)

`LTLPolicyVerifier` (4 formulae LTLf: S1/S2 safety, L1/L2 liveness), `StateMachineVerifier` (BFS model checker). 10+2 test. S15.
Integrato in `checkPlanCompletion()`: event `LTL_VERIFICATION` appendato post-completion.

## #39 ✅ — Policy Lattice Composition (Teoria dei Reticoli)

`PolicyLattice` (meet-semilattice, TOP/BOTTOM, wildcard). 17 test. S15.

## #40 ✅ — Shapley Value per Reward Distribution

`ShapleyDagService` (Monte Carlo, Kahn's random topo-sort), V25 migration, `creditShapley()`. 14 test. S16. Commit: `b3c0d3d`.


---

## #41 ✅ — Topological Pattern Detection (Persistent Homology)

Coperto da #85 `PersistentHomologyService` (Vietoris-Rips + Union-Find β₀ barcodes). Dettagli: [documentazione/06-fase-9-10-research.md](documentazione/06-fase-9-10-research.md).

---

## #42 ✅ — Global Task Assignment (Hungarian Algorithm)

`HungarianAlgorithm` (Kuhn-Munkres O(n³)), `GlobalAssignmentSolver` (cost matrix da GP), `AssignmentResult` DTO. Config `global-assignment:`. 15 test. S16. Commit: `7800c9e`.

## #43 ✅ — Differential Privacy per Metriche Federate

`DifferentialPrivacyService` (Laplace + Gaussian noise), `PrivacyBudgetTracker` (epsilon accounting). 10 test. S16.

---

# Advanced Mechanisms (#45-#49) ✅

Tutti implementati in S21-S22. Commit: `84253d3`.

## #45 ✅ — Merkle Tree per DAG Verification

`MerkleDagVerifier`, `DagHashService`. SHA-256 per nodo, merkle root per sink nodes. V11 migration. 8 test.

## #46 ✅ — Verifiable Council Deliberation (Commit-Reveal)

`CommitRevealCouncil`, `CouncilCommitment`. Two-phase: commit (blinded hash) → reveal (verify). 7 test.

## #47 ✅ — Reputation Staking (Teoria dei Giochi)

`ReputationStakingService`, `StakeEscrow`. Stake ELO → reward multiplier. 6 test.

## #48 ✅ — Content-Addressable Storage per Artifact

`ContentAddressableStore` (SHA-256 keyed), `ArtifactDeduplicator`. Plan-scoped workspace. 9 test.

## #49 ✅ — Quadratic Voting per Council Weighting

`QuadraticVotingService`, `VotingBudget`. sqrt(credits) = votes, budget depletion. 8 test.

---

# Research Domains (#50-#61) ✅

12 items in 3 domini (Finanza, Sistemi Complessi, Matematica Avanzata), ~22.5g. Sessione S8-research.
Dettagli: [documentazione/05-fase-8-research.md](documentazione/05-fase-8-research.md) | `docs/agent-framework/research-domains-new.md`

---

## Research Domains — Fasi 9-12 (#62-#106) ✅

67 items completati, ~147.5g totali. Dettagli per fase:

| Fase | Items | Sforzo | File |
|------|-------|--------|------|
| 9 (#62-#76) | 15 | ~33.0g | [documentazione/06-fase-9-10-research.md](documentazione/06-fase-9-10-research.md) |
| 10 (#77-#86) | 10 | ~24.0g | [documentazione/06-fase-9-10-research.md](documentazione/06-fase-9-10-research.md) |
| 11 (#87-#96) | 10 | ~22.5g | [documentazione/07-fase-11-12-research.md](documentazione/07-fase-11-12-research.md) |
| 12 (#97-#106) | 10 | ~21.5g | [documentazione/07-fase-11-12-research.md](documentazione/07-fase-11-12-research.md) |

Consolidamento: `research-domains-consolidation.md` (matrice 57×21, Mermaid, coverage, theory clusters)

### Ordine implementazione Fase 13

```
Fase 13a (core, ~6.5g):            #110 ✅ → #111 ✅ → #116 ✅
Fase 13b (context+prompt, ~4.5g):  #107 ✅ → #108 ✅
Fase 13c (avanzato, ~8.0g):        #115 ✅ → #112 ✅ → #109 ✅
Fase 13d (attribution, ~5.0g):     #113 ✅ → #114 ✅
                                     ─────────────────────
                                     Totale: ~24.0g (#107-#116) ✅
```

### Riepilogo Fase 13 — Research Applicativi (#107-#116)

| Tier | # | Titolo | Componente gap | Sforzo | Valore |
|------|---|--------|----------------|--------|--------|
| 0 | 107 | Context Engineering ✅ | ContextWindowManager | 2.5g | Alto |
| 1 | 108 | Curriculum Prompting ✅ | PromptBuilder | 2.0g | Medio-Alto |
| 0 | 109 | Iterated Amplification ✅ | FeedbackCollector | 3.0g | Alto |
| 0 | 110 | Semantic Caching ✅ | CacheService | 2.0g | Alto |
| 0 | 111 | Observability SLIs ✅ | MetricsExporter | 2.0g | Alto |
| 0 | 112 | MCTS Dispatch ✅ | OrchestrationService | 3.0g | Alto |
| 1 | 113 | Worker-to-Worker Handoff ✅ | OrchestrationService | 2.5g | Medio-Alto |
| 1 | 114 | Markov Shapley Value ✅ | TaskOutcomeService | 2.5g | Medio-Alto |
| 1 | 115 | Factorised Belief Models ✅ | GP Engine | 3.0g | Medio-Alto |
| 1 | 116 | Logical Induction ✅ | GP Engine | 1.5g | Medio-Alto |
|   |     | **Totale Fase 13** | | **24.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§46-§55)

Risultati ricerca Fase 13 (35+ paper, 7 connessioni trasversali): → [PIANO_HISTORY.md] (S23)

### Ordine implementazione Fase 14

```
Fase 14a (core online, ~6.5g):       #117 → #119 → #120
Fase 14b (context+examples, ~6.5g):  #121 → #122 → #123
Fase 14c (attribution, ~4.5g):       #124 → #125
Fase 14d (resilience, ~5.5g):        #118 → #126
                                       ─────────────────────
                                       Totale: ~23.0g (#117-#126)
```

### Riepilogo Fase 14 — Adaptive Operations & Online Learning (#117-#126)

| Tier | # | Titolo | Componente target | Sforzo | Valore |
|------|---|--------|-------------------|--------|--------|
| 0 | 117 | Anomaly Detection su SLI Streams | SliAnomalyDetector | 2.0g | Alto |
| 1 | 118 | Semantic Cache Warm Transfer | CacheWarmTransferService | 2.0g | Medio-Alto |
| 0 | 119 | MCTS Online Policy Learning | MctsOnlineLearner | 2.5g | Alto |
| 0 | 120 | Self-Refine Loop | SelfRefineLoop | 2.0g | Alto |
| 0 | 121 | Context Compaction Pipeline | ContextCompactionPipeline | 2.5g | Alto |
| 1 | 122 | Golden Example Registry | GoldenExampleRegistry | 2.0g | Medio-Alto |
| 0 | 123 | Distributed Checkpointing | CheckpointService | 3.0g | Alto |
| 1 | 124 | Causal Shapley Attribution | CausalShapleyService | 2.5g | Medio-Alto |
| 1 | 125 | Adaptive Exploration Schedule | AdaptiveExplorationSchedule | 2.0g | Medio-Alto |
| 0 | 126 | Multi-Agent Failure Taxonomy | FailureTaxonomyService | 2.5g | Alto |
|   |     | **Totale Fase 14** | | **23.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§56-§65)

### Risultati ricerca Fase 14 — Sintesi accademica (S24)

Ricerca completata su 10 item (#117-#126). 50+ paper validati, 6 correzioni al design originale, 5 connessioni trasversali.

#### #117 Anomaly Detection — BOCPD validato, 7 raccomandazioni

**Paper validati:** Adams & MacKay 2007 (arXiv:0710.3742, ~1800 cit, BOCPD), Turner et al. 2009 (ICML, GP-BOCPD), Fearnhead & Liu 2007 (hazard adattivo).

**Correzioni al design:** (1) hazard lambda deve essere per-SLI-type (latency cambia più di availability), (2) servono detector paralleli a 3 scale temporali (raw, 1min, 5min) per catturare sia spike che drift, (3) AR(1) observation model per latency autocorrelate, (4) run-length pruning hard cap 500 (memory safety), (5) alert con posterior parameters (non solo binary), (6) independent per-stream + correlation aggregation (non joint multivariate).

#### #118 Semantic Cache Warm Transfer — Ben-David bound come fondamento

**Paper validati:** Ben-David et al. 2010 (ML journal, T1, ~5000 cit, domain adaptation bound), Pan & Yang 2010 (TKDE, T1, ~22000 cit), SUPER (Gerstgrasser 2023, selective sharing), LOL-GP (Wang 2024, local transfer), RECaST (Hickey 2024, Bayesian discount), C2C (Fu et al. ICLR 2026, cache-to-cache).

**Insight chiave:** Discount principled = `affinity_discount(d_HH) × uncertainty_gate(σ²) × temporal_decay(age)`. Uncertainty gate: trasferire solo dove σ²_target > σ²_source. Cap al 1-10% del cache (SUPER). Nessun framework esistente ha semantic cache transfer — design originale.

#### #119 MCTS Online — EMA insufficiente, 3 meccanismi aggiuntivi

**Paper validati:** Garivier & Moulines 2011 (ALT, T1, Discounted UCB), Gelly & Silver 2011 (T1, RAVE/AMAF), Guez et al. 2012 (NeurIPS, T1, BAMCP), LiZero (arXiv:2502.00633, adaptive decay).

**Correzione:** EMA decay (lambda=0.95) è sufficiente per drift graduale ma non per cambiamenti bruschi (~20 osservazioni per adattarsi). Aggiungere: (1) per-node adaptive lambda gated on Welford variance, (2) RAVE warm-start per nodi freddi, (3) BAMCP root sampling dal GP posterior (un Thompson sample per episodio).

#### #120 Self-Refine — CRITICO: task-type gate obbligatorio

**Paper validati:** Self-Refine (Madaan et al. NeurIPS 2023, ~1000 cit), Reflexion (Shinn et al. NeurIPS 2023, ~1520 cit), Cannot Self-Correct (Huang et al. ICLR 2024), Dark Side (Zhang 2024, -20.4% accuracy), MAgICoRe (Chen et al. EMNLP 2025, +4% a metà costo), DDI (Adnan & Kuhn, Sci. Reports 2025, 60-80% decay in 2-3 iter).

**Correzione CRITICA:** Self-refine **degrada** reasoning senza feedback esterno (58.8% risposte corrette ribaltate). Design rivisto: style/format → self-refine OK (max 3 iter); code → self-refine con test esterni (max 2 iter); math/reasoning → NO self-refine, escalare a H2 o usare PRM (MAgICoRe pattern). Epsilon deve essere misurato esternamente (embedding distance, non self-reported). Aggiungere flip rate monitor.

#### #121 Context Compaction — Non compattare mai le reasoning chains

**Paper validati:** LLMLingua-2 (Pan et al. ACL 2024), TALE (He et al. ACL 2025), "Complexity Trap" (JetBrains, NeurIPS 2025 Workshop, -52% costi), AOI (arXiv:2512.13956, 3 layer, 72.4% compression), ACON (Kang 2025), EHPC (NeurIPS 2025 Spotlight, evaluator heads).

**Correzione:** 4 segnali per tier assignment (non solo age+relevance): relevance + age + access frequency + information scent. **Mai compattare abstractivamente le reasoning chains** — solo observations e tool outputs. Simple observation masking ≥ LLM summarization (JetBrains: LLM summarization causa +13-15% allungamento traiettorie).

#### #122 Golden Example Registry — ZPD: esempi leggermente più facili

**Paper validati:** Cui & Sachan 2025 (ZPD per ICL, IRT-based), DemoShapley (2024, Shapley per example valuation), LLM-as-Judge (Zheng et al. NeurIPS 2023, ~7365 cit, bias noti), EPR (Rubin et al. 2022, trained retriever).

**Correzione:** Esempi devono essere **leggermente più facili** del task, ordinati easy-to-hard (Zone of Proximal Development). Finestra: `D(example) ∈ [D(task) - 2δ, D(task)]`, non `D(task) ± δ`. Quality gate ibrida: LLM-as-judge + position-swap debiasing + task-completion success + human audit periodico. DemoShapley per garbage collection.

#### #123 Distributed Checkpointing — Semplificato: event sourcing + GP snapshot

**Paper validati:** Chandy & Lamport 1985 (T1, ~1900 cit), Carbone et al. 2015 (Flink ABS, ~500 cit), Cheng & Boots 2016 (NeurIPS, incremental sparse GP), LangGraph/CrewAI docs.

**Correzione MAGGIORE:** Design over-engineered. PlanEventStore **già copre** il recovery del Plan state via event replay. Checkpoint ridotto a: `{lastEventSeqNum, gpHyperparameters, gpTrainingDataHash, cacheHotEntries}`. No MCTS tree (effimero, ricostruibile). No Chandy-Lamport (orchestratore centralizzato → quiesce-then-snapshot). GP re-training da dati ~200ms per n<1000. Una tabella `checkpoints` + un metodo.

#### #124 Causal Shapley — Owen Values ≡ Shapley Flow

**Paper validati:** Heskes et al. NeurIPS 2020 (T1, ~133 cit, Causal Shapley), Janzing et al. AISTATS 2020 (marginal è corretto), Shapley Flow (Wang et al. AISTATS 2021), Frye et al. (Asymmetric Shapley), PASV (Lee et al. 2026).

**Insight chiave:** Shapley Flow prova che **Owen values con coalizioni tree-structured = edge-level Shapley su DAG causale**. Ergo #114 (Owen) e #124 (Causal) sono viste della stessa struttura. Implementare Owen first, poi aggiungere conditioning interventionale. Monotonicità **non** completamente risolta — usare separazione direct/indirect come diagnostica.

#### #125 Adaptive Exploration — Randomized GP-UCB come alternativa semplice

**Paper validati:** Srinivas et al. 2010 (ICML/IEEE TIT, T1, ~1737 cit, GP-UCB), Vakili et al. 2021 (AISTATS, T1, bounds più stretti), Russo & Van Roy 2014 (NeurIPS/OR, T1, IDS), Takeno et al. 2023 (ICML, T1, Randomized GP-UCB), Deng et al. 2022 (AISTATS, T1, WGP-UCB non-stationary).

**Scelta strategica:** 4 path possibili: (A) TS solo → zero lavoro ma perde cold-domain boost; (B) **Randomized GP-UCB** → sample beta da Gamma, 80% beneficio con 20% complessità; (C) design corrente → valido, con fix a formula IG-lambda; (D) IDS → gold standard teorico, overkill. Raccomandazione: **Path B come default, Path C come upgrade**. Fix: invertire relazione IG-lambda (basso IG → exploit, non il contrario).

#### #126 Multi-Agent Failure Taxonomy — Correzione venue + priorità detection

**Paper validati:** Cemri et al. 2025 (**NeurIPS 2025 D&B spotlight**, non ICLR, arXiv:2503.13657), Zhu et al. 2025 (AgentDebug, +24% accuracy), Bholani 2026 (Self-Healing Router, -93% LLM calls), CONSENSAGENT (Pitre et al. ACL 2025, anti-sycophancy), Vennemeyer et al. 2025 (3 tipi sycophancy in latent space), AGDebugger (Epperson et al. CHI 2025).

**Correzione:** Tassonomia MAST ha **3 categorie** (non 4): FC1 Specification & System Design (5 modi), FC2 Inter-Agent Misalignment (6 modi), FC3 Task Verification & Termination (3 modi). Priorità detection: (1°) FM-3.2 No/Incomplete Verification (silente, cascade multiplier), (2°) FM-2.6 Reasoning-Action Mismatch, (3°) FM-1.3 Step Repetition (facile da detectare). Self-Healing Router per System failures. 11.54% delle esperienze sufficienti per learning (ChatDev).

#### Connessioni trasversali Fase 14

| Connessione | Descrizione | Impatto |
|-------------|-------------|---------|
| Owen ≡ Shapley Flow | #114 Owen hierarchy = #124 Causal Shapley su DAG | Implementare Owen first |
| ZPD + IRT | #122 difficulty matching = #108 IRT, stessa infrastruttura | Zero codice nuovo per difficulty |
| Ben-David + GP σ² | #118 discount = affinity(GP means) ha interpretazione teorica | Non è euristica |
| TS vs #125 | Thompson Sampling (#93) rende #125 parzialmente ridondante | Path B (RGP-UCB) come compromesso |
| Self-Refine gate + GP σ² | #120 decide se refinare basandosi su σ² (alta → refine, bassa → skip) | DDI decay ∝ 1/σ² |

Dipendenze Fase 12, audit qualita' Fase 9-12, arricchimenti: → [documentazione/07-fase-11-12-research.md](documentazione/07-fase-11-12-research.md)

### Ordine implementazione Fase 15

```
Fase 15a (verification & safety, ~8.0g):     #127 → #135 → #133
Fase 15b (transparency, ~4.0g):              #128 → #129
Fase 15c (optimization & learning, ~5.0g):   #132 → #131
Fase 15d (exploration & monitoring, ~5.5g):  #134 → #136 → #130
                                               ─────────────────────
                                               Totale: ~22.5g (#127-#136)
```

### Riepilogo Fase 15 — Reflective Intelligence & Decision Transparency (#127-#136)

> Tema: il sistema acquisisce la capacita' di ragionare sul proprio ragionamento, spiegare le decisioni,
> trasferire conoscenza, rilevare patologie, e verificare gli output tramite giudici esterni.

| # | Titolo | Componente target | Sforzo | Valore | Tier |
|---|--------|-------------------|--------|--------|------|
| 127 | Process Reward Model | `ProcessRewardModelService` | 2.5g | Alto | 0 |
| 128 | Explainable Decision Trace | `DecisionTraceService` | 2.0g | Alto | 0 |
| 129 | Sycophancy Detection in Council | `SycophancyDetectorService` | 2.0g | Alto | 0 |
| 130 | Graph-Based Recovery Router | `RecoveryRouterService` | 2.5g | Alto | 0 |
| 131 | Cross-Plan Meta-Learning | `PlanArchetypeRegistry` | 3.0g | Alto | 1 |
| 132 | Token Cost Pareto Optimizer | `ParetoDispatchOptimizer` | 2.0g | Medio-Alto | 1 |
| 133 | AUDIT_MANAGER Dual-Mode | `AuditManagerDualModeService` | 2.5g | Alto | 0 |
| 134 | Information-Directed Sampling | `InformationDirectedSamplingService` | 2.0g | Medio-Alto | 1 |
| 135 | Execution Sandbox | `SandboxExecutionService` | 3.0g | Alto | 0 |
| 136 | Bayesian Surprise Monitor | `BayesianSurpriseMonitor` | 1.5g | Medio-Alto | 1 |
|   |     | **Totale Fase 15** | | **22.5g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§66-§75)

### Sintesi ricerca accademica Fase 15 (S25)

10 item ricercati, ~40 paper validati, 5 correzioni al design, 4 connessioni trasversali.

#### #127 Process Reward Model — GP-posterior-as-proxy validato

**Paper validati:** Lightman et al. "Let's Verify Step by Step" (ICLR 2024, T1, ~2646 cit, ORM vs PRM — PRM superiore), MAgICoRe (Chen et al. EMNLP 2025, T1, +4% vs Self-Refine a <50% compute), DDI (Adnan & Kuhn, Scientific Reports 2025, T1, 60-80% decay confermato — ma specifico per code debugging), Huang et al. (ICLR 2024, T1, riconfermato).

**Insight chiave:** Il campo converge verso **PRM training-free** (implicit PRM, confidence-as-reward, generative verification) — valida fortemente il GP-posterior-as-proxy. CodePRM (ACL 2025) emerge come primo PRM specifico per codice. Task-type gate (style OK, reasoning NO) confermato empiricamente.

#### #128 Explainable Decision Trace — Contrastive > feature importance

**Paper validati:** SHAP (Lundberg & Lee NeurIPS 2017, T1, ~31707 cit), LIME (Ribeiro et al. KDD 2016, T1, ~20717 cit).

**Correzione:** Aggregare SHAP da 4 componenti indipendenti (GP, MCTS, EFE, Shapley) **non è sound** nel caso generale (Faith-Shap, Tsai et al. JMLR 2023 — interazioni tra componenti ignorati). Raccomandazione: (1) SHAP component-level OK come decomposizione, (2) aggiungere interaction audit quando componenti disagreano, (3) **contrastive explanations** ("why A not B") più utili di feature importance per dispatch (Miller 2019, AI journal, ~4985 cit; Lerouge et al. 2026, workforce scheduling). ACAR (Kumaresan 2026) — proxy attribution correla debolmente con leave-one-out ground truth. XRL taxonomy (Milani et al. ACM Computing Surveys 2023, ~192 cit) mappa perfettamente sui 3 livelli del DecisionTrace.

#### #129 Sycophancy Detection — 3 segnali insufficienti, soglia troppo alta

**Paper validati:** CONSENSAGENT (Pitre et al. ACL **Findings** 2025 — non main track), Vennemeyer et al. (arXiv:2509.21305, 3 tipi sycophancy separabili in latent space, confermato), Sharma et al. (ICLR 2024, T1, ~597 cit, foundational).

**Correzioni al design:** (1) Cosine similarity threshold **0.85-0.90** (non 0.95 — troppo stretta, perde sycophancy e flagga genuine agreement). (2) Aggiungere 3 segnali mancanti: **reasoning diversity collapse** (similarità delle justification, non solo dei voti), **first-mover anchoring** (conformity al primo a rispondere — Zhu et al. ACL 2025 main track), **calibration probe confidence shift**. (3) Devil's advocate deve essere **collaborativo** non adversarial (ColMAD, Chen et al. 2025 — competitive debate causa "debate hacking"). (4) **Model diversity mandate**: almeno 2-3 famiglie diverse tra gli 8 membri Council (MAEBE, Erisken et al. 2025 — ensemble omogenei convergono su errori correlati).

#### #130 Recovery Router — Bholani REALE, Dijkstra confermato

**Paper validati:** Bholani "Self-Healing Router" (arXiv:2603.01548, **2 marzo 2026** — paper reale, non hallucination. John Deere/MIT. -93% LLM control-plane calls confermato). MAST (Cemri et al. NeurIPS 2025 D&B, riconfermato, ~238 cit).

**Insight chiave:** Bholani conferma **esattamente** il design RecoveryRouterService — Dijkstra deterministico su tool graph, edge reweighted a infinito su failure, LLM riservato solo per "no feasible path". Dijkstra è la scelta giusta per edge weights deterministici (success rate storici). SHIELDA (2025, structured exception handling), CHIEF (2026, hierarchical causal graph for failure attribution), Who&When (2025, best accuracy 53.5% agent-level). **Nessun framework in produzione fa graph-based rerouting** — il nostro sarebbe il primo in Java/Spring.

#### #131 Cross-Plan Meta-Learning — MAML irrilevante, CBP lineage

**Paper validati:** MAML (Finn et al. ICML 2017, T1, ~13957 cit — confermato ma **irrilevante**), SUPER (Bogin et al. **EMNLP 2024** — non Wang et al., ed è un benchmark non un approccio).

**Correzione MAGGIORE:** MAML è gradient-based parameter initialization. PlanArchetypeRegistry è **retrieval-based few-shot priming** — lineage corretto: Case-Based Planning (Gerevini et al. JAIR 2023, plan library maintenance) → Skill Libraries (Voyager, NeurIPS 2023 spotlight) → Plan Reuse (AgentReuse, Li et al. 2024, **93% reuse rate** via intent classification). Graph edit distance NP-hard → usare **two-stage retrieval**: pgvector embedding (coarse) + WL kernel (fine). Store anche archetypes falliti per contrastive signal (ETO, Song et al. ACL 2024). Quality score multi-dimensionale: success rate + adaptation cost + IRT difficulty weighted + coverage + freshness.

#### #132 Token Cost Pareto Optimizer — Sener & Koltun validato

**Paper validati:** Sener & Koltun (NeurIPS 2018, T1, confermato — multi-task learning as multi-objective optimization), Ehrgott (2005, textbook Springer, confermato).

**Insight chiave:** FrugalGPT (Chen et al. 2023) e RouteLLM (Ong et al. 2024) dimostrano che cascade semplici (cheap model first, escalate se incerto) catturano ~80% del risparmio con complessità minima. Pareto frontier esplicito è più flessibile ma può essere overkill. Raccomandazione: partire con cascade GP-guided (se mu alto e sigma basso → cheap worker, altrimenti escalate), poi evolvere a Pareto se servono >2 obiettivi.

#### #133 AUDIT_MANAGER Dual-Mode — AutoCodeRover = ISSTA 2024

**Paper validati:** AutoCodeRover (Zhang et al. **ISSTA 2024** — non ICSE 2025, ~185 cit, spectrum-based fault localization + AST search), SWE-Agent (Yang et al. **NeurIPS 2024**, ~792 cit, agent-computer interface design).

**Insight chiave:** Agentless (Xia et al. 2024, ~289 cit) valida il concetto di pre-planning: localizzazione strutturata prima dell'azione → 32% SWE-bench a $0.70. CodexGraph (NAACL 2025, graph DB per code context), LocAgent (ACL 2025, lightweight directed graph), CGM (NeurIPS 2025, code graph nel LLM attention — 43% SWE-bench con open-weight). Gerarchia consolidata: **graph > hierarchical summary > AST > flat file list**. METR Report (marzo 2026): ~50% dei patch SWE-bench Verified verrebbero rifiutati da maintainer reali — benchmark sovrastima utilità ~2x.

#### #134 Information-Directed Sampling — Russo & Van Roy confermato

**Paper validati:** Russo & Van Roy (NeurIPS 2014 + Operations Research 2018, T1, confermato — IDS minimizza ratio regret²/information gain).

**Nota:** Ricerca S24 (#125) ha già coperto IDS in dettaglio. IDS è computazionalmente più costoso di TS (~10x per GP posteriors). Randomized GP-UCB (raccomandato in S24) cattura 80% del beneficio con 20% della complessità. IDS rimane la scelta ottimale teorica ma il fallback a TS è pragmaticamente superiore.

#### #135 Execution Sandbox — SWE-bench ICLR 2024 confermato

**Paper validati:** Jimenez et al. SWE-bench (ICLR 2024, T1, confermato — execution-based evaluation).

**Insight chiave:** SWE-bench è il benchmark standard per execution-based evaluation. gVisor/Firecracker offrono isolamento superiore a Docker ma con overhead di setup. Per il nostro caso (compilazione Java + test JUnit), Docker con seccomp profile + network none + read-only root è sufficiente (difesa in profondità già nel design #44). Container pre-warm pool riduce startup latency da ~2-5s a ~200ms.

#### #136 Bayesian Surprise Monitor — Itti & Baldi = NeurIPS 2005

**Paper validati:** Itti & Baldi (**NeurIPS 2005** — non 2009; il 2009 è la versione Vision Research journal, ~1709 cit), Schmidhuber (IEEE TAMD 2010, T1, ~849 cit — learning progress ≠ raw surprise).

**Correzione:** (1) `KL(posterior || prior)` è la direzione canonica — confermata da Feldman & Friston (2010, ~1258 cit, free-energy framework). (2) La distinzione novelty/anomaly è supportata: novelty = surprise + model improvement (Schmidhuber), anomaly = surprise persistente senza apprendimento (Achiam & Sastry 2017, ~250 cit). (3) Per threshold adattivi: percentile-based come baseline, ma integrare con BOCPD per il caso anomaly — Altamirano et al. (ICML 2023) mostra che generalised Bayesian posteriors danno robustezza a model misspecification. (4) Integrazione BOCPD↔Surprise è profonda: BOCPD può operare sulla serie temporale di surprise stessa.

### Connessioni trasversali Fase 15

| Connessione | Item | Insight |
|-------------|------|---------|
| PRM ↔ Sandbox | #127 ↔ #135 | Sandbox fornisce pass/fail binario come segnale PRM per codice |
| Sycophancy ↔ Surprise | #129 ↔ #136 | Entropy collapse nel Council = surprise anomala → Bayesian surprise come meta-segnale per sycophancy detection |
| Recovery ↔ Taxonomy | #130 ↔ #126 | MAST classifica, Recovery Router instrada — ruoli complementari |
| Meta-Learning ↔ IRT | #131 ↔ #108 | IRT difficulty scores pesano la qualità degli archetypes |
| BOCPD ↔ Surprise | #117 ↔ #136 | BOCPD opera sulla serie temporale di surprise — changepoint in surprise level |

### Correzioni al design identificate (6 totali)

1. **#128**: SHAP aggregato da 4 componenti non è sound → aggiungere interaction audit + contrastive explanations
2. **#129**: Soglia cosine 0.95 → 0.85-0.90; aggiungere 3 segnali mancanti; devil's advocate collaborativo
3. **#131**: MAML irrilevante → CBP/skill library lineage; SUPER = Bogin (non Wang); GED → two-stage retrieval
4. **#133**: AutoCodeRover = ISSTA 2024 (non ICSE 2025)
5. **#136**: Itti & Baldi = NeurIPS 2005 (non 2009)
6. **#132**: Cascade GP-guided più pragmatico di Pareto frontier esplicito come punto di partenza

---

### Ordine implementazione Fase 16

```
Fase 16a (security & foundations, 7.5g):     #137 → #138 → #142
Fase 16b (verification & testing, 4.5g):     #139 → #146
Fase 16c (prediction & learning, 7.5g):      #141 → #143 → #140
Fase 16d (scaling, 5.5g):                    #144 → #145
```

### Riepilogo Fase 16 — Operational Maturity & Production Resilience (#137-#146)

| # | Titolo | Service | Sforzo | Valore | Tier |
|---|--------|---------|--------|--------|------|
| 137 | Prompt Injection Detector | `PromptInjectionDetectorService` | 2.5g | Alto | 0 |
| 138 | Tenant Context Isolation | `TenantIsolationService` | 3.0g | Alto | 0 |
| 139 | Integration Test Framework | `PlanIntegrityTestFramework` | 2.5g | Alto | 0 |
| 140 | Human Correction Learning | `HumanCorrectionLearnerService` | 2.5g | Alto | 1 |
| 141 | Predictive Cost & Failure Forecaster | `PredictiveForecasterService` | 2.5g | Alto | 1 |
| 142 | Distributed Tracing Correlator | `DistributedTracingService` | 2.0g | Alto | 0 |
| 143 | Failure Pattern Predictor | `FailurePatternPredictorService` | 2.5g | Alto | 1 |
| 144 | Multi-Instance Plan Router | `PlanRoutingService` | 3.0g | Medio-Alto | 0 |
| 145 | Hierarchical Sub-Plan | `SubPlanOrchestrationService` | 2.5g | Alto | 0 |
| 146 | Plan Integrity Verifier | `PlanIntegrityVerifierService` | 2.0g | Alto | 0 |
|   |     | **Totale Fase 16** | | **25.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§76-§85)

### Sintesi ricerca Fase 16 (S25)

Ricerca accademica completata su tutti i 10 item (#137-#146). 10 agenti paralleli, ~20 paper citati validati, ~30 paper nuovi trovati. Di seguito le sintesi per item con correzioni e raccomandazioni.

#### #137 — Prompt Injection Detector
- **Paper validati**: Greshake et al. (AISec 2023, ~761 cit), Jain et al. (arXiv 2023, T2), Yi et al. (**KDD 2025**, non arXiv — venue upgrade)
- **Paper nuovi**: Kang et al. (NAACL 2025), Spotlighting/Datamarking (L0 defense layer)
- **Correzione**: aggiungere Spotlighting come Layer 0 pre-filter

#### #138 — Tenant Context Isolation
- **Krebs et al.**: venue è **CLOSER 2012**, non ICSEA; titolo è "Architectural Concerns"
- **Paper nuovi**: PROMPTPEEK (NDSS 2025), Burn-after-use (arXiv 2026)
- **Raccomandazione**: aggiungere PostgreSQL RLS come defense-in-depth

#### #139 — Integration Test Framework
- **Paper validati**: QuickCheck (ICFP 2000, ~1271 S2), Swarm Testing (ISSTA 2012, ~200)
- **Paper nuovi**: quickcheck-state-machine (Andjelkovic 2017), MASEval/MAESTRO
- **Raccomandazione**: metamorphic testing + mutation testing (PIT)

#### #140 — Human Correction Learning
- **Paper validati**: Christiano (NeurIPS 2017, ~4321), Ouyang (NeurIPS 2022), Fails & Olsen (IUI 2003, ~465 — non ~1000)
- **Correzione**: peso correzione fisso 2.0x → **adattivo [0.5, 5.0]**
- **Raccomandazione**: step-level granularity > task-level

#### #141 — Predictive Cost & Failure Forecaster
- **Notaro et al.**: **venue errato** — ACM TIST, non Computing Surveys. Citazioni ~67, non ~250
- **Correzione critica**: **Holt-Winters → Holt's linear (damped trend)** (nessuna stagionalità)
- **Paper nuovi**: Salfner et al. (CSUR 2010, >500 cit), Feng et al. MarBLR (JAMIA 2022)
- **Raccomandazione**: prediction intervals al posto di threshold 1.2x. MarBLR > BLR per drift

#### #142 — Distributed Tracing Correlator
- **W3C Trace Context**: anno è **2020**, non 2021
- **Sambasivan et al.**: **CMU tech report 2014**, non HotNets 2016. Citare SoCC 2016 version
- **Paper nuovi**: OTel GenAI Semantic Conventions (2025), AG2 OTel Tracing (2026)
- **Raccomandazione**: Micrometer Observation API corretta per Spring Boot 3.x

#### #143 — Failure Pattern Predictor
- **Notaro et al.**: stessa correzione venue di #141 (TIST, non CSUR)
- **Correzione critica**: **Aho-Corasick è sbagliato** (exact substring, non subsequence). Alternativa: NFA skip semantics (SASE, SIGMOD 2008) o pointer array O(n×k)
- **Paper nuovi**: CM-SPADE (PAKDD 2014, 8x più veloce di PrefixSpan)

#### #144 — Multi-Instance Plan Router
- **Citazioni corrette**: Karger ~3230 (non ~5000), DeCandia ~4607 (non ~6000)
- **Ketama**: OK + layerare **bounded-load** (Mirrokni et al. 2018)
- **Correzione**: **Redis pub/sub → Redis Streams** (delivery garantito). MULTI/EXEC per atomicità vnodes

#### #145 — Hierarchical Sub-Plan
- **Citazioni corrette**: Erol ~711 (non ~1000), Nau/SHOP2 ~1148 (non ~2000)
- **Georgievski venue**: *Artificial Intelligence* (Elsevier), non "AI Review"
- **Depth limit 3**: confermato (Temporal, Cadence, Airflow convergono su 2-3)
- **Raccomandazione**: ParentClosePolicy, fan-out limit, budget reservation con watermark

#### #146 — Plan Integrity Verifier
- **Paper validati**: Schneider (TISSEC 2000, ~866 S2), Leucker & Schallhart (JLAP 2009, ~872 S2)
- **Raccomandazione**: Kahn's algorithm O(V+E) per cycle detection. #146 e #38 complementari
- **Correzione**: force-FAILED deve essere transizione valida da qualsiasi stato. Gestione concorrenza necessaria

#### Correzioni cumulative Fase 16

| # | Cosa | Da | A |
|---|------|----|---|
| 138 | Krebs venue | ICSEA | CLOSER 2012 |
| 141 | Notaro venue | ACM Computing Surveys | ACM TIST |
| 141 | Notaro citazioni | ~250 | ~67 |
| 141 | Metodo forecasting | Holt-Winters | Holt's linear (damped trend) |
| 142 | W3C Trace Context anno | 2021 | 2020 |
| 142 | Sambasivan venue | HotNets 2016 | CMU-PDL-14-102 (2014) / SoCC 2016 |
| 143 | Notaro venue | ACM Computing Surveys | ACM TIST (stessa correzione) |
| 143 | Online matching | Aho-Corasick | NFA skip semantics / pointer array |
| 144 | Karger citazioni | ~5000 | ~3230 (S2) |
| 144 | DeCandia citazioni | ~6000 | ~4607 (S2) |
| 144 | Plan forwarding | Redis pub/sub | Redis Streams (delivery garantito) |
| 145 | Erol citazioni | ~1000 | ~711 |
| 145 | Nau/SHOP2 citazioni | ~2000 | ~1148 |
| 145 | Georgievski venue | AI Review | *Artificial Intelligence* (Elsevier) |

#### Cross-connessioni inter-fase Fase 16

| Connessione | Da → A | Tipo |
|-------------|--------|------|
| Notaro et al. (TIST) | #141 ↔ #143 | Stesso paper, stessa correzione venue |
| Salfner et al. (CSUR 2010) | #141 ↔ #143 | Paper chiave condiviso per failure prediction |
| MarBLR drift detection | #141 → #136 (Bayesian Surprise) | Model drift alimenta surprise detection |
| Redis Streams forwarding | #144 → #142 | Trace context propagation nel forwarding |
| Fan-out limit + depth limit | #145 → #146 (I1) | Entrambi prevengono esplosione strutturale |
| Self-Healing Router (arXiv:2603.01548) | #130 ↔ #146 | Edge-reweighting vs break-weakest |
| OTel GenAI conventions | #142 → #145 | Sub-plan span hierarchy = agent span hierarchy |

Report completi: `docs/research/{injection-detector-137,tenant-isolation-138,test-framework-139,human-correction-140,predictive-forecaster-141,distributed-tracing-142,failure-pattern-143,multi-instance-router-144,hierarchical-subplan-145,plan-integrity-146}.md`

---

### Ordine implementazione Fase 17

```
Fase 17a (foundations, 7.5g):              #147 → #148 → #152
Fase 17b (interaction, 4.5g):             #150 → #155
Fase 17c (intelligence, 5.0g):            #151 → #149
Fase 17d (safety & discovery, 7.0g):      #153 → #154 → #156
```

### Riepilogo Fase 17 — Worker Autonomy & Interactive Intelligence (#147-#156)

| # | Titolo | Service | Sforzo | Valore | Tier |
|---|--------|---------|--------|--------|------|
| 147 | Phased Worker Execution | `WorkerPhaseOrchestrator` | 2.5g | Alto | 0 |
| 148 | Worker Workspace Isolation | `WorkerWorkspaceManager` | 3.0g | Alto | 0 |
| 149 | Parallel Tool Orchestration | `ParallelToolCallingManager` | 2.5g | Alto | 0 |
| 150 | Mid-Execution Human Interaction | `HumanInteractionGateway` | 2.5g | Alto | 0 |
| 151 | Persistent Worker Memory | `WorkerEpisodicMemory` | 2.5g | Alto | 0 |
| 152 | Project Constraint Injection | `ProjectConstraintManager` | 2.0g | Alto | 0 |
| 153 | Information Flow Guard | `InformationFlowGuard` | 2.5g | Alto | 0 |
| 154 | Automated Validation Pipeline | `ValidationPipelineService` | 2.5g | Alto | 1 |
| 155 | Worker Progress Estimation | `WorkerProgressTracker` | 2.0g | Medio-Alto | 1 |
| 156 | Dynamic Tool Discovery | `DynamicToolRegistry` | 2.0g | Medio-Alto | 1 |
|   |     | **Totale Fase 17** | | **24.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§86-§95)

Claude Code patterns coperti: P2, P3, P5, P7, P8, P10, P14, P15, P18, P21, P22, P26, P28 (13/17 gap chiusi)

### Sintesi ricerca accademica Fase 17 (S26, 2026-03-15)

10 report Template F completati. 37 riferimenti validati, 1 fabbricato, ~60 paper aggiuntivi identificati.

#### Per-item summaries

**#147** — SWE-Agent: **claim "tool filtering per phase" ERRATO** (ACI statico). Hard tool allowlist per fase = contributo novel. Paper chiave: AFlow (ICLR 2025 Oral), MCP-Zero.

**#148** — 4 riferimenti validati. Git worktree: no T1-T3, solo practitioner. Paper chiave: FIDES (Microsoft), MAGIS (NeurIPS 2024).

**#149** — **Lea: cit 549 (non 1400)**. Paper chiave: **LLMCompiler (ICML 2024, 3.7x speedup)**, W&D (~3 tool paralleli ottimali).

**#150** — **Settles: confusione libro 2012 (~695) vs survey 2009 (~6564)**. Paper chiave: **KnowNo (CoRL 2023 Best Paper)**, HULA (ICSE SEIP 2025).

**#151** — **Lin: cit 1698 (non 3500)**. **Park: cit 3003 (sottostimate)**. Paper chiave: **CER (ACL 2025)**, MemGPT, CoALA.

**#152** — **Hoare: cit 1727 (non 6500, sovrastima 3.8x)**. Paper chiave: **Wink (Microsoft, 90% singolo intervento)**, AgentIF (NeurIPS 2025).

**#153** — **Gruschka (arXiv:2311.11438): FABBRICATA** (= fisica nucleare). Sostituto: Basak (ESEM 2023). Paper chiave: **FIDES (100% block injection)**.

**#154** — **Rothermel: cit 815 (non 1700)**. Ralph-Loop validato da **LLMLOOP (ICSME 2025, +9.2%)**. MuTAP: +28%.

**#155** — **Little: cit 2556 (non 6500)**. EVM per AI agent: territorio inesplorato. Paper chiave: **BRIDGE IRT**, **AgentBoard (NeurIPS 2024)**.

**#156** — **Papazoglou: cit 1184 (non 3800)**. Paper chiave: **MCP-Zero (architettura identica)**, **Tool2Vec (+27%)**, **ToolRet (ACL 2025)**.

#### Citazione fabbricata

| # | Paper | Problema | Sostituto |
|---|-------|----------|-----------|
| 153 | Gruschka et al. (arXiv:2311.11438) | arXiv ID = paper fisica nucleare | Basak et al. (ESEM 2023, arXiv:2307.00714) |

#### Correzioni algoritmiche

| # | Claim | Correzione |
|---|-------|------------|
| 147 | SWE-Agent "tool filtering per phase" | ACI statico; filtering per fase = contributo novel |
| 150 | Settles 2012 ~6500 cit | ~6500 = survey 2009; libro 2012 = ~695 |

#### Tabella correzioni citazioni (S2 vs claim, solo delta significativi)

| Paper | Claim | S2 | Delta | Item |
|-------|-------|----|-------|------|
| Lea | ~1400 | 549 | **-61%** | 149 |
| Settles (book) | ~6500 | 695 | **-89%** | 150 |
| Lin | ~3500 | 1,698 | -51% | 151 |
| Park | ~2000 | 3,003 | **+50%** | 151 |
| Hoare | ~6500 | 1,727 | **-73%** | 152 |
| Rothermel | ~1700 | 815 | -52% | 154 |
| Little | ~6500 | 2,556 | **-61%** | 155 |
| Boehm | ~3800 | 1,937 | -49% | 155 |
| Papazoglou | ~3800 | 1,184 | **-69%** | 156 |
| Toolformer | ~1500 | 2,625 | **+75%** | 156 |

21/25 sovrastimati (media -37%). 3 sottostimati (Anderson, Park, Toolformer).

#### Paper chiave scoperti (T1 top-10)

| Paper | Venue | Item | Perché |
|-------|-------|------|--------|
| LLMCompiler (Kim) | ICML 2024 | 149 | DAG planning, 3.7x speedup |
| KnowNo (Ren) | CoRL 2023 Best | 150 | Conformal prediction "when to ask" |
| HULA | ICSE SEIP 2025 | 150 | Coding agents in produzione (Atlassian) |
| CER (Liu) | ACL 2025 | 151 | Ponte Lin 1992 → LLM agents |
| AgentIF (Qi) | NeurIPS 2025 | 152 | GPT-4o 87→58.5 su vincoli agentic |
| LLMLOOP (Ravi) | ICSME 2025 | 154 | Stesso pattern pipeline (+9.2%) |
| Meta ACH | FSE 2025 | 154 | 10K classi, 93.4% fault detection |
| AgentBoard (Ma) | NeurIPS 2024 | 155 | Progress Rate ≈ SPI |
| AnyTool (Du) | ICML 2024 | 156 | Retrieval gerarchico 16K API |
| ToolRet | ACL 2025 | 156 | Retriever generici inadeguati |

#### Cross-connessioni dalla ricerca

| Connessione | Implicazione |
|-------------|-------------|
| FIDES ≈ #153 | Design quasi identico — differenziare su secret scanning + reversibilità |
| MCP-Zero ≈ #156 | Architettura quasi identica — validazione empirica |
| LLMLOOP ≈ #154 | Stesso feedback loop — validazione indipendente |
| Tool2Vec → #156 | Description-based subottimale; usage-driven +27% |
| Wink → #152 | 90% con singolo intervento — valida rate-limiting |
| BRIDGE → #155 | IRT più rigoroso del k-NN |
| MuTAP → #154 | Surviving mutants +28% test quality |

Report: `docs/research/{phased-execution-147,workspace-isolation-148,parallel-tools-149,human-interaction-150,persistent-memory-151,project-constraints-152,information-flow-153,validation-pipeline-154,progress-estimation-155,tool-discovery-156}.md`

---


### Ordine implementazione Fase 18

```
Fase 18a (collaboration, 8.0g):              #157 → #165 → #158
Fase 18b (production feedback, 4.5g):        #159 → #162
Fase 18c (economics, 7.0g):                  #160 → #161 → #164
Fase 18d (resilience, 4.5g):                 #163 → #166
```

### Riepilogo Fase 18 — Production Intelligence & Collaborative Coordination (#157-#166)

| # | Titolo | Service | Sforzo | Valore | Tier |
|---|--------|---------|--------|--------|------|
| 157 | Shared Workspace Blackboard | `SharedBlackboardService` | 2.5g | Alto | 0 |
| 158 | Worker Negotiation Protocol | `WorkerNegotiationService` | 3.0g | Alto | 0 |
| 159 | Production Feedback Collector | `ProductionFeedbackService` | 2.5g | Alto | 0 |
| 160 | Cost Accounting & Budget Controller | `PlanCostAccountingService` | 2.0g | Alto | 0 |
| 161 | Adaptive Pipeline Configurator | `PipelineConfiguratorService` | 2.5g | Alto | 1 |
| 162 | Worker Self-Assessment | `WorkerSelfAssessmentService` | 2.0g | Alto | 0 |
| 163 | Conflict Resolution Arbiter | `ConflictResolutionArbiterService` | 2.5g | Alto | 1 |
| 164 | Canary Execution Strategy | `CanaryExecutionService` | 2.5g | Medio-Alto | 1 |
| 165 | Collaborative Code Understanding | `SharedCodeModelService` | 2.5g | Alto | 0 |
| 166 | Pipeline Degradation Manager | `DegradationManagerService` | 2.0g | Medio-Alto | 1 |
|   |     | **Totale Fase 18** | | **24.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§96-§105)

Claude Code patterns coperti (cumulativo Fasi 17-18): P2, P3, P5, P7, P8, P10, P14, P15, P18, P21, P22, P24, P26, P28 (14/17 gap chiusi)


### Sintesi ricerca accademica Fase 18 (S27, 2026-03-15)

10 report Template F completati. ~40 riferimenti validati, 0 fabbricati, ~80 paper aggiuntivi identificati.

#### Per-item summaries

**#157** — Blackboard: **Corkill venue ERRATA** (AI Expert, non AI Magazine). **Hayes-Roth cit ~144 (non ~1100, 8x)**. **Cosine < 0.3 per contradiction: FONDAMENTALMENTE SBAGLIATO** — embedding non distinguono contraddizioni da affermazioni fedeli. Usare NLI cross-encoder. Paper chiave: "The Semantic Illusion" (arXiv:2512.15068), LLM-Blackboard (arXiv:2510.01285).

**#158** — Negotiation: **Jennings: anno 2004→1998, venue AAMAS conf→journal**. **Smith cit ~4300 (non ~5800)**. CNP over-engineered per questo caso — letteratura converge su pre-assegnazione (MetaGPT SOP) + negoziazione solo per conflitti residui. GP tiebreaker: novel, nessun precedente.

**#159** — Production Feedback: 4 riferimenti reali. Kim/Bass sono **libri T7**, non letteratura accademica. Shapley per file attribution: **giustificato ma overkill** — approccio gerarchico: git blame pesato (default) + Shapley (escalation per 3+ contributor). Temporal decay 30g: parametrizzazione arbitraria. Paper chiave: **RUDDER (NeurIPS 2019)**, Data Shapley (ICML 2019), **SHARP (arXiv 2026)**.

**#160** — Cost Accounting: **FrugalGPT cit ~160 (non ~350, 2x)**. **Maelstrom cit ~13 (non ~150, 11x)** + rilevanza dubbia. **RouteLLM: ICLR 2025 (non solo arXiv)**. Paper chiave: **BATS (Google, arXiv:2511.17006)** — budget-aware agent scaling, modello diretto per budget controller.

**#161** — Pipeline Config: 4 riferimenti T1 tutti validi. **Auto-sklearn cit ~1728 (non ~4500)** — conflazione NeurIPS+book. **GP-UCB subottimale per 20 dim** — BOHB o SMAC superiori per mixed-type parameters. Soglia 10 piani: fragile senza warm-starting. Paper chiave: **ARTEMIS (arXiv:2512.09108)**, DSPy MIPROv2.

**#162** — Self-Assessment: 5 riferimenti tutti validi. **Kadavath: T2 arXiv (non peer-reviewed)**. **Platt scaling: non SOTA per LLM** — confidenze LLM clusterizzano vicino a 1.0. Sostituire con **temperature scaling adattivo** (Thermometer ICML 2024). **ECE ha patologie note** (binning sensitivity) — usare ACE o Brier Score come target. Sycophancy-calibration link **validato** (arXiv:2509.21305). Paper chiave: **Thermometer (ICML 2024)**, UQ Survey (ACM CS 2024).

**#163** — Conflict Arbiter: **ChatDev autore: Qian, non Tian**. **Tessier cit ~23 (non ~200, 10x)**, data 2000 non 2001. **ChatDev cit ~464 (non ~700)**. **Cosine < 0.3: stessa critica di #157** — usare NLI. BDI: valido come ispirazione architetturale, non implementazione diretta. Priority hierarchy ben fondata (Brewka & Eiter). Paper chiave: **ABBEL (arXiv:2512.20111)**, "Learning to Negotiate" (arXiv:2603.10476).

**#164** — Canary Execution: **Johari venue ERRATA** — non NeurIPS 2017, ma arXiv 2015 / **Operations Research 2022**. **Schermann venue ERRATA** — non JSS ma **IEEE Software 2018**. SPRT: ottimalità campionaria valida ma "5 piani consecutivi" statisticamente fragile — usare **mSPRT** o binomial test su finestra mobile. Hash routing: documentare partitioned ramps.

**#165** — Code Understanding: **CodexGraph autore: Liu, non Zhang**. Garcia cit ~54 (non ~600). Campo in rapida evoluzione 2024-2026. AGE appropriato per unified stack (Cypher compatibile con CodexGraph). Scout pattern validato (Willison 2025, blackboard classico). Paper chiave: **LocAgent (ACL 2025)**, **CGM (NeurIPS 2025)**, LogicLens (arXiv 2026).

**#166** — Degradation Manager: 4 riferimenti tutti validi. Circuit breaker per LLM: pratica standard ma non sufficiente — serve layered approach (retry→fallback→circuit breaker). Threshold "5 in 30s" nell'ordine corretto ma **rate-based > count-based** (Resilience4j). Paper chiave: **Sun et al. (SC'25, LLM non intrinsecamente resilienti)**, Portkey AI Gateway.

#### Correzione critica cross-item

| Problema | Item | Azione |
|----------|------|--------|
| Cosine distance per contradiction detection | #157, #163 | **Sostituire con NLI cross-encoder** (DeBERTa-v3-large-mnli) o approccio ibrido (cosine per topic + NLI per relazione logica) |

#### Correzioni algoritmiche

| # | Claim | Correzione |
|---|-------|------------|
| 157 | Cosine < 0.3 per contraddizioni | NLI cross-encoder. Embedding non catturano relazioni logiche |
| 158 | CNP puro per negoziazione | Ibrido: pre-assegnazione + negoziazione residui. FCFS declassare a fallback |
| 159 | Shapley per ogni file | Gerarchico: git blame (default) + Shapley (3+ contributor, alta severità) |
| 159 | Exponential decay 30g | Configurabile; considerare hyperbolic discounting |
| 160 | FrugalGPT cascade per codice | Serve quality gate basato su test execution, non confidence |
| 161 | GP-UCB per 20 parametri | BOHB/SMAC superiori per mixed-type high-dim |
| 162 | Platt scaling per calibrazione | Temperature scaling (Guo 2017) o Adaptive TS (EMNLP 2024) |
| 162 | ECE come metrica primaria | ACE o Brier Score come target; ECE come dashboard |
| 163 | BDI come implementazione | Solo ispirazione architetturale; usare "context reconciliation" |
| 164 | 5 piani consecutivi per rollback | mSPRT o binomial test su finestra mobile |
| 164 | SPRT per piccoli campioni | Considerare Bayesian con Beta prior per campioni < 100 |
| 166 | 5 failures in 30s (count-based) | Rate-based (50% su sliding window 10 chiamate) |

#### Tabella correzioni citazioni (S2 vs claim, solo delta significativi)

| Paper | Claim | Verificato | Delta | Item |
|-------|-------|------------|-------|------|
| Hayes-Roth | ~1100 | ~144 | **-87%** | 157 |
| Corkill | ~450 | ~767 | **+70%** | 157 |
| Smith | ~5800 | ~4300 | -26% | 158 |
| Jennings | ~3500 | ~2341 | -33% | 158 |
| FrugalGPT | ~350 | ~160 | **-54%** | 160 |
| Maelstrom | ~150 | ~13 | **-91%** | 160 |
| Auto-sklearn | ~4500 | ~1728 | **-62%** | 161 |
| Tessier | ~200 | ~23 | **-89%** | 163 |
| ChatDev | ~700 | ~464 | -34% | 163 |
| Garcia | ~600 | ~54 | **-91%** | 165 |

9/10 sovrastimati (media -52%). 1 sottostimato (Corkill, +70%).

#### Correzioni venue/autore

| Paper | Errore | Correzione | Item |
|-------|--------|------------|------|
| Corkill | AI Magazine | **AI Expert** | 157 |
| Jennings | AAMAS 2004 | **AAMAS journal 1998** | 158 |
| ChatDev | Tian et al. | **Qian** et al. | 163 |
| CodexGraph | Zhang et al. | **Liu** et al. | 165 |
| Johari | NeurIPS 2017 | **arXiv 2015 / Operations Research 2022** | 164 |
| Schermann | JSS 2018 | **IEEE Software 2018** | 164 |
| Tessier | 2001 | **2000** | 163 |
| RouteLLM | arXiv 2024 | **ICLR 2025** | 160 |

#### Paper chiave scoperti (T1 top-10)

| Paper | Venue | Item | Perché |
|-------|-------|------|--------|
| "The Semantic Illusion" | arXiv:2512.15068 | 157,163 | Distrugge approccio cosine per contradiction |
| BATS (Google) | arXiv:2511.17006 | 160 | Budget-aware agent scaling, modello diretto |
| ARTEMIS | arXiv:2512.09108 | 161 | Auto-config agent pipeline, 36.9% token reduction |
| Thermometer (Shen) | ICML 2024 | 162 | Task-adaptive temp scaling, supera Platt |
| RUDDER | NeurIPS 2019 | 159 | Return decomposition per delayed rewards |
| LocAgent | ACL 2025 | 165 | 92.7% file-level localization, graph-guided |
| CGM | NeurIPS 2025 | 165 | Graph structure in LLM attention, 43% SWE-bench |
| SHARP | arXiv:2602.08335 | 159 | Shapley credit gerarchico per multi-agent |
| Sun et al. | SC'25 | 166 | LLM non intrinsecamente resilienti ai fault |
| "Sycophancy Is Not One Thing" | arXiv:2509.21305 | 162 | Sycophancy separabile in latent space |

#### Cross-connessioni dalla ricerca

| Connessione | Implicazione |
|-------------|-------------|
| Cosine→NLI (#157,#163) | Entrambi necessitano NLI cross-encoder — componente condiviso |
| MetaGPT SOP → #158 | Pre-assegnazione strutturale > negoziazione runtime |
| RUDDER → #159 | Redistribuzione reward > discounting temporale |
| Thermometer → #162 | Temperature scaling adattivo sostituisce Platt |
| BATS → #160 | Budget tracker unificato: token + tool consumption |
| ARTEMIS → #161 | Evolutionary search valida per agent config |
| mSPRT → #164 | Johari è già tra i riferimenti — usare mSPRT, non SPRT |
| Layered resilience → #166 | retry→fallback→circuit breaker (Portkey pattern) |
| NLI + Blackboard (#157) ↔ Conflict Arbiter (#163) | Stesso componente NLI per contradiction detection |
| Bradley-Terry ↔ calibrazione (#162) | Stessa struttura logistic regression (Platt ≡ BT) |

Report: `docs/research/{blackboard-157,negotiation-158,production-feedback-159,cost-accounting-160,pipeline-config-161,conflict-arbiter-163,canary-execution-164,code-understanding-165,degradation-166}.md` + `docs/design-validation/item-162-worker-self-assessment.md`

---

## Fase 19 — Code Quality Intelligence & Defensive Autonomy (#167-#176)

**Tema**: I worker producono codice senza valutazione strutturale della qualità: nessun secret scanning sugli output, nessun assessment di reversibilità, nessun tracking di complessità o debito tecnico, nessuna analisi doc-code consistency. Fase 19 chiude tutti gli 8 P-gap rimasti completamente scoperti e trasforma il framework da "sistema che funziona" a "sistema che produce codice di cui un maintainer umano si fida".

### Ordine implementazione Fase 19

```
Fase 19a (security & context, 6.5g):     #167 → #168 → #169
Fase 19b (execution intelligence, 4.5g): #170 → #171
Fase 19c (code quality, 7.0g):           #175 → #172 → #173
Fase 19d (debt & tools, 5.0g):           #174 → #176
```

### Riepilogo Fase 19 — Code Quality Intelligence & Defensive Autonomy (#167-#176)

| # | Titolo | Service | Sforzo | Valore | Tier |
|---|--------|---------|--------|--------|------|
| 167 | Output Secret Scanner | `SecretScannerService` | 2.0g | Alto | 0 |
| 168 | Reversibility Guard | `ReversibilityGuardService` | 2.5g | Alto | 0 |
| 169 | Project Instructions Injector | `ProjectInstructionsService` | 2.0g | Alto | 0 |
| 170 | Context Reminder Enricher | `ContextReminderEnricher` | 2.0g | Alto | 0 |
| 171 | Structured Progress Tracker | `StructuredProgressTracker` | 2.5g | Alto | 0 |
| 172 | Code Simplification Worker | `CodeSimplificationService` | 3.0g | Alto | 1 |
| 173 | Comment & Documentation Analyzer | `CommentAnalyzerService` | 2.0g | Medio-Alto | 1 |
| 174 | Technical Debt Estimator | `TechDebtEstimatorService` | 2.5g | Alto | 1 |
| 175 | Structural Complexity Gate | `ComplexityGateService` | 2.0g | Alto | 0 |
| 176 | Deferred Tool Loading Manager | `DeferredToolLoaderService` | 2.5g | Medio-Alto | 1 |
|   |     | **Totale Fase 19** | | **23.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§106-§115)

Claude Code patterns chiusi (Fase 19): P2, P5, P7, P14, P15, P19, P20, P26 (8 nuovi)
Cumulativo Fasi 17-19: 22/28 pattern coperti (✅ o 🔧). Residui: P16, P18 (bloccati #25), P23 (N/A)

### Sintesi ricerca accademica Fase 19 (S28, 2026-03-15)

10 report Template F completati. ~30 riferimenti validati, 4 fabbricati, ~60 paper aggiuntivi identificati.

#### Per-item summaries

**#167** — Secret Scanner: **Tutti e 4 i paper con errori**. Meli cit ~121 (non ~250). **Saha: titolo e venue completamente sbagliati** — "Secrets in Source Code" su IEEE COMSNETS 2020, non "Reducing FP" su CCS Workshop. **SecretBench: autori Basak non Feng**, anno 2023 non 2022, "818 repository" non "818 categorie". **"SecretLint" NON ESISTE**. Paper chiave: **Niu et al. TOSEM 2024** (LLM emettono credenziali memorizzate dal training data — giustificazione diretta). Architettura: **2 livelli** (regex+entropy → LLM validation).

**#168** — Reversibility Guard: **2 paper fabbricati** (Zhang FSE 2024, Bohme ICSE 2024 — **non esistono**). Weiser: anno **1981** (non 1984), cit ~1201 (non ~6000, 5x). Xia "Agentless" confermato (~289 cit). Sostituti: **ToolEmu** (Ruan et al., ICLR 2024, ~234 cit — safety per agenti con tool use), **AgentSpec** (ICSE 2026, runtime enforcement). Change impact analysis > program slicing per blast radius.

**#169** — Project Instructions: GPT-3 **sottostimato** (~55282, non ~30000). FLAN **sottostimato** (~4800, non ~3000). AgentIF confermato NeurIPS 2025 D&B. **"Model spec" è di OpenAI, non Anthropic** (Anthropic ha "Claude's Constitution"). Paper chiave: **Gloaguen et al. "Evaluating AGENTS.md"** (ETH Zurich 2026) — primo studio empirico: AGENTS.md seguiti ma **-3% success rate** per overhead. Implicazione: servizio parsimonioso. Gerarchia project>directory>file: **territorio inesplorato**, analogo a specificità CSS.

**#170** — Context Reminders: Liu "Lost in the Middle" anno **2023** (non 2024), cit **~3078** (non ~800, 4x). **"TALE" è una conflazione** con paper di visione — sostituire con **LongAttn (Wu et al., ACL Findings 2025)**. "Retrieval Head" autori **Wu** (non Xu), venue **ICLR 2025** (non ICML 2024). Pirolli cit ~3481 (non ~2500). Paper chiave: **Dongre et al. "Drift No More?" (arXiv 2025)** — formalizza context drift come processo stocastico. **Bui "Building AI Coding Agents" (arXiv 2026)** — implementazione production-grade di system reminders. Raccomandazione: trigger **event-driven** (non periodici), reminders come `role: user`.

**#171** — Progress Tracker: AgentBoard titolo corretto "Multi-turn LLM Agents", cit **~152** (non ~50, 3x). SWE-bench cit **~1667** (non ~300, 5.5x). MAgICoRe EMNLP 2025 confermato. Devin è prodotto commerciale (T7), non paper. Paper chiave: **TheAgentCompany** (Xu et al. 2024, partial completion score con checkpoint), **AOP** (Li et al., ICLR 2025, 3 principi decomposizione subtask), **SHIELDA** (Zhou et al. 2025, 36 tipi eccezione per workflow agentici). Progress rate AgentBoard ≡ Earned Value di EVM — #171 e #155 devono condividere ontologia.

**#172** — Code Simplifier: **Silva et al. ICSE 2025 NON ESISTE** — paper fabbricato. Alomar venue **IEEE TSE** (non EMSE), anno 2023/2024 (non 2022). McCabe cit **~5755-6000** (non ~10000). Finding critico: **Dristi & Dwyer (arXiv 2026) — 19-35% dei refactoring LLM alterano la semantica**, ~21% sfuggono ai test. Design deve includere behavior preservation verification. SWE-Refactor (arXiv 2026) come benchmark.

**#173** — Comment Analyzer: **Wen et al. ASE 2022 NON ESISTE** — paper fabbricato. Louis venue **ICSE-NIER 2020** (4 pagine, ~8 cit), non ICSE main. Tan cit ~320 (non ~200). **DeBERTa NLI sub-ottimale per coppie commento-codice** — codice fuori distribuzione. Raccomandazione: preprocessare codice in riassunto semantico NL prima del cross-encoder, oppure usare modelli code-aware (CodeBERT, CodeT5+). Paper chiave: **Zhang FSE 2024** (LLM + program analysis per CCI), **LLMCup** (arXiv 2025, +49-117% accuracy).

**#174** — Tech Debt Estimator: Tutti confermati. Cunningham ~1035 cit. Kruchten ~656 (non ~500). **Lenarduzzi venue: JSS 2021** (non EMSE). Avgeriou ~492 (non ~200, +146%). SQALE **non più best-in-class** — campo spostato verso ibridi behavioral+structural. Paper chiave: **ACE** (Tornhill & Borg, ICSE 2025) — loop automatizzato detection→refactoring→test→PR. **Manifesto 2025** (arXiv:2505.13009) successore del Dagstuhl 2016.

**#175** — Complexity Gate: **Tutti e 4 confermati**, correzioni minori. McCabe cit ~5755 (non ~10000). Campbell anno **2017** (non 2018). Spadini ~172 (non ~200). Sharma ~335 (non ~400). Soglia CC ≤ 15 **ben supportata** (NIST, NASA). Codice LLM ha CC mediano basso (1-3) — gate scatterà solo su outlier. Tree-sitter scelta allineata allo stato dell'arte. Raccomandazione: cognitive complexity come metrica di primo livello.

**#176** — Deferred Tool Loading: Tutti e 4 reali. **Gorilla è NeurIPS 2024** (non solo arXiv 2023). Citazioni non verificabili precisamente (S2 rate-limited). Paper chiave: **EASYTOOL** (NAACL 2025, ~153 cit) — compressione descrizioni tool, combinato con deferred loading dà riduzione **>95%**. **COLT** (CIKM 2024) — retrieval per completezza. Analogia con **Working Set Model di Denning** (1968, memoria virtuale).

#### Correzione critica cross-item

| Problema | Item | Azione |
|----------|------|--------|
| DeBERTa NLI su codice fuori distribuzione | #173 (anche #157, #163) | **Preprocessare codice → riassunto NL** prima del cross-encoder, oppure usare modelli code-aware (CodeT5+) |
| LLM refactoring altera semantica (19-35%) | #172 | **Behavior preservation verification obbligatoria** (differential testing o property-based testing) |

#### Correzioni algoritmiche

| # | Claim | Correzione |
|---|-------|------------|
| 167 | Regex + entropy sufficienti | **2 livelli**: regex+entropy (layer 1) + LLM validation (layer 2) |
| 168 | Program slicing per blast radius | **Change impact analysis** > program slicing per sistemi agentici |
| 169 | Iniettare tutte le instructions | **Parsimonioso**: AGENTS.md riduce success rate del ~3% per overhead |
| 170 | Trigger periodico (ogni N tool calls) | **Event-driven** con guardrail counters. Reminders come `role: user` |
| 171 | Progress solo osservato | Distinguere **progress claimed vs verified**. Validare decomposizione con principi AOP |
| 172 | 5 pass senza verifica | **Behavior preservation obbligatoria**: 19-35% refactoring LLM altera semantica |
| 173 | DeBERTa NLI diretto su codice | **Preprocessare codice → NL** o usare CodeT5+ |
| 174 | SQALE-inspired | SQALE non best-in-class — **ibrido behavioral+structural** (CodeScene/ACE pattern) |
| 175 | Solo CC come metrica primaria | **Cognitive complexity** come metrica di primo livello accanto a CC |
| 176 | Solo deferred loading | **EASYTOOL compressione + deferred loading** per riduzione >95% |

#### Tabella correzioni citazioni (S2 vs claim, solo delta significativi)

| Paper | Claim | Verificato | Delta | Item |
|-------|-------|------------|-------|------|
| Meli | ~250 | ~121 | **-52%** | 167 |
| Weiser | ~6000 | ~1201 | **-80%** | 168 |
| GPT-3 (Brown) | ~30000 | ~55282 | **+84%** | 169 |
| FLAN (Wei) | ~3000 | ~4800 | **+60%** | 169 |
| Liu "Lost in Middle" | ~800 | ~3078 | **+285%** | 170 |
| AgentBoard (Ma) | ~50 | ~152 | **+204%** | 171 |
| SWE-bench (Jimenez) | ~300 | ~1667 | **+456%** | 171 |
| McCabe | ~10000 | ~5755 | **-42%** | 172,175 |
| Tan "iComment" | ~200 | ~320 | +60% | 173 |
| Avgeriou | ~200 | ~492 | **+146%** | 174 |

5/10 sovrastimati, 5/10 sottostimati. Pattern inverso rispetto a Fase 18 (dove 9/10 erano sovrastimati).

#### Correzioni venue/autore

| Paper | Errore | Correzione | Item |
|-------|--------|------------|------|
| Saha et al. | Titolo + venue completamente sbagliati | **"Secrets in Source Code"**, IEEE COMSNETS 2020 | 167 |
| SecretBench | Autori Feng et al. | **Basak** et al., 2023 non 2022 | 167 |
| Weiser | Anno 1984 | **1981** | 168 |
| "Model spec" | Anthropic | **OpenAI** | 169 |
| Liu "Lost in Middle" | Anno 2024 | **2023** | 170 |
| "TALE" He et al. | ACL 2025 | **Conflazione** — sostituire con LongAttn (Wu et al., ACL Findings 2025) | 170 |
| "Retrieval Head" | Xu et al., ICML 2024 | **Wu** et al., **ICLR 2025** | 170 |
| AgentBoard | "Multi-step Reasoning Agent" | "**Multi-turn LLM Agents**" | 171 |
| Alomar | EMSE 2022 | **IEEE TSE 2023/2024** | 172 |
| Louis | ICSE 2020 | **ICSE-NIER 2020** (4 pagine) | 173 |
| Lenarduzzi | EMSE 2021 | **JSS 2021** | 174 |
| Campbell | 2018 | **2017** (white paper); conferenza TechDebt 2018 | 175 |
| Gorilla (Patil) | arXiv 2023 | **NeurIPS 2024** | 176 |

#### Paper fabbricati (4 su ~30 validati = 13%)

| Paper | Item | Sostituto |
|-------|------|-----------|
| Zhang et al. "Irreversible Decisions" FSE 2024 | 168 | ToolEmu (Ruan et al., ICLR 2024) |
| Bohme et al. "Assurance Cases" ICSE 2024 | 168 | AgentSpec (ICSE 2026) |
| Silva et al. "LLM Refactoring" ICSE 2025 | 172 | Dristi & Dwyer (arXiv 2026) |
| Wen et al. "Stale Comments" ASE 2022 | 173 | Zhang (FSE 2024, LLM+CCI) |
| "SecretLint" arXiv 2023 | 167 | Basak et al. "Comparative Study" (ESEM 2023) |

#### Paper chiave scoperti (T1 top-10)

| Paper | Venue | Item | Perché |
|-------|-------|------|--------|
| Niu et al. "Your Code Secret Belongs to Me" | TOSEM 2024 | 167 | LLM emettono credenziali memorizzate — giustificazione empirica diretta |
| ToolEmu (Ruan et al.) | ICLR 2024 | 168 | Safety evaluation per agenti con tool use, ~234 cit |
| Gloaguen "Evaluating AGENTS.md" | ETH Zurich 2026 | 169 | Primo studio empirico su file AGENTS.md/CLAUDE.md |
| Dongre "Drift No More?" | arXiv 2025 | 170 | Formalizza context drift come processo stocastico |
| Bui "Building AI Coding Agents" | arXiv 2026 | 170 | Production-grade system reminders con 8 event detectors |
| TheAgentCompany (Xu et al.) | 2024 | 171 | Partial completion score con checkpoint intermedi |
| Dristi & Dwyer | arXiv 2026 | 172 | 19-35% refactoring LLM altera semantica |
| ACE (Tornhill & Borg) | ICSE 2025 | 174 | Loop automatizzato detection→refactoring→test→PR |
| EASYTOOL | NAACL 2025 | 176 | Compressione descrizioni tool, ~153 cit |
| AOP (Li et al.) | ICLR 2025 | 171 | 3 principi decomposizione subtask |

#### Cross-connessioni dalla ricerca

| Connessione | Implicazione |
|-------------|-------------|
| NLI preprocessing (#173) → (#157, #163) | Stesso problema: DeBERTa fuori distribuzione su codice. Tutti e 3 necessitano preprocessing code→NL |
| Behavior preservation (#172) → (#154) | ValidationPipelineService deve verificare equivalenza semantica post-semplificazione |
| AGENTS.md overhead (#169) → (#107) | ContextWindowManager deve budgetare instructions con parsimonia |
| Event-driven reminders (#170) → (#136) | BayesianSurpriseDetector come trigger, non timer periodico |
| Progress ≡ EVM (#171) → (#155) | Condividere ontologia metriche tra reporting attivo e stima passiva |
| EASYTOOL (#176) + deferred → (#107) | Riduzione >95% token budget tool descriptions |
| 2-layer secret scan (#167) → Ollama | LLM validation come layer 2 sfrutta infrastruttura Ollama esistente |
| Working Set (#176) → Denning 1968 | Replacement policies OS applicabili a tool eviction |

Report: `docs/research/{secret-scanner-167,reversibility-guard-168,project-instructions-169,context-reminders-170,progress-tracker-171,code-simplifier-172,comment-analyzer-173,tech-debt-174,complexity-gate-175,deferred-tools-176}.md`

---

## Fase 20 — Execution Grounding & Adaptive Evolution (#177-#186)

**Tema**: Il framework non può verificare ciò che produce — nessuna compilazione, test, o git safety. Ogni piano parte da zero senza transfer learning. Fase 20 ground il framework nella realtà (bash → git safety → compile-test-fix) e lo rende adattivo (cross-plan learning, self-improvement, project lifecycle). Sblocca finalmente #25 (mcp-bash-tool), P16 e P18.

### Ordine implementazione Fase 20

```
Fase 20a (execution grounding, 7.5g):     #177 → #185 → #186
Fase 20b (intelligence & learning, 6.0g): #178 → #182
Fase 20c (lifecycle & integration, 6.5g): #184 → #179 → #180
Fase 20d (measurement & output, 4.5g):    #181 → #183
```

### Riepilogo Fase 20 — Execution Grounding & Adaptive Evolution (#177-#186)

| # | Titolo | Service | Sforzo | Valore | Tier |
|---|--------|---------|--------|--------|------|
| 177 | Execution Runtime Orchestrator | `ExecutionRuntimeOrchestrator` | 3.0g | Molto alto | 0 |
| 178 | Cross-Plan Knowledge Transfer Engine | `CrossPlanKnowledgeEngine` | 3.0g | Alto | 1 |
| 179 | Conversational Requirements Elicitor | `RequirementsElicitorService` | 2.5g | Alto | 0 |
| 180 | Multi-Plan Project Lifecycle Manager | `ProjectLifecycleManager` | 3.5g | Alto | 1 |
| 181 | Longitudinal Effectiveness Benchmark | `EffectivenessBenchmarkService` | 2.5g | Alto | 0 |
| 182 | Self-Improving Prompt & Strategy Optimizer | `SelfImprovingOptimizerService` | 3.0g | Alto | 1 |
| 183 | Architectural Visualization Generator | `VisualizationGeneratorService` | 2.0g | Medio-Alto | 1 |
| 184 | External System Integration Hub | `ExternalIntegrationHubService` | 3.0g | Alto | 0 |
| 185 | Git Safety Protocol Enforcer | `GitSafetyProtocolService` | 2.0g | Alto | 0 |
| 186 | Compile-Test-Fix Verification Loop | `CompileTestFixLoopService` | 2.5g | Molto alto | 0 |
|   |     | **Totale Fase 20** | | **27.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§116-§125)

Claude Code patterns sbloccati (Fase 20): P16 (Git safety), P18 (Test running)
Cumulativo Fasi 17-20: 24/28 pattern coperti. Residui: 🔧 parziali + P23 (N/A)

### Sintesi ricerca accademica Fase 20 (S29, 2026-03-15)

10 item validati con ricerca accademica (Template F). ~30 paper verificati, ~60 nuovi paper trovati. Report completi in `docs/research/`.

#### Per-item summaries

**#177 Execution Runtime** — ChatDev sottostimato (~601 vs ~464). Bui singolo autore. OpenHands (ICLR 2025, ~436 cit) reference per sandbox. Docker + cgroups v2 raccomandato. Nessun sistema implementa approval gates per comandi distruttivi — gap che #177+#185 colmano.

**#178 Cross-Plan Knowledge** — Voyager **TMLR 2024** (non NeurIPS 2023 Spotlight). CoPS (Yang 2024) alternativa superiore al GP posterior (regret bounds, scala meglio). ACE (Zhang 2025, ~72 cit) "evolving playbooks".

**#179 Requirements Elicitor** — KnowNo confermato CoRL 2023 Oral (non Best Paper). CP applicabile con caveat (exchangeability). ReqElicitGym (Feb 2026): primo benchmark. Bashir (ICSME 2025): LLM +20.2% su requisiti ambigui.

**#180 Project Lifecycle** — Erol sottostimato (~892 vs ~711). Georgievski sovrastimato (~168 vs ~335). Li AOP: ICLR **2024**. SagaLLM (VLDB 2025): Saga pattern per lifecycle. Nessun paper formalizza "sprint autonomi" — contributo originale.

**#181 Effectiveness Benchmark** — BOCPD gonfiate (~850 vs ~1800), arXiv-only, i.i.d. assumption. xbench (Chen 2025, ~59 cit): quasi identico a #181.

**#182 Self-Improving Optimizer** — Promptbreeder: **ICML 2024**. 5 rischi: mode collapse, reward hacking, prompt bloat, safety drift, regression. Godel Agent (ACL 2025) come ispirazione.

**#183 Visualization Generator** — Brown C4 anno **2012**. **Spinellis: correzione grave** (titolo/anno/cit errati). MermaidSeqBench: LLM hanno capability gaps. Patidar (2025): C4 automatico + validazione ibrida.

**#184 External Integration** — Hohpe EIP gonfiate (~1412 vs ~3200). HULA titolo corretto. Ghaleb (MSR 2026): agenti toccano CI/CD solo 3.25%. Architettura #184 originale.

**#185 Git Safety** — AgentSpec autori **Wang, Poskitt, Sun**. "Predicting Faults" autori **Kim, Zimmermann** ICSE **2007**. Nessun paper su git safety per AI agents. Solo **17% defense rate** senza safety layer (Shan 2026).

**#186 Compile-Test-Fix** — Self-Refine sottostimato (~2961 vs ~1000). 2-3 iterazioni ottimali. Test feedback > compiler di **9.4-18.9 punti**. RepairAgent (ICSE 2025, ~262 cit) reference.

#### Correzioni citazioni

| Paper | Claimed | Verified (S2) | Delta |
|-------|---------|---------------|-------|
| Self-Refine (NeurIPS 2023) | ~1000 | ~2961 | +196% |
| Erol HTN (AAAI 1994) | ~711 | ~892 | +25% |
| ChatDev (ACL 2024) | ~464 | ~601 | +29% |
| BOCPD (arXiv 2007) | ~1800 | ~850 | -53% |
| Georgievski (AI 2015) | ~335 | ~168 | -50% |
| Hohpe EIP (2003) | ~3200 | ~1412 | -56% |
| Spinellis (IEEE Sw 2003) | ~150 | ~39 | -74% |

#### Correzioni venue/autore (11)

Voyager: TMLR 2024 (non NeurIPS 2023). Li AOP: ICLR 2024 (non 2025). Promptbreeder: ICML 2024 (non arXiv-only). Brown C4: 2012 (non 2018). Spinellis: "On the Declarative Specification of Models" IEEE Software 2003 (non 2008). AgentSpec: Wang, Poskitt, Sun (non Zhou). Predicting Faults: Kim, Zimmermann ICSE 2007 (non Bird ICSE 2004). Bui: singolo autore. AgentReuse/HULA/LLMLOOP: titoli e track corretti.

#### Paper fabbricati: nessuno (Fase 19 ne aveva 4)

#### Top-10 paper scoperti

OpenHands (ICLR 2025, ~436 cit, #177), SagaLLM (VLDB 2025, #180), CoPS (arXiv 2024, #178), ACE (arXiv 2025, ~72 cit, #178), RepairAgent (ICSE 2025, ~262 cit, #186), xbench (arXiv 2025, ~59 cit, #181), ReqElicitGym (arXiv 2026, #179), Ghaleb CI/CD (MSR 2026, #184), AgentSpec (ICSE 2026, ~27 cit, #185), FeedbackEval (arXiv 2025, #186).

#### Correzioni algoritmiche (10)

1. GP posterior #178: O(n^3), usare CoPS o sparse GP
2. BOCPD #181: i.i.d. assumption, usare GP-BOCPD o pre-differencing
3. CP #179: exchangeability non garantita, usare adaptive CP
4. Pattern matching git #185: insufficiente, serve analisi contestuale
5. Iterations #186: 2-3 ottimali, mixed feedback massimizza
6. Prompt evolution #182: diversity penalty + multi-objective + rotazione giudice
7. C4 #183: validation post-generazione obbligatoria
8. Webhook #184: idempotency keys obbligatorie
9. Saga #180: compensating actions, adottare SagaLLM
10. Error patterns #178: triple contestuali (contesto, errore, correzione, confidence)

#### Cross-connessioni dalla ricerca (8)

OpenHands SDK → #177+#185 | SagaLLM → #180+#143 | CoPS → #178+#11 | AgentSpec → #185+#168 | FeedbackEval → #186+#127 | xbench → #181+#111 | ReqElicitGym → #179 | Pillar Security → #185+#153

Report: `docs/research/execution-runtime-177.md`, `cross-plan-knowledge-178.md`, `requirements-elicitor-179.md`, `project-lifecycle-180.md`, `benchmark-visualization-181-183.md`, `self-improvement-182.md`, `external-integration-184.md`, `git-safety-185.md`, `compile-test-fix-186.md`

---


# Execution Sandbox (#44)

---

## #44 — Execution Sandbox Containerizzato (Framework + Worker Isolation)

> Design completo: [docs/architecture/execution-sandbox-design.md](docs/architecture/execution-sandbox-design.md)

Dual-layer: framework containerizzato (Docker Compose) + sandbox effimeri per compilazione/test.
8 immagini sandbox pre-built (Java, COBOL, Go, Python, Node, Rust, C++, .NET).
Difesa in profondita': 8 livelli (network none, read-only, non-root, memory limit, timeout, volume :ro, no socket, seccomp).

**Sforzo**: 3g. **Dipendenze**: #29 (Worker Lifecycle), #25 (mcp-bash-tool).

---
---


# Pattern Claude Code → Agent Framework

> Dettagli completi: [docs/architecture/claude-code-patterns.md](docs/architecture/claude-code-patterns.md)

28 pattern mappati (6 categorie). Priorità CRITICA: P1 Auto-compacting (B17). ALTA: P2, P7, P22, P28.


---
---

# Observability & Tracking — Riepilogo

Tutti i 6 gap di observability sono stati implementati (G1-G6 ✅):

| Gap | Sessione | Stato |
|-----|----------|-------|
| G1 Conversation history | S14 | ✅ `DispatchAttempt.conversationLog` JSONB |
| G2 Decision reasoning | S13 | ✅ `Provenance.reasoning` field |
| G3 File modification tracking | S14 | ✅ `FileModification` entity + hook |
| G4 Prometheus/Micrometer | S12 | ✅ Actuator + metriche applicative |
| G5 Persistent audit | S13 | ✅ `AuditEvent` entity DB |
| G6 Worker event pipeline | S14 | ✅ Header propagation + MCP audit |

Dettagli design originali: PIANO_HISTORY.md (sezione Observability).

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
- Feature implementate: #1-#4, #6, #11-#18, #19-#49, #50-#116 (motivazioni architetturali complete)
- RAG Pipeline: piano dettagliato 3 sessioni, struttura modulo, config YAML, fonti
- Bug fix: B1-B7, B9-B11, B13-B19 (sessioni S8, S12, S14)
- Session log: S1-S23
- Design reference: B17 L2 CompactingToolCallingManager, tool mapping B13, Observability G1-G6
- Riepilogo file per sessione (tabella riassuntiva)

_→ Dettagli in PIANO_HISTORY.md_
