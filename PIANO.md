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

### 12. Serendipita' nel Context Manager

**Problema**: il context-manager trova solo file semanticamente simili (RAG search coseno + BM25 via
`HybridSearchService`). Non scopre file "sorprendenti" che storicamente si sono rivelati utili
per task simili ma che la ricerca semantica non intercetta.

**Soluzione**: GP residual per file discovery — `residual(file, task) = actual_usefulness - predicted_usefulness`.
Se `residual >> 0`: il file era inaspettatamente utile → pattern latente da sfruttare.

**Perche' non e' random exploration**: usa il residual positivo del GP, non randomicita'.
E' **informed surprise** — file che hanno *sorpreso* il modello in task passati simili.
Un file `SecurityConfig.java` inaspettatamente utile per un task "build CRUD API" suggerisce
che quel progetto ha vincoli di sicurezza impliciti.

**Perche' nel context-manager e non nel RAG_MANAGER**: CM opera *prima* del RAG nel DAG dei task.
La serendipita' arricchisce il contesto iniziale; il RAG puo' poi cercare anche sui file sorpresa.
Layering: serendipita' (storica) → RAG (semantica). Se fosse nel RAG, il CM non ne beneficerebbe.

**Come si compone con RRF**: `HybridSearchService` fa RRF con k=60 su 2 ranked list
(coseno + BM25). La serendipita' aggiunge una terza list (residual storico).
RRF esteso a 3 sorgenti: `score(d) = 1/(k+r_cosine) + 1/(k+r_bm25) + 1/(k+r_serendipity)`.

**Dati necessari**: collegamento `(task_embedding, suggested_files[], final_reward)` —
ricavabile join-ando `plan_items` (reward) con risultati CONTEXT_MANAGER (lista file).
Richiede `task_outcomes` di #11.

**File nuovi**: `shared/gp-engine/SerendipityAnalyzer.java`.
Modifica `context-manager-worker`, estensione `HybridSearchService` per terza sorgente RRF.

**Sforzo**: 2g. **Dipendenze**: #11 (GP engine + task_outcomes). **Dati minimi**: ~100 task con file context.

---

### 13. Council Taste Profile

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

### 15. Active Learning per Token Budget

**Problema**: `TokenBudgetService.checkBudget()` (riga 53) confronta `used >= limit` statico.
Task facili sprecano budget (limite troppo alto), task difficili lo esauriscono (limite troppo basso).

**Soluzione**: modulare il budget con le predizioni GP — `mu` per la qualita' attesa,
`sigma^2` per l'incertezza.

**Formula**: `dynamic_budget = base × (1 + alpha × sigma^2) × clip(mu, 0.3, 1.0)`.
- `sigma^2` alto (incertezza) → piu' budget (active learning: lascia esplorare)
- `mu` basso (qualita' attesa bassa) → riduce budget (non sprecare su task destinato a fallire)
- `clip(mu, 0.3, 1.0)` impedisce di azzerare il budget

**Perche' `sigma^2` come modulatore e non solo `mu`**: solo `mu` = "se predico fallimento,
riduci budget" (miope). Aggiungere `sigma^2` = "se incerto, dai piu' budget" (active learning).
L'incertezza e' informazione: un task incerto potrebbe rivelare pattern nuovi per il GP.

**Perche' in `checkBudget` e non in `recordUsage`**: `checkBudget` opera *prima* del dispatch
(riga 465 di `OrchestrationService`). `recordUsage` opera *dopo* (riga 280). Il budget dinamico
deve modulare il limite *prima* che il task parta.

**Enforcement mode invariato**: il budget dinamico modifica solo il limite numerico, non la policy
(`FAIL_FAST`/`NO_NEW_DISPATCH`/`SOFT_LIMIT`). Single Responsibility Principle: la policy dice
*cosa fare* quando il budget e' superato, il GP dice *quanto* budget dare.

**File mod**: `TokenBudgetService.java` (`checkBudget` accetta `Optional<GPPrediction>`).

**Sforzo**: 1g. **Dipendenze**: #11 (GP engine). **Dati minimi**: ~50 task con budget history.

---

### 16. Ralph-Loop (Quality Gate Feedback Loop) ✅ → [PIANO_HISTORY.md]

---

### 17. SDK Scaffold Worker

**Problema**: creare un progetto Agent SDK (Claude Code SDK, Python o TypeScript) richiede
boilerplate ripetitivo: setup environment, installazione SDK, configurazione, gitignore,
entry point, tool definitions, deployment config.

**Soluzione**: worker con `workerType: AI_TASK` e `workerProfile: sdk-scaffold` che genera
un progetto completo e verificato, guidato da skill files.

**Perche' workerType `AI_TASK` e non un nuovo type**: lo scaffolding non ha semantica
diversa da un generic AI task. Creare un WorkerType dedicato inquinerebbe l'enum
(gia' 15 valori). Un `workerProfile: sdk-scaffold` basta per il routing nel
`WorkerProfileRegistry`, coerente con il pattern esistente.

**Perche' skill files e non codice Java**: lo scaffolding e' un task LLM-driven.
Il worker generato dall'agent-compiler esegue le istruzioni della skill.
Le skill sono modificabili senza rebuild — aggiornare un template e' un file edit.
Pattern identico a `be-java`, `fe-react`, etc.

**Perche' ralph-loop inline nella skill**: il verifier (type check + syntax) e'
parte delle istruzioni della skill, non un servizio separato. Il worker esegue il loop
autonomamente come parte dell'`execute()`. Il ralph-loop orchestratore (#16)
e' a livello piano; qui e' a livello task (complementari, non duplicati).

**File nuovi**: `agents/manifests/sdk-scaffold.agent.yml`, `skills/sdk-scaffold/SKILL.md`,
`skills/sdk-scaffold/python-template.md`, `skills/sdk-scaffold/typescript-template.md`.

**Sforzo**: 0.5g. **Dipendenze**: nessuna.

---

### 18. ADR-005: GP + Serendipita' — Motivazioni Architetturali

**Problema**: le scelte architetturali per #11-#15 hanno motivazioni profonde
che non entrano in una roadmap item. Servono in un ADR dedicato con alternative
scartate e ancoraggi al codice.

**Perche' ADR e non commenti nel codice**: le motivazioni riguardano *perche'*
una scelta e' stata fatta, non *come* funziona il codice. I commenti nel codice
spiegano il come; gli ADR spiegano il perche' e le alternative scartate.

**Contenuto**: per ogni feature #11-#15, il *perche'* di ogni scelta ancorato
a righe specifiche del codice esistente. Include: schema `task_outcomes`,
formula `processScore` in `RewardComputationService`, flusso dispatch in
`OrchestrationService` (righe 449-540), Bradley-Terry in `EloRatingService`,
delta filter in `PreferencePairGenerator` (riga 113).

**File**: `docs/adr/ADR-005-gp-serendipity-evolution.md`.

**Sforzo**: 0.5g. **Dipendenze**: nessuna (documenta #11-#15, non le implementa).

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
#23 Enrichment Pipeline ─────► #24L2 TOOL_MANAGER (usa enrichment per generare policy)
#23 ─────────────────────────► (sblocca RAG S1-S3, HOOK_MANAGER, CONTEXT_MANAGER)
#26L1 Cost tracking ─────────► #26L2 Auto-split (necessita costi reali + GP prediction)
#26L2 Auto-split ────────────► (dipende da #11 GP, #20 modelHint)
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
| 16 | Ralph-Loop (Quality Gate Feedback) | 1.5g | Alto | — |
| 17 | SDK Scaffold Worker | 0.5g | Basso | — |
| 11 | GP Worker Selection ✅ | 3g | Molto alto | pgvector, Ollama |
| 14 | DPO con GP Residual ✅ | 1g | Alto | #11 |
| 12 | Serendipita' Context Manager | 2g | Alto | #11 |
| 13 | Council Taste Profile | 2g | Medio | #11 |
| 15 | Active Token Budget | 1g | Medio | #11 |
| 18 | ADR-005 (GP Motivazioni) | 0.5g | Medio | — |
| **S8** | **Fix bug critici + resilienza (B1-B7, B9, B14-B16)** | **4g** | **Critico** | #27 (per S8-L) |
| **#27** | **Centralizzazione nomi tool (ToolNames registry)** | **0.5g** | **Alto** | — (prerequisito) |
| **B13** | **Fix nomi tool nei 22 manifest** | **0.5g** | **Alto** | #27 |
| **B14** | **Fix Mustache template write-tool-names** | **0.5g** | **Alto** | #27 |
| **B15** | **Project path dinamico in ownsPaths** | **0.5g** | **Alto** | — |
| **B16** | **HOOK_MANAGER schema con nomi Claude Code** | **0.5g** | **Alto** | #27 |
| **B17** | **Context overflow da `fs_read` senza limiti** | **1.5g** | **Critico** | — |
| **23** | **Enrichment Pipeline Activation** | **2g** | **Molto alto** (sblocca S1-S3) | — |
| **25** | **mcp-bash-tool + mcp-python-tool** | **1.5g** | **Alto** | — |
| **24** | **Tool configurabili (Livello 1: toolHints)** | **1g** | **Alto** | B13, #25 |
| 19 | Retry manuale TO_DISPATCH | 1g | Alto | S8 |
| 20 | Modello LLM per task | 2g | Alto | — |
| 22 | Orchestrator singleton (leader election) | 1.5g | Medio-alto | S8-H |
| 26L1 | Cost tracking per task | 0.5g | Alto | — |
| 24L2 | Tool configurabili (Livello 2: TOOL_MANAGER) | 1g | Medio | #23 |
| 26L2 | Auto-split task costosi | 1.5g | Medio | #26L1, #11, #20 |
| 21 | Redis topic splitting | 1g | Basso | — |
| **28** | **Monitoring Dashboard UI (real-time)** | **3g** | **Alto** | #5 (SSE), G1, G4, G6 |
| **29** | **Worker Lifecycle Management (kill, singleton, JVM-per-type)** | **3.5g** | **Alto** | B17 (per fase 1 in-process) |
| **B18** | **No singleton per task — double processing** | — | **Alto** | Risolto da #29 (ConcurrentHashMap) |
| **B19** | **DispatchAttempt orfani — non-unique result** | **0.5g** | **Alto** | Query fix ✅ applicato, cleanup strutturale futuro |

---
---

# Bug noti e fix pianificati

Bug emersi dall'esplorazione del codice e dall'uso reale del framework.

## Catena cascante critica

3 bug collegati — causa root dei problemi riscontrati: task bloccato DISPATCHED, risultato ignorato, piano mai completo.

| # | Bug | Severita' | File / Righe | Root cause | Fix proposto |
|---|-----|-----------|-------------|------------|-------------|
| B1 | **ACK prima di commit** — `AgentResultConsumer.handleMessage()` fa `ack.reject()` (XACK + DLQ) anche se la transazione di `onTaskCompleted` ha fatto rollback. Il messaggio e' perso, il task resta DISPATCHED. | CRITICAL | `AgentResultConsumer.java:58-71`, `RedisStreamAcknowledgment.java:44-58` | ACK avviene fuori dal boundary transazionale Spring | Spostare XACK dentro la transazione, oppure usare pattern "ACK after commit" con `TransactionSynchronization.afterCommit()`. Se rollback → non ACK → Redis ri-consegna il messaggio. |
| B2 | **Idempotency guard incompleto** — `onTaskCompleted()` guarda solo DONE/FAILED. Se un'eccezione causa rollback (reward computation, GP update), l'item resta DISPATCHED ma il messaggio e' in DLQ → task bloccato per sempre. | CRITICAL | `OrchestrationService.java:199-203` | Guard non copre DISPATCHED; side-effect (reward, GP, serendipity) nel path critico senza isolamento | Separare side-effect non critici in `@TransactionalEventListener(AFTER_COMMIT)`. La transizione DISPATCHED→DONE deve avere successo anche se i side-effect falliscono. |
| B3 | **Piano mai completo** — conseguenza di B1+B2: `findActiveByPlanId` trova item DISPATCHED (non terminale) → `checkPlanCompletion` esce subito → piano resta RUNNING per sempre | CRITICAL | `OrchestrationService.java:696-710`, `PlanItemRepository.java:22-23` | Cascata da B1+B2 | Risolvere B1+B2. Aggiungere "stale task detector" schedulato che marca come FAILED task DISPATCHED da piu' di X minuti senza risultato. |

## Bug aggiuntivi

| # | Bug | Severita' | File / Righe | Fix proposto |
|---|-----|-----------|-------------|-------------|
| B4 | **AutoRetryScheduler transazione unica** — `@Transactional` sull'intero loop. Se un retry fallisce, rollback di TUTTI. `nextRetryAt` resettato per tutti → retry immediato infinito. | HIGH | `AutoRetryScheduler.java:40-60` | Transaction separata per item (`REQUIRES_NEW`) o try-catch con restore `nextRetryAt` |
| B5 | **Missing context error propagation** — se il CM task creato per fornire contesto fallisce, il task padre resta WAITING per sempre (nessuna propagazione fallimento) | HIGH | `OrchestrationService.java:804-835` | Se CM fallisce → propagare FAILED al task padre. Oppure count retry e fallire dopo N tentativi. |
| B6 | **LazyInitializationException** — `item.getPlan()` con FetchType.LAZY in `onTaskCompleted()` e `retryFailedItem()` | HIGH | `OrchestrationService.java:281,356` | `JOIN FETCH plan` nella query repository |
| B7 | **Race condition child plan completion** — no `@Version`, concurrent `onChildPlanCompleted` puo' sovrascrivere | HIGH | `OrchestrationService.java:975-1011` | Aggiungere `@Version` a PlanItem e Plan entities |
| B8 | **Dependency results "1 vs 3"** — task FE-001 con 3 dipendenze (AI-001/AI-002/AI-003) logga "with 1 dependency results". `buildContextJson()` filtra `item.getDependsOn()` contro `completedResults`. | MEDIUM | `OrchestrationService.java:748-759`, `AgentContextBuilder.java:67-80` | Aggiungere log diagnostico: `log.info("Task {} dependsOn={}, completedKeys={}", ...)`. Verificare che il planner popoli correttamente `dependsOn`. |
| B9 | **Consumer group pending messages (PEL)** — al riavvio dell'orchestratore, `XREADGROUP` con `>` legge solo nuovi messaggi. Messaggi pending (in-flight al crash) non vengono reclamati → risultati persi. | MEDIUM | `RedisStreamListenerContainer.java` | Aggiungere `XAUTOCLAIM` all'avvio per reclamare messaggi pending oltre un idle timeout. |
| B10 | **Compensation task semantics** — riapertura plan COMPLETED/FAILED ambigua | MEDIUM | `OrchestrationService.java:365-430` | Piano di compensazione separato o flag speciale |
| B11 | **Token budget check ordering** — GP prediction calcolata prima del budget check (costo non contabilizzato) | LOW | `OrchestrationService.java:629-650` | Spostare budget check prima di GP prediction |
| B12 | **Optional service null checks** — `gpTaskOutcomeService` potrebbe essere null in path non protetti | LOW | `OrchestrationService.java:124-126, 549-554` | Usare Optional consistently |
| B13 | **Tool names errati in tutti i 22 manifest** — i manifest `.agent.yml` elencano tool con nomi Claude Code (`Read`, `Write`, `Edit`, `Bash`, `Glob`, `Grep`) che non esistono nel registro Spring AI MCP. I worker non trovano i tool e partono con 0/N tools. | HIGH | `agents/manifests/*.agent.yml` (tutti i 22 file) | Correggere i nomi con la mappatura MCP reale. `Edit` e `Grep` non hanno equivalente MCP → rimuovere. Aggiungere sempre `fs_list`. |
| B14 | **Mustache template hardcoda write-tool-names errati** — `application.yml.mustache` (righe 30-32) genera `write-tool-names: [Write, Edit]` hardcoded, ignorando i nomi MCP reali. `PolicyProperties.java` ha il default corretto (`["Write", "Edit", "fs_write"]`), ma il template lo sovrascrive. Risultato: `PathOwnershipEnforcer` non controlla mai `fs_write` perche' non e' nella lista dei write-tool-names → un worker puo' scrivere ovunque con `fs_write` senza violazione path ownership. | HIGH | `agent-compiler-maven-plugin/.../templates/application.yml.mustache:30-32`, `PolicyProperties.java:37` | Aggiornare il template Mustache per generare `write-tool-names` dinamicamente dal manifest (includendo `fs_write`, `bash_execute`, etc.) oppure rimuovere la sezione hardcoded e lasciare i default di `PolicyProperties`. |
| B15 | **ownsPaths statici — manca il project path dinamico** — i manifest dichiarano `ownsPaths` statici (`backend/`, `frontend/`, `docs/`, `eval/`). Ma il path del progetto in corso (es. `cps4/`) non viene propagato dall'orchestratore. Un `ai-task` con `ownsPaths: [eval/]` non puo' scrivere in `cps4/` anche se il task lo richiede. L'orchestratore deve almeno propagare il path del piano/progetto come owned path per ogni worker. | HIGH | `agents/manifests/*.agent.yml` (ownsPaths), `AgentTaskProducer.java`, `WorkerTaskConsumer.java`, `PathOwnershipEnforcer.java:49-99` | L'orchestratore aggiunge il project path (dal campo `Plan.projectPath` o dalla spec del piano) ai `ownsPaths` del task al momento del dispatch. Design: `AgentTask.dynamicOwnsPaths` (lista propagata via Redis) + merge con i `ownsPaths` statici dal manifest nel `PathOwnershipEnforcer`. |
| B16 | **HOOK_MANAGER schema con nomi Claude Code** — `hook-manager.agent.yml:34` mostra esempio `["Read", "Write", "Edit", "Glob", "Grep", "Bash"]`. Il HOOK_MANAGER worker (LLM) genera HookPolicy con questi nomi. `PolicyEnforcingToolCallback` al livello 2 blocca i tool MCP reali (`fs_read`, `fs_write`) perche' non sono nell'allowlist della policy. Il fallback statico `HookPolicyResolver.DEFAULT_TOOL_ALLOWLISTS` ha i nomi corretti — ma viene usato solo se il HOOK_MANAGER non ha generato policy. | HIGH | `hook-manager.agent.yml:34`, `PolicyEnforcingToolCallback.java:113-125`, `HookPolicyResolver.java:35-46` | Aggiornare lo schema in `hook-manager.agent.yml` con nomi MCP reali. Centralizzare i nomi in un'unica source of truth (vedi #27). |
| B18 | **No singleton per task — double processing** — se un worker crasha durante l'esecuzione, il messaggio resta nel Redis PEL (Pending Entries List). Quando il worker viene riavviato (Docker `restart: unless-stopped`), il messaggio pendente puo' essere reclamato (XCLAIM) e riprocessato. Ma se nel frattempo l'`AutoRetryScheduler` ha gia' ri-dispatchato il task, **due worker processano lo stesso task in parallelo**. Risultato: scritture duplicate, conflitti su file, token sprecati, risultato incoerente. Nessun lock distribuito impedisce il double processing. | HIGH | `WorkerTaskConsumer.java` (nessun lock pre-process), `AutoRetryScheduler.java` (ri-dispatcha senza check PEL), `RedisStreamListenerContainer.java` (nessun XCLAIM) | `TaskLockService` — lock distribuito Redis (`SETNX task:{taskKey}:lock {consumerId} EX 300`). Il worker acquisisce il lock prima di processare. Se lock gia' acquisito → NACK, il messaggio torna in coda. TTL 5 min come safety net (worker crash). Heartbeat ogni 60s per task lunghi. |
| B17 | **Context overflow da `fs_read` senza limiti** — un worker (es. AI-001 audit) che chiama `fs_read` su file grandi (HTML ~80KB, CSS ~80KB, JS ~30KB) accumula migliaia di token per ogni chiamata. Con un sito di 11 pagine (es. CPS), 48 tool call portano il prompt a 208K token, superando il limite di 200K del modello. Il LLM non ha visibilita' sul budget token residuo e continua a leggere file finche' non viene troncato. Nessun meccanismo di protezione: ne' `maxTokens` nel manifest, ne' auto-compacting nel worker SDK, ne' `limit` parametro nel tool `fs_read`. | CRITICAL | `WorkerChatClient.java` (nessun token counting pre-call), `fs_read` tool (nessun parametro `limit`), manifests `*.agent.yml` (nessun campo `maxTokens`) | Fix a 3 livelli: **(1)** campo `maxTokens` nel manifest → il worker SDK lo propaga come `maxOutputTokens` nella chat request (quick fix); **(2)** **auto-compacting nel worker SDK** — prima di ogni tool call, stimare token accumulati (system + messaggi + tool results). Se si supera una soglia (es. 75% del context window), compattare la storia: sostituire i risultati vecchi di tool call con un riassunto LLM compresso (un `fs_read` da 80KB → "file X: 1400 righe HTML, struttura header/nav/main/footer, Bootstrap 5" — da ~20K a ~200 token). Il worker continua a lavorare con contesto compresso, senza interrompere il task. Pattern analogo a Claude Code auto-compress; **(3)** parametro `limit` (righe/byte) nel tool `fs_read` — il worker sceglie quanto leggere per file (prevenzione upstream). Livello 2 e' il fix architetturale — il worker non crasha mai, si auto-compatta. |
| B19 | **DispatchAttempt orfani — non-unique result** — `findOpenAttempt(itemId)` usa `Optional<DispatchAttempt>` con query `completedAt IS NULL`, che attende 0 o 1 risultato. Ma ogni retry (`retryFailedItem` → `dispatchReadyItems`) crea un **nuovo** `DispatchAttempt` senza chiudere il precedente. Con 2+ attempt aperti, Spring Data JPA lancia `IncorrectResultSizeDataAccessException`. Cascata: `onTaskCompleted()` chiama `findOpenAttempt` → eccezione → risultato del worker perso → item resta DISPATCHED → piano bloccato (B3). Scenari che creano orfani: rollback transazione in `onTaskCompleted` (B1), worker crash senza risultato + stale detector che marca FAILED senza chiudere l'attempt, race condition su `dispatchReadyItems` concorrenti. | HIGH | `DispatchAttemptRepository.java:18-19` (query single-result), `OrchestrationService.java:206` (chiama `findOpenAttempt`), `OrchestrationService.java:342-364` (`retryFailedItem` non chiude attempt precedente) | **Fix immediato ✅ applicato**: `ORDER BY a.attemptNumber DESC LIMIT 1` nella query — restituisce solo l'attempt piu' recente anche con multipli aperti. **Fix strutturale** (futuro): (1) `closeOrphanedAttempts(itemId)` bulk update in `retryFailedItem` prima del nuovo dispatch; (2) `onTaskCompleted` difensivo — chiudere TUTTI gli attempt aperti (`findByItemIdAndCompletedAtIsNull` + loop) con log warning se >1. |

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

**Stato attuale**: Livello 1 ✅ (corretto dopo B13). Livello 2 ❌ (B16 — HOOK_MANAGER genera nomi Claude Code). Livello 3 ❌ parziale (B14 — `write-tool-names` hardcoded nel template).

**Nomi tool sparsi in 6 file diversi** — necessaria centralizzazione (#27):

| # | File | Nomi usati | Stato |
|---|------|------------|-------|
| 1 | `HookPolicyResolver.java:35-46` | `fs_list, fs_read, fs_write, fs_search` | ✅ Corretto |
| 2 | `PolicyProperties.java:43` | `Write, Edit, fs_write` | ⚠️ Misto |
| 3 | `PathOwnershipEnforcer.java:117` | `Read, fs_read` (hardcoded) | ⚠️ Misto |
| 4 | `hook-manager.agent.yml:34` | `Read, Write, Edit, Glob, Grep, Bash` | ❌ Sbagliato |
| 5 | `application.yml.mustache:30-32` | `Write, Edit` | ❌ Sbagliato |
| 6 | Worker generati (`toolAllowlist()`) | `fs_list, fs_read, fs_write, fs_search` | ✅ Corretto |

---
---

# Sessione 8 — Fix bug critici + resilienza

> **Obiettivo**: risolvere la catena B1-B3 (task bloccato / risultato perso / piano mai completo)
> e i bug HIGH B4-B7. Aggiungere resilienza consumer group Redis.

## S8-A. ACK after commit (B1)

Il fix fondamentale: l'ACK del messaggio Redis deve avvenire SOLO dopo il commit della transazione.

**Approccio**: `TransactionSynchronizationManager.registerSynchronization()` in `AgentResultConsumer`.
Alternativa piu' robusta: rendere `handleMessage` partecipe della transazione Spring, e registrare
l'ACK come `afterCommit` callback. Se rollback → no ACK → Redis ri-consegna.

```java
private void handleMessage(String body, MessageAcknowledgment ack) {
    AgentResult result = objectMapper.readValue(body, AgentResult.class);
    orchestrationService.onTaskCompleted(result);
    // Se siamo qui, la transazione e' committed (no exception)
    ack.complete();
}
```

**File**: `AgentResultConsumer.java`, `RedisStreamListenerContainer.java` (gestire re-delivery)

## S8-B. Side-effect isolation (B2)

Separare i side-effect non critici dalla transizione di stato:
- **Path critico** (nella transazione): `item.transitionTo(DONE/FAILED)` + `planItemRepository.save(item)` + `checkPlanCompletion` + `dispatchReadyItems`
- **After-commit** (via `@TransactionalEventListener(phase=AFTER_COMMIT)`): reward computation, GP update, serendipity collection, hook policy storage, plan event publish

**File**: `OrchestrationService.java` (refactor `onTaskCompleted`), nuovo `TaskCompletedEventHandler.java` per i side-effect

## S8-C. Stale task detector (B3)

`@Scheduled` che cerca task DISPATCHED da piu' di N minuti (configurabile, default 30min) senza risultato.
Azioni: log warning, opzionalmente marca come FAILED con motivo "stale_timeout".

**File**: `StaleTaskDetectorScheduler.java` (NEW), `application.yml` (config timeout)

## S8-D. AutoRetryScheduler fix (B4)

Wrappare ogni retry in transazione separata:

```java
for (PlanItem item : eligible) {
    try {
        retryService.retryInNewTransaction(item.getId());  // @Transactional(REQUIRES_NEW)
    } catch (Exception e) {
        log.error("...");
    }
}
```

**File**: `AutoRetryScheduler.java`, `RetryTransactionService.java` (NEW)

## S8-E. Missing context propagation (B5)

In `onTaskCompleted`, se il task completato e' un CM creato per context retry:
- Se FAILED → trovare il task padre (che dipende da questo CM) → marcare FAILED
- Se DONE → il padre si sblocca normalmente (dipendenza soddisfatta)

**File**: `OrchestrationService.java` (sezione `onTaskCompleted`, gestione CM failure)

## S8-F. LazyInitializationException fix (B6)

Aggiungere `JOIN FETCH` nelle query repository:

```java
@Query("SELECT i FROM PlanItem i JOIN FETCH i.plan WHERE i.id = :itemId")
Optional<PlanItem> findByIdWithPlan(@Param("itemId") UUID itemId);
```

**File**: `PlanItemRepository.java`, `OrchestrationService.java` (usare `findByIdWithPlan`)

## S8-G. Optimistic locking (B7)

Aggiungere `@Version` a PlanItem e Plan:

```java
@Version
private Long version;
```

Gestire `OptimisticLockException` nel handler con retry.

**File**: `PlanItem.java`, `Plan.java`, Flyway V10 (`ALTER TABLE ... ADD COLUMN version BIGINT DEFAULT 0`)

## S8-H. Consumer group resilience (B9)

All'avvio dell'orchestratore, reclamare messaggi pending:

```java
@PostConstruct
public void reclaimPendingMessages() {
    // XAUTOCLAIM agent-results orchestrator-group consumer-id idle-timeout 0-0
    // Processa i messaggi reclamati come normali
}
```

**File**: `AgentResultConsumer.java` (+`reclaimPendingMessages()`), `RedisStreamListenerContainer.java` (+metodo `autoclaim`)

## S8-J. Fix Mustache template write-tool-names (B14)

Il template `application.yml.mustache` genera `write-tool-names: [Write, Edit]` hardcoded.
Due opzioni di fix:

**Opzione A** — Rimuovere la sezione dal template, lasciare i default di `PolicyProperties`:
```yaml
# Rimuovere righe 30-32 dal template, PolicyProperties usa:
# writeToolNames = ["Write", "Edit", "fs_write"]
```

**Opzione B** — Generare dinamicamente dal manifest (preferita):
```yaml
write-tool-names:
  {{#writeToolEntries}}
  - {{name}}
  {{/writeToolEntries}}
```
Con `WorkerGenerator` che popola `writeToolEntries` dai tool con attributo `write: true` nel manifest.

**File**: `application.yml.mustache`, `WorkerGenerator.java` (se opzione B), `PolicyProperties.java` (aggiornare default con tutti i nomi MCP write)

## S8-K. Project path dinamico in ownsPaths (B15)

L'orchestratore deve propagare il path del progetto in corso come owned path per ogni worker.

**Design**:
- `Plan.projectPath` — campo esistente o nuovo (es. `cps4/`, `backend/user-service/`)
- `AgentTask.dynamicOwnsPaths` — nuovo campo (lista propagata via Redis)
- `AgentTaskProducer.buildTask()` — aggiunge `plan.getProjectPath()` ai `dynamicOwnsPaths`
- `WorkerTaskConsumer` — merge `dynamicOwnsPaths` con gli `ownsPaths` statici dal manifest
- `PathOwnershipEnforcer.checkOwnership()` — riceve la lista merged

```java
// In AgentTaskProducer.buildTask():
List<String> dynamicPaths = new ArrayList<>();
if (plan.getProjectPath() != null) {
    dynamicPaths.add(plan.getProjectPath());
}
task.setDynamicOwnsPaths(dynamicPaths);
```

**File**: `AgentTask.java`, `AgentTaskProducer.java`, `WorkerTaskConsumer.java`,
`PathOwnershipEnforcer.java`, `Plan.java` (campo `projectPath` se non esiste)

## S8-L. Centralizzazione nomi tool — ToolNames registry (B13 + B14 + B16)

Invece di fixare B13, B14 e B16 separatamente, centralizzare tutti i nomi tool in un'unica source of truth.

**Design**: classe `ToolNames` nel modulo `worker-sdk` (usata sia dai worker che dall'orchestratore).

```java
public final class ToolNames {
    // Tool MCP filesystem (mcp-filesystem-tools, 5 tool)
    public static final String FS_LIST   = "fs_list";
    public static final String FS_READ   = "fs_read";
    public static final String FS_WRITE  = "fs_write";
    public static final String FS_SEARCH = "fs_search";
    public static final String FS_GREP   = "fs_grep";

    // Categorie filesystem
    public static final List<String> WRITE_TOOLS = List.of(FS_WRITE);
    public static final List<String> READ_TOOLS  = List.of(FS_READ, FS_GREP);
    public static final List<String> ALL_FS_TOOLS = List.of(FS_LIST, FS_READ, FS_WRITE, FS_SEARCH, FS_GREP);
    public static final List<String> READONLY_FS_TOOLS = List.of(FS_LIST, FS_READ, FS_SEARCH, FS_GREP);

    // Prefissi librerie (per discovery dinamica — ~548 tool totali)
    // Ogni libreria usa un prefisso coerente: azure_*, devops_*, ocp_*, jira_*,
    // docker_*, playwright_*, db_*, mongo_*, embeddings_*, graph_*, api_*
    // Il registry ToolNames centralizza solo i tool usati nelle policy di enforcement.
    // I tool specifici di dominio (azure_*, devops_*, etc.) sono gestiti dal manifest allowlist.

    // Tool futuri (dopo #25)
    // public static final String BASH_EXECUTE   = "bash_execute";
    // public static final String PYTHON_EXECUTE = "python_execute";

    public static boolean isWriteTool(String name) {
        return WRITE_TOOLS.contains(name);
    }

    public static boolean isReadTool(String name) {
        return READ_TOOLS.contains(name);
    }
}
```

**Consumatori da aggiornare** (tutti leggono da `ToolNames`):

| File | Attuale | Dopo |
|------|---------|------|
| `HookPolicyResolver.java:35-46` | `List.of("fs_list", "fs_read", ...)` letterali | `ToolNames.ALL_FS_TOOLS`, `ToolNames.READONLY_FS_TOOLS` |
| `PolicyProperties.java:43` | `["Write", "Edit", "fs_write"]` | Default da `ToolNames.WRITE_TOOLS` |
| `PathOwnershipEnforcer.java:117` | `"Read".equals(toolName)` hardcoded | `ToolNames.isReadTool(toolName)` |
| `hook-manager.agent.yml:34` | `["Read", "Write", "Edit", ...]` | `["fs_list", "fs_read", "fs_write", "fs_search", "fs_grep"]` |
| `application.yml.mustache:30-32` | `[Write, Edit]` hardcoded | Rimuovere — delegare a `PolicyProperties` default |

**File**: `ToolNames.java` (NEW in `worker-sdk`), + aggiornamento dei 5 file sopra.

## S8-M. Test attesi (~28)

- `AgentResultConsumerTest`: ACK solo dopo commit (3 test)
- `OrchestrationServiceTest`: side-effect isolation (4 test), stale detection (2 test)
- `AutoRetrySchedulerTest`: transazione per-item (3 test)
- `PlanItem/Plan`: optimistic locking conflict (2 test)
- `RedisStreamListenerContainerTest`: pending message reclaim (3 test)
- `MissingContextPropagationTest`: CM failure → parent failure (3 test)
- `PathOwnershipEnforcerTest`: dynamic ownsPaths merge + fs_write check (3 test)
- `WorkerGeneratorTest`: write-tool-names generation (2 test)
- `ToolNamesTest`: costanti, categorie, `isWriteTool()`, `isReadTool()` (3 test)

---
---

# Roadmap items #19-#26

## #19 — Retry manuale via DB `TO_DISPATCH`

**Problema**: l'unico modo per ritentare un task fallito e' tramite `AutoRetryScheduler` (automatico).
Non c'e' un canale manuale semplice per rimettere task in coda.

**Soluzione**: nuovo stato `TO_DISPATCH` nel DB. Un poller leggero legge periodicamente i task
in `TO_DISPATCH` e li ri-dispatcha. Per ritentare basta un `UPDATE status = 'TO_DISPATCH'`
(via API o diretto DB).

**Design**:
- `ItemStatus.TO_DISPATCH` — nuovo stato nella state machine
- Transizioni ammesse: `FAILED → TO_DISPATCH`, `DONE → TO_DISPATCH` (ralph-loop manuale)
- `DispatchPollerService` — `@Scheduled`, legge `findByStatus(TO_DISPATCH)`, chiama dispatch
- API REST: `POST /api/v1/plans/{id}/items/{itemId}/redispatch` — setta stato TO_DISPATCH
- Complementare ad AutoRetryScheduler (che usa FAILED → WAITING con backoff)

**File**: `ItemStatus.java`, `PlanItem.java`, `PlanItemRepository.java`,
`DispatchPollerService.java` (NEW), `PlanController.java`, `v1.yaml`

**Sforzo**: 1g. **Dipendenze**: S8.

---

## #20 — Decisione modello LLM per task (planner)

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

## #22 — Orchestrator singleton (leader election)

**Problema**: nulla impedisce 2 istanze dell'orchestratore. Con Redis consumer groups, un secondo
orchestratore consuma risultati in parallelo causando conflitti sugli state transitions (race condition
sull'item, sovrascritture, dispatch doppio).

**Soluzione**: leader election via Redis lock per garantire che una sola istanza dell'orchestratore
sia attiva. Pattern: `SET orchestrator:leader {id} NX PX 30000` + refresh periodico.

**Design**:
- `LeaderElectionService` — Redis-based, `@Scheduled` heartbeat (es. ogni 10s, TTL 30s)
- L'istanza non-leader rimane in standby: non consuma messaggi, non dispatcha
- Al crash del leader: TTL scade, lo standby diventa leader (failover ~30s)
- Soft-reboot: il leader rilascia il lock → lo standby prende il controllo → zero downtime

**Beneficio soft-reboot**: l'orchestratore puo' essere riavviato senza perdere messaggi in-flight
(consumer group Redis mantiene i pending, la nuova istanza li reclama con S8-H).

**File**: `LeaderElectionService.java` (NEW), `AgentResultConsumer.java` (condizionale su leader),
`AgentTaskProducer.java` (condizionale), `application.yml` (config)

**Sforzo**: 1.5g. **Dipendenze**: S8-H (consumer group resilience).

---

## #23 — Enrichment Pipeline Activation (CRITICO)

**Problema**: l'intera pipeline di enrichment (CONTEXT_MANAGER → RAG_MANAGER → vectorDB/graphDB)
e' **costruita ma non collegata**. Il planner non genera mai task di enrichment perche':

1. **`plan_tasks.prompt.md` (riga 17-31)** elenca solo 5 tipi standard (CONTRACT, BE, FE, AI_TASK, REVIEW)
   e 3 advisory (COUNCIL_MANAGER, MANAGER, SPECIALIST). **CONTEXT_MANAGER, RAG_MANAGER, SCHEMA_MANAGER,
   TASK_MANAGER, HOOK_MANAGER, AUDIT_MANAGER, EVENT_MANAGER non sono menzionati.**
2. **Nessuna auto-injection** — `PlannerService.decompose()` e `OrchestrationService.createAndStart()`
   non iniettano automaticamente task di enrichment prima del dispatch.
3. **RAG_MANAGER orfano** — il worker Java e' completo (`RagManagerWorker.java`), ma
   **`agents/manifests/rag-manager.agent.yml` non esiste**. Idem per hook-manager, audit-manager.
4. **Solo reattivo** — l'unico meccanismo e' `missing_context` (worker dice "mi manca contesto"
   → CM creato on-the-fly in `handleMissingContext()`, riga 804-838). E' un fallback, non una strategia.

**Conseguenza**: tutto il lavoro RAG (S1-S3: vectorDB, graphDB, hybrid search, reranking, ingestion,
graph services — 100+ test, 3 sessioni complete) e' inutilizzato in produzione.

**Soluzione**: approccio a 2 livelli.

**Livello 1 — Planner-aware (minimo)**: aggiornare `plan_tasks.prompt.md` per includere i manager types.
Il planner generera' CM e RAG_MANAGER come dipendenze dei domain worker quando serve.

```markdown
## Enrichment Worker Types (Pre-requisiti — usare come dipendenze dei domain worker)

| Type | Quando usarlo |
|------|---------------|
| CONTEXT_MANAGER | Sempre come primo task: esplora codebase, produce file rilevanti |
| RAG_MANAGER | Per codebase grandi: ricerca semantica su vectorDB + graphDB |
| SCHEMA_MANAGER | Per task che toccano API/DTO: estrae interfacce e contratti |
| TASK_MANAGER | Con issue tracker: recupera issue snapshot + branch target |
```

**Livello 2 — Auto-injection (robusto)**: l'orchestratore inietta automaticamente task di enrichment
prima del primo dispatch. Configurabile per piano:

```java
// In OrchestrationService, dopo planner.decompose():
if (enrichmentProperties.autoInjectEnabled()) {
    enrichmentInjector.inject(plan);  // Aggiunge CM → RAG_MANAGER → domain workers
}
```

**Design auto-injection**:
- `EnrichmentInjectorService` (NEW) — analizza il piano e aggiunge task manager come dipendenze
- Configurabile: `enrichment.auto-inject: true`, `enrichment.include-rag: true`, `enrichment.include-schema: true`
- Il DAG risultante: `CM-001 → RAG-001 → [BE-001, FE-001, ...]` (CM e RAG come antenati comuni)
- Ogni domain worker riceve i risultati via `dependencyResults` (meccanismo esistente)

**File da creare**: `rag-manager.agent.yml` (manifest mancante), `EnrichmentInjectorService.java` (NEW)
**File da modificare**: `plan_tasks.prompt.md` (aggiungere enrichment types), `OrchestrationService.java`
(chiamare injector dopo decompose), `application.yml` (config enrichment)

**Sforzo**: 2g (Livello 1: 0.5g, Livello 2: 1.5g). **Dipendenze**: nessuna.
**Impatto**: **Molto alto** — sblocca tutto il lavoro RAG/vectorDB/graphDB delle sessioni S1-S3.

---

## #24 — Tool configurabili dal planner + TOOL_MANAGER

**Problema**: i worker domain (BE, FE, AI_TASK) partono con **0 tools abilitati** perche':
1. Il HOOK_MANAGER (che genera HookPolicy con `allowedTools`) non viene mai eseguito (pipeline enrichment disconnessa, vedi #23)
2. Senza HookPolicy nel task, `PolicyEnforcingToolCallback` non sa quali tool permettere
3. Il fallback statico (`config/generated/hooks-config.json`) potrebbe non essere configurato
4. Risultato: il worker puo' solo generare testo (chiamata LLM pura), non interagire col filesystem

**Soluzione a 2 livelli**:

**Livello 1 — Planner specifica `toolHints` per task**:
Il planner, conoscendo la natura del task, indica quali tool servono nel piano generato.

```json
{
  "taskKey": "BE-001",
  "workerType": "BE",
  "title": "Implement REST controller",
  "toolHints": ["fs_read", "fs_write", "fs_search", "fs_list", "bash_execute"],
  "modelHint": "claude-sonnet-4-6"
}
```

**ATTENZIONE (vedi B13)**: i `toolHints` devono usare i **nomi MCP reali**, NON i nomi Claude Code.
La tabella di riferimento:

| Capacita' | Tool MCP | Fonte |
|-----------|----------|-------|
| Leggi file | `fs_read` | mcp-fs-tools (esistente) |
| Scrivi file | `fs_write` | mcp-fs-tools (esistente) |
| Cerca file | `fs_search` | mcp-fs-tools (esistente) |
| Lista directory | `fs_list` | mcp-fs-tools (esistente) |
| Esegui shell | `bash_execute` | **mcp-bash-tool** (da creare, vedi #25) |
| Esegui Python | `python_execute` | **mcp-python-tool** (da creare, vedi #25) |

- `PlanItem.toolHints` — nuovo campo (lista nullable, nomi MCP reali)
- `PlanSchema` — nuovo campo opzionale `toolHints` per task
- `AgentTask.toolHints` — propagato al worker via Redis
- `WorkerChatClientFactory.create()` — se `toolHints` presente, usa come allowlist override
- `planner.agent.md` — linee guida con nomi MCP reali

**Livello 2 — TOOL_MANAGER come enrichment worker**:
Worker dedicato che analizza il task + codebase e genera una `HookPolicy` precisa (come HOOK_MANAGER,
ma per singolo task, non per l'intero piano).

- Complementare a HOOK_MANAGER (che opera su tutto il piano)
- Piu' leggero: usa modello haiku, analizza solo un task
- Puo' essere auto-injected come dipendenza (vedi #23 EnrichmentInjectorService)

**File**: `PlanItem.java`, `PlanSchema.java`, `AgentTask.java`, `WorkerChatClientFactory.java`,
`planner.agent.md`, Flyway (`plan_items.tool_hints`), `agents/manifests/*.agent.yml` (fix nomi B13)

**Sforzo**: 1.5g (Livello 1: 0.5g + fix B13 0.5g, Livello 2: 1g). **Dipendenze**: #25 per `bash_execute`/`python_execute`. #23 per Livello 2.

---

## #25 — mcp-bash-tool + mcp-python-tool (nuove librerie MCP)

**Problema**: i worker domain non hanno modo di eseguire comandi shell o script Python.
Le librerie MCP esistenti coprono filesystem (`mcp-fs-tools`), Docker, database, DevOps, ma
**non c'e' nessun tool per esecuzione di codice**. Senza questi, un worker non puo':
- Compilare/buildare il progetto (`mvn`, `npm`, `gradle`)
- Eseguire test
- Lanciare script di migrazione
- Eseguire data processing/ML tasks

**Soluzione**: due nuove librerie MCP Spring Boot, seguendo lo stesso pattern delle esistenti
(`@ReactiveTool` + `ReactiveToolAutoConfiguration`).

**mcp-bash-tool** (`io.github.massimilianopili:mcp-bash-tool`):

| Tool | Descrizione |
|------|-------------|
| `bash_execute` | Esegue un comando shell con timeout, working directory, env vars |

- Sandboxing: working directory configurabile, timeout (default 60s), env vars whitelist
- Output: stdout + stderr + exit code (troncato a N chars per non esplodere il contesto LLM)
- Sicurezza: lista comandi bloccati configurabile (`rm -rf /`, `shutdown`, etc.)
- Pattern: `ProcessBuilder` con redirect stderr → stdout, `waitFor(timeout)`
- `@ConditionalOnProperty(prefix = "mcp.bash", name = "enabled", havingValue = "true")`

**mcp-python-tool** (`io.github.massimilianopili:mcp-python-tool`):

| Tool | Descrizione |
|------|-------------|
| `python_execute` | Esegue uno script Python con timeout e virtualenv opzionale |

- Sandboxing: virtualenv path configurabile, timeout, working directory
- Output: stdout + stderr + exit code
- Sicurezza: flag per abilitare/disabilitare `pip install` a runtime
- Pattern: `ProcessBuilder` con `python3 -c <script>` o `python3 <file>`
- `@ConditionalOnProperty(prefix = "mcp.python", name = "enabled", havingValue = "true")`

**Struttura progetto** (uguale alle altre librerie MCP):

```
mcp-bash-tool/
├── pom.xml             # parent mcp-parent, Java 21
├── src/main/java/com/agentframework/mcp/bash/
│   ├── BashTool.java                  # @ReactiveTool
│   ├── BashToolAutoConfiguration.java # @AutoConfiguration
│   └── BashToolProperties.java        # @ConfigurationProperties
└── src/test/java/...

mcp-python-tool/
├── pom.xml
├── src/main/java/com/agentframework/mcp/python/
│   ├── PythonTool.java
│   ├── PythonToolAutoConfiguration.java
│   └── PythonToolProperties.java
└── src/test/java/...
```

**Sforzo**: 1.5g (0.75g per tool). **Dipendenze**: nessuna (standalone).
**Impatto**: **Alto** — senza questi tool, i worker possono solo leggere/scrivere file ma non compilare, testare, o eseguire.

---

## #26 — Cost tracking per task + auto-split task costosi

**Problema**: il costo (token input/output) di un task non viene tracciato in modo strutturato.
Task molto costosi (es. generazione di un intero modulo con molte classi) consumano budget
senza possibilita' di controllo granulare. Non c'e' un meccanismo per spezzare automaticamente
task troppo grandi.

**Soluzione a 2 livelli**:

**Livello 1 — Cost tracking per task**:
Tracciare token utilizzati (input + output) e costo stimato per ogni task completato.

- `PlanItem.tokenUsage` — nuovo campo: `{ inputTokens, outputTokens, estimatedCostUsd }` (JSON)
- `AgentResult.tokenUsage` — il worker riporta l'uso token nel risultato
- `WorkerTaskConsumer` — dopo chiamata LLM, popola `tokenUsage` da `ChatResponse.getMetadata()`
- `OrchestrationService.onTaskCompleted()` — salva `tokenUsage` nell'item
- Dashboard/API: `GET /api/v1/plans/{id}/cost` — costo totale piano, breakdown per task
- Log strutturato: `log.info("Task {} completed: {}in/{}out tokens, ~${}",...)`

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

## #27 — Centralizzazione nomi tool (ToolNames registry)

**Problema**: i nomi dei tool MCP sono sparsi in 6+ file con 3 convenzioni diverse (Claude Code names,
MCP names, misto). Questo ha causato B13, B14, B16 — e continuera' a causare bug ogni volta che si
aggiunge un tool o si modifica la policy.

**Soluzione**: classe `ToolNames` nel modulo `worker-sdk` come unica source of truth.
Costanti per i 5 tool filesystem (`fs_list`, `fs_read`, `fs_write`, `fs_search`, `fs_grep`),
categorie (`WRITE_TOOLS`, `READ_TOOLS`, `ALL_FS_TOOLS`, `READONLY_FS_TOOLS`) e metodi helper.

**Scope**: ToolNames centralizza solo i tool usati nelle **policy di enforcement** (livelli 1-3).
I ~548 tool di dominio (`azure_*`, `devops_*`, `ocp_*`, etc.) sono gestiti esclusivamente
dal manifest allowlist — non servono costanti perche' non partecipano all'enforcement path/write.

**Design dettagliato**: vedi S8-L nel piano Session 8.

**Consumatori** (6 file da aggiornare):
1. `HookPolicyResolver.java` — usa `ToolNames.ALL_FS_TOOLS` / `ToolNames.READONLY_FS_TOOLS`
2. `PolicyProperties.java` — default da `ToolNames.WRITE_TOOLS`
3. `PathOwnershipEnforcer.java` — `ToolNames.isWriteTool()` / `ToolNames.isReadTool()`
4. `hook-manager.agent.yml` — schema con nomi MCP reali (tutti e 5 fs_*)
5. `application.yml.mustache` — rimuovere hardcoded, delegare a PolicyProperties
6. Worker generati — gia' corretti, ma refactorizzare per usare `ToolNames.ALL_FS_TOOLS`

**Estensibilita' futura** (#25): quando arrivano `mcp-bash-tool` e `mcp-python-tool`,
basta aggiungere `BASH_EXECUTE` e `PYTHON_EXECUTE` in `ToolNames` — tutti i consumatori
ereditano automaticamente. I tool di dominio (`db_query`, `docker_*`, etc.) restano fuori
dal registry perche' non hanno enforcement path ownership.

**Sforzo**: 0.5g. **Dipendenze**: nessuna (prerequisito per B13, B14, B16 fix definitivo).

## #28 — Monitoring Dashboard UI (real-time)

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

### G1 — Conversation history (❌ mancante)

**Problema**: i turni LLM non vengono salvati — solo il risultato finale (`PlanItem.result`).
Impossibile fare debug post-mortem ("perche' il worker ha letto file X prima di Y?"), impossibile fare session resume (P4).

**Impatto**: ALTO — senza conversation history non si puo' implementare ne' auto-compacting (P1/B17), ne' session resume (P4), ne' persistent memory (P3).

**Design proposto**: salvare il JSON completo dei messaggi Spring AI come JSONB in `DispatchAttempt.conversationLog`.
Questo e' il design piu' leggero — nessuna nuova entity, solo un campo aggiuntivo.
L'`AbstractWorker` serializza `chatClient.getMessages()` dopo ogni esecuzione.
Per retention: truncare a N turni (es. ultimi 50) se la conversazione e' troppo lunga.

**Sforzo**: 1g. **Pattern collegati**: P4 (session resume), P3 (persistent memory), P1 (auto-compacting).

### G2 — Decision reasoning (❌ mancante)

**Problema**: nessun chain-of-thought salvato. Il Provenance ha `toolsUsed` (cosa) ma non `reasoning` (perche').
Il GP Engine (#11) non ha accesso al reasoning del worker per calcolare reward piu' precisi.

**Impatto**: MEDIO — utile per audit e miglioramento GP, non bloccante.

**Design proposto**: campo `Provenance.reasoning` (TEXT, max 2000 char).
L'`AbstractWorker` estrae il primo blocco di testo dalla prima risposta LLM (prima del primo tool call) — questo e' tipicamente il "piano d'azione" del worker.
Salvato automaticamente, nessun costo aggiuntivo di token.

**Sforzo**: 0.5g. **Pattern collegati**: P24 (insight blocks).

### G3 — File modification tracking (❌ mancante)

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

### G4 — Prometheus/Micrometer metrics (❌ mancante)

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

### G5 — Persistent audit (❌ mancante — AuditManagerService in-memory)

**Problema**: `AuditManagerService` usa `CopyOnWriteArrayList` in-memory (max 10k eventi).
Al restart del servizio, tutti gli audit event sono persi. Con ring-buffer, gli eventi piu' vecchi vengono eliminati.

**Impatto**: MEDIO — gli eventi critici sono gia' su `PlanEvent` (DB). Ma i tool audit dettagliati (con input preview, violations) vanno persi.

**Design proposto**: migrare `AuditManagerService` da in-memory a PostgreSQL.
`AuditEvent` entity (NEW):
```
id (UUID)
taskKey (VARCHAR, nullable)
eventType (VARCHAR — TOOL_CALL, POLICY_VIOLATION, TASK_LIFECYCLE)
payload (JSONB)
occurredAt (TIMESTAMP)
```
Retention: cron job cancella eventi > 30 giorni. Indice su `(taskKey, occurredAt)`.
Il REST endpoint `/audit/events` legge da DB invece che da lista in-memory.

**Sforzo**: 0.5g. **Pattern collegati**: Event Sourcing (#1) — estensione naturale.

### G6 — MCP Server audit logging (❌ mancante)

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
| G1 Conversation history | 1g | ALTA | — |
| G2 Decision reasoning | 0.5g | MEDIA | — |
| G3 File modification tracking | 1.5g | ALTA | — |
| G4 Prometheus metrics | 0.5g | ALTA | actuator+micrometer deps |
| G5 Persistent audit | 0.5g | MEDIA | — |
| G6 MCP server audit logging | 1g | ALTA | header propagation nel worker SDK |
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
- Feature implementate: #1, #2, #3, #4, #6, #11, #14, #16 (motivazioni architetturali complete)
- RAG Pipeline: piano dettagliato 3 sessioni, struttura modulo, config YAML, fonti
- Session log: S1, S2, S3, S5, S6, S7 (file nuovi/modificati, test, commit)
- Riepilogo file per sessione (tabella riassuntiva)

_→ Dettagli in PIANO_HISTORY.md_
