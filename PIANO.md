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

## B17 Livello 2 — CompactingToolCallingManager ✅

> Design spostato in PIANO_HISTORY.md. Implementazione: `CompactingToolCallingManager.java` (worker-sdk),
> `BeanPostProcessor` wrap del `DefaultToolCallingManager`, soglia 75% context window.
> Commit: `ee9f85d`. Test: `CompactingToolCallingManagerTest`.

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

# Roadmap items #30-#34 — Blockchain-Inspired Enhancements

Cinque concetti ispirati alla blockchain — senza blockchain vera — per aggiungere garanzie
crittografiche di integrita' al framework. Costo infrastrutturale zero: i primitivi crittografici
(hash chain, firme Ed25519, commitment) danno le stesse garanzie senza consenso distribuito.


# Roadmap items #30-#34 — Blockchain-Inspired Enhancements ✅

Tutti implementati. Design dettagliati: PIANO_HISTORY.md.

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

Fase 8 della roadmap: 12 items in 3 domini (Finanza #50-#54, Sistemi Complessi #55-#57, Matematica Avanzata #58-#61). Tutti implementati in S8-research + S10. Dettagli: `docs/agent-framework/research-domains-new.md`.

| # | Item | Componente | Sessione |
|---|------|-----------|----------|
| 50 | Portfolio Theory (Markowitz) | `PortfolioAllocator` | S8-research |
| 51 | Market Making (Bid-Ask) | `MarketMakingScheduler` | S8-research |
| 52 | Black-Scholes Greeks | `WorkerGreeksService` | S8-research |
| 53 | Bayesian Success Prediction | `BayesianSuccessPredictor` | S8-research |
| 54 | Causal Inference (Do-Calculus) | `CausalInferenceService` | S8-research |
| 55 | Evolutionary Game Theory | `ReplicatorDynamicsService` | S8-research |
| 56 | Self-Organized Criticality | `SandpileService` | S8-research |
| 57 | Swarm Intelligence (ACO) | `AntColonyOptimizer` | S8-research |
| 58 | Spectral Graph Theory | `SpectralDecomposer` | S8-research |
| 59 | Tropical Geometry (Min-Plus) | `TropicalScheduler` | S8-research |
| 60 | Optimal Transport (Wasserstein) | `OptimalTransportService` | S8-research |
| 61 | Submodular Optimization | `SubmodularSelector` | S8-research |

Codice condiviso: `HashUtil`, `CriticalPathCalculator`, `TropicalSemiring`, `CovarianceMatrix`, `TaskOutcomeService`, `GpWorkerSelectionService`, `PlanGraphService`, `AnalyticsController`.

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
Fase 9a (fondazioni, ~8.0g):      #64 ✅ → #65 ✅ → #69 ✅ → #71 ✅ → #75 ✅
Fase 9b (game theory, ~5.0g):     #62 ✅ → #67 ✅
Fase 9c (controllo+info, ~10.5g): #63 ✅ → #68 ✅ → #73 ✅ → #74 ✅
Fase 9d (avanzato, ~5.0g):        #66 ✅ → #70 ✅
Fase 9e (agent found., ~5.5g):    #76 ✅ → #72 ✅
                                   ─────────────────────
                                   Totale: ~33.0g (#62-#76) ✅
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
Fase 13 (#107-#116):10 items, ~24.0g  [research-domains-ext.md]
───────────────────────────────────────
Totale:             67 items, ~147.5g

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
Fase 10a (fondazioni, ~6.0g):       #79 ✅ → #82 ✅ → #84 ✅
Fase 10b (core, ~10.0g):            #77 ✅ → #78 ✅ → #81 ✅ → #83 ✅
Fase 10c (avanzato, ~8.0g):         #80 ✅ → #85 ✅ → #86 ✅
                                     ─────────────────────
                                     Totale: ~24.0g (#77-#86) ✅
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
Fase 11a (formal+voting, ~9.5g):   #87 ✅ → #90 ✅ → #89 ✅ → #88 ✅
Fase 11b (council+econ, ~6.5g):    #91 ✅ → #95 ✅ → #96 ✅
Fase 11c (avanzato, ~6.5g):        #93 ✅ → #94 ✅ → #92 ✅
                                     ─────────────────────
                                     Totale: ~22.5g (#87-#96) ✅
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
Fase 12a (core, ~12.0g):          #97 ✅ → #99 ✅ → #100 ✅ → #101 ✅ → #102 ✅ → #105 ✅
Fase 12b (avanzato, ~9.5g):       #98 ✅ → #103 ✅ → #104 ✅ → #106 ✅
                                    ─────────────────────
                                    Totale: ~21.5g (#97-#106) ✅
```

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

### Risultati ricerca Fase 13 — Sintesi accademica (S23)

Ricerca completata su 7 item rimanenti (#107, #108, #109, #112, #113, #114, #115). 35+ paper validati, 7 connessioni trasversali identificate.

#### #107 Context Engineering — Knapsack + Information Scent + Compaction

**Paper validati:** TALE (He et al., ACL 2025, -67% costi), RankRAG (Yu et al., NeurIPS 2024, +10% QA), LongLLMLingua (Jiang et al., ACL 2024, +21.4% con 4× meno token), Pirolli & Card 1999 (T1, information foraging), Blackboard Architecture (Salemi et al., arXiv:2510.01285, +13-57%).

**Insight:** Nessun paper formula context selection come knapsack 0-1 → formulazione **genuinamente nuova** (il campo usa top-k = knapsack degenere con pesi uguali).

**Design:** Value function con sigmoid decay × task alignment. Stopping: marginal value theorem di Pirolli. Compaction tiered: verbatim → LongLLMLingua 2-4× → LLMLingua-2 10-20× → drop. Context sharing: blackboard su AGE `task_graph`.

#### #108 Curriculum Prompting — Difficulty Estimation + Self-Paced + Golden Examples

**Paper validati:** Bengio et al. 2009 (ICML, T1, ~6200 cit), DAAO (arXiv:2509.11079, quasi identico al nostro design), TaskEval (arXiv:2407.21227, IRT per difficoltà), TACLer (arXiv:2601.21711, -42% token), Liu et al. 2022 (DeeLIO, T1, +41.9%).

**Insight:** IRT ≡ Bradley-Terry → riuso infrastruttura Preference Sort per stima difficoltà. `P(correct | ability, difficulty) = sigmoid(ability - difficulty)`.

**Design:** `D(task) = alpha * prior(type) + (1-alpha) * dynamic(history)`, alpha → 0 con dati. Curriculum lambda adattivo (+0.05 successo, -0.025 fallimento). CoT budget proporzionale: easy → minimal, hard → extended + self-verify.

#### #109 Iterated Amplification — Cascade H₀→H₃ + Anti-Collusion

**Paper validati:** Christiano et al. 2018 (arXiv:1810.08575, ~500 cit, IDA), Burns et al. 2023 (OpenAI, weak-to-strong), Trust or Escalate (ICLR 2025, confidence-gated), Self-Refine (Madaan et al. NeurIPS 2023, +20%), NeurIPS 2024 collusion paper (prompt-based anti-collusion non funziona).

**Insight:** Council di 8 membri rischia sycophantic consensus. Servono: ruoli strutturalmente diversi (2 adversarial su 8), valutazione indipendente prima della deliberazione (Delphi method), calibration probes periodiche.

**Design:** Escalation confidence-gated (H1→H2 solo se confidence bassa). Self-Refine intra-worker 2-3 round prima di escalare. Council: independent-then-deliberate, probes inject. H3 calibrato su outcome reali.

#### #112 MCTS Dispatch — PUCT + GP Prior + Welford Backprop

**Paper validati:** Kocsis & Szepesvári 2006 (ECML, T1, ~4700 cit, UCT), Silver et al. 2017 (Nature, T1, ~14000 cit, PUCT/AlphaZero), BOMCP (Mern et al., AAAI 2021, GP-guided MCTS), SWE-Search (Antoniades et al., arXiv:2410.20285, +23%), Tesauro et al. 2012 (UAI, Bayesian backprop).

**Insight:** PUCT > UCT per il nostro caso. GP posterior serve direttamente come prior P(s,a) via softmax. GP mean come leaf value elimina rollout random. 150 GP query × 1ms = 150ms, dentro budget 1s.

**Design:** `a* = argmax[Q(s,a) + c_puct * P(s,a) * sqrt(N(s)) / (1 + N(s,a))]`. Prior: `softmax(mu_i / tau)`. Backprop Welford online. Budget: depth=3, topK=5, 50 iter, <500ms.

#### #113 Worker-to-Worker Handoff — Confidence Routing + Anti-Loop

**Paper validati:** AutoGen Swarm (Wu et al., arXiv:2308.08155), Cemri et al. 2025 (ICLR, 14 failure modes MAS), Zhang et al. 2025 (arXiv:2502.11021, semantic entropy router), ACON (Kang et al., arXiv:2510.00615, -26-54% memoria), Anthropic engineering blog (filesystem come medium).

**Insight:** Pattern Anthropic (artifact refs su storage condiviso, mai inline) superiore al message passing. Max 3-4 handoff prima che il context degradi. Loop prevention: visited-set + exponential penalty.

**Design:** Trigger: GP σ² > threshold(depth) crescente (0.5→0.7→0.9). Max depth 3-4 hard limit. Context transfer: summary strutturato + artifact refs. HandoffRequest record con chainDepth e traceId.

#### #114 Markov Shapley Value — TMC-Shapley + Owen Values

**Paper validati:** SHAQ (Wang et al., NeurIPS 2022, T1, SBOE), Data Shapley (Ghorbani & Zou, ICML 2019, T1), Causal Shapley (Heskes et al., NeurIPS 2020, T1), SHARP (arXiv:2602.08335, +23.66%), Blame Attribution (NeurIPS 2021, non-monotonicità).

**Insight:** N=10 worker types → exact Shapley (1024 coalizioni × 1ms GP = ~1s) **trattabile**. Owen values con 4 gruppi {Backend, Frontend, Intelligence, Quality} → ~50 coalizioni. Attenzione: non-monotonicità in setting sequenziali.

**Design:** TMC-Shapley 50 permutations, stop su marginal < 10% noise_std. Owen hierarchy real-time. V(S) = GP prediction con coalition mask. Tripartite reward (da SHARP): `R_total_i = α·R_global + β·R_shapley_i + γ·R_process_i`.

#### #115 Factorised Belief Models — EFE come GP-UCB Principled

**Paper validati:** Ruiz-Serra et al. (AAMAS 2025, T1, factorised beliefs), Li et al. 2026 (arXiv:2602.06029, **GP-UCB ⊂ EFE**), Koudahl et al. 2021 (Entropy, T1), Parr/Pezzulo/Friston 2022 (MIT Press, textbook AIF), Fiedler et al. 2024 (LOD, AIF scheduling).

**Insight:** Implementazione = **5 righe di codice** sopra il GP esistente. `EFE(a) = -mu(a) - λ·0.5·ln(1 + σ²/noise²)`. λ = sqrt(2·ln(T)) per garanzia no-regret. Non serve full AIF apparatus.

**Design:** Pragmatic = mu(a), epistemic = 0.5·ln(1+σ²/noise²), score = pragmatic + λ·epistemic. λ adattivo su T. Mean-field factorisation (il GP la fa già). Opzionale: multi-output GP con coregionalisation per correlazioni cross-worker.

#### Connessioni trasversali

| Connessione | Descrizione | Impatto |
|-------------|-------------|---------|
| IRT ≡ Bradley-Terry | Stima difficoltà (#108) = modello logistico Preference Sort | Riuso infrastruttura |
| GP-UCB ⊂ EFE | Active Inference (#115) sussume GP-UCB con garanzie più forti | 5 righe di upgrade |
| Blackboard ≡ task_graph | Context sharing (#107) = pattern in AGE task_graph | Zero nuovo data layer |
| PUCT prior = GP posterior | MCTS (#112) usa GP come prior senza conversioni | Design pulito |
| Trust or Escalate → H₀→H₃ | ICLR 2025 (#109) = escalation gated | Risparmio compute |
| Anthropic filesystem | Handoff (#113) tramite artifact refs, non inline | Best practice validata |
| Owen hierarchy | N=10, 4 gruppi (#114) → 50 coalizioni vs 1024 | Real-time attribution |

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

### Dipendenze critiche Fase 12

- **#98 Process Mining → #87 Petri Nets**: il workflow net scoperto dal process mining E' un Petri net
- **#100 Actor Model → #88 CSP**: complementari — Actor per isolamento, CSP per sincronizzazione
- **#104 Info Foraging → #94 Compressed Sensing**: entrambi ottimizzano retrieval RAG
- **#106 Stigmergy → #92 VSM**: meccanismo di coordinamento S2 nel VSM
- **GP Engine (#15)** prerequisito per #97, #99, #102 (prior/posterior, reward, convergenza)
- **OrchestrationService** target di #100, #101, #105, #106 (supervision, snapshot, policy, coordinamento)
- Nessuna Flyway migration — tutti in-memory, Redis, o metriche runtime

### Audit qualità Fase 9-12 (2026-03-10)

Audit sistematico di tutte le 45 implementazioni (#62-#106). Classificazione:
- **GENUINE**: implementa l'algoritmo reale dal paper
- **ACCEPTABLE**: semplificato ma cattura l'insight core
- **STUB**: query+mean+DTO con naming cosmetico

| Fase | GENUINE | ACCEPTABLE | STUB |
|------|---------|------------|------|
| 9 (#62-#76) | 15/15 | 0 | 0 |
| 10 (#77-#86) | 9/10 | 1 (#86) | 0 |
| 11 (#87-#96) | 10/10 | 0 | 0 |
| 12 (#97-#106) | 9/10 | 0 | 1 (#100) |
| **Totale** | **43** | **1** | **1** |

**Fix applicati (stesso giorno)**:

1. **#100 ActorModelSupervisor — REWRITE** (STUB → GENUINE)
   - Prima: calcolo crash-rate + soglie, nessuno stato
   - Dopo: supervision tree con ChildActor (state machine RUNNING→CRASHED→RESTARTING→STOPPED),
     restart policy (maxRestarts in time window), escalation on limit exceeded,
     REST_FOR_ONE strategy con ordine di registrazione, restart event history
   - 11 test (erano 7)

2. **#86 FunctorialSemanticsService — BUG FIX** (ACCEPTABLE → GENUINE)
   - Bug: `error = |compositeAC - (edgeAB + edgeBC)|` era una tautologia algebrica (sempre 0)
     perché `(fc-fa) - ((fb-fa)+(fc-fb)) = 0` per telescoping sum
   - Fix: confronta functor (GP prediction) con ground truth (actual_reward) su path compositi:
     `error = |(gp_mu(C)-gp_mu(A)) - (actual(C)-actual(A))|`
   - 7 test (erano 5), inclusi test di non-zero error e non-compositional detection

3. **#101 ChandyLamportSnapshotter — IMPROVEMENT** (ACCEPTABLE → GENUINE)
   - Prima: solo task_outcomes + PlanEvent, nessun local state reale
   - Dopo: PlanItemRepository per stato locale reale (WAITING/DISPATCHED/RUNNING/DONE/FAILED),
     marker sequence (ultimo PlanEvent.sequenceNumber), state-event coherence check
     (DISPATCHED senza evento = violazione), ProcessState record per item
   - 8 test (erano 6), inclusi coherence violation e mixed states

**Test suite**: 1082 test, 0 failure (era 1074, +8 test netti)

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
- Feature implementate: #1-#4, #6, #11-#18, #19-#49, #50-#106 (motivazioni architetturali complete)
- RAG Pipeline: piano dettagliato 3 sessioni, struttura modulo, config YAML, fonti
- Bug fix: B1-B7, B9-B11, B13-B19 (sessioni S8, S12, S14)
- Session log: S1-S22
- Design reference: B17 L2 CompactingToolCallingManager, tool mapping B13, Observability G1-G6
- Riepilogo file per sessione (tabella riassuntiva)

_→ Dettagli in PIANO_HISTORY.md_
