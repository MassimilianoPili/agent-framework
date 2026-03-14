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

## Parzialmente implementati (8 item — codice base, estensioni da fare)

| # | Item | Cosa manca |
|---|------|------------|
| 7 | Context Cache (TASK_MANAGER) | TASK_MANAGER worker (bloccato da tracker-mcp) |
| 8 | DAG + Mermaid | Miglioramenti UI |
| 9 | Hierarchical Plans | Estensioni previste |
| 33 | Token Economics | Tuning credit formula, dashboard Grafana |
| 35 | Context Quality Scoring | Test unitari, tuning pesi |
| 36 | Worker Pool Sizing | Dashboard Grafana, live monitoring |
| 37 | Adaptive Token Budget (PID) | Tuning PID con dati reali |
| 38 | State Machine Verification (LTL) | Integrazione CI/CD |
| 40 | Shapley Value | Tuning K samples, dashboard Grafana |

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

`ContextQualityService`, file relevance + entropy proxy, 4° reward source (0.15). S15. Commit: `75c484b`.

## #36 ✅ — Worker Pool Sizing (Queueing Theory)

`QueueAnalyzer` (Erlang C + Little's Law + CPM), `QueuingCapacityPlanner` (M/G/1 P-K), `CriticalPathCalculator`, `TropicalScheduler`. 15 test. S17.

## #37 ✅ — Adaptive Token Budget (PID Controller)

`PidBudgetController` (PID in-memory per planId×workerType), integrazione OrchestrationService. 10 test. S16. Commit: `4ee1d76`.

## #38 ✅ — State Machine Verification (LTL)

`StateMachineVerifier` (BFS model checker, product state space), `@AllowedViolation`. 10 test. S15.

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
