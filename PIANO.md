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

### 1. Event Sourcing Puro

**Problema**: `Plan.status` e `PlanItem.status` vengono sovrascritti in-place. Nessuna storia queryabile.

**Soluzione**: `PlanEvent` entity append-only. `Plan` e `PlanItem` diventano proiezioni materializzate.

```java
@Entity
public class PlanEvent {
    UUID id;
    UUID planId;
    UUID itemId;       // null = evento a livello piano
    String eventType;  // PLAN_STARTED | TASK_DISPATCHED | TASK_COMPLETED | TASK_FAILED | PLAN_COMPLETED
    String payload;    // JSON completo (include resultJson per TASK_COMPLETED)
    Instant occurredAt;
    long sequenceNumber; // monotonicamente crescente per planId, no cascade delete
}
```

**Decisioni**:
- Retention **permanente** (audit trail, no cascade delete con il piano)
- Payload **completo** negli eventi TASK_COMPLETED (include `resultJson`)
- Abilita SSE late-join con replay (punto 5b)

**File**: `PlanEvent.java` (NEW), `PlanEventRepository.java` (NEW), `PlanEventStore.java` (NEW),
refactoring `OrchestrationService`, `Plan.java`, `PlanItem.java`

---

### 2. Missing-Context Feedback Loop

**Problema**: `missing_context` nel JSON di output dei worker viene ignorato da `OrchestrationService`.

**Soluzione**: loop automatico — crea nuovo task CONTEXT_MANAGER/TASK_MANAGER e rimette il worker in WAITING.

```yaml
# Nel manifest worker:
maxContextRetries: 2  # default: 1
```

```java
// onTaskCompleted():
if (contextRetries < maxContextRetries) {
    PlanItem ctxTask = createContextManagerTask(planId, missing, result.taskKey());
    item.addDependency(ctxTask.getTaskKey());
    item.transitionTo(WAITING);
} else {
    item.transitionTo(FAILED); // fallimento definitivo
}
```

**File**: `AgentManifest.java`, `PlanItem.java`, `OrchestrationService.onTaskCompleted()`

---

### 3. Retry Automatico con Exponential Backoff

**Problema**: `AgentManifest.Retry` è parsato ma mai usato automaticamente.

**Soluzione**: `AutoRetryScheduler` + contesto aggiornato prima del retry (ri-esegue TASK_MANAGER).

```yaml
# Nel manifest worker:
retry:
  maxAttempts: 3
  backoffMs: 5000
  attemptsBeforePause: 2  # dopo 2 fallimenti, piano → PAUSED
```

**Comportamento**:
- Backoff esponenziale con jitter ±25% dal primo tentativo
- Primi `attemptsBeforePause` retry: immediati con backoff
- Dopo `attemptsBeforePause`: piano va in stato `PAUSED`
- Prima del retry: ri-esecuzione CONTEXT_MANAGER/TASK_MANAGER per contesto fresco

**Nuovo stato**: `PlanStatus.PAUSED`

**File**: `PlanItem.java`, `AgentManifest.Retry`, `AutoRetryScheduler.java` (NEW),
`PlanItemRepository.findRetryEligible()` (NEW), `PlanStatus.java`

---

### 4. Saga / Compensation (COMPENSATOR_MANAGER)

**Problema**: nessun meccanismo di rollback quando un task fallisce definitivamente.

**Soluzione**: trigger manuale via API — l'utente decide scope e profondità del rollback.
`COMPENSATOR_MANAGER` esegue operazioni git (revert, branch delete) via MCP tool.

```yaml
# Nel manifest worker:
compensation:
  description: "Revert all commits on branch {branch}. Use git tool."
```

```
POST /api/v1/plans/{id}/items/{itemId}/compensate
→ crea PlanItem workerType=COMPENSATOR_MANAGER
→ dispatcha normalmente
```

**File**: `AgentManifest.java`, `PlanItem.java`, `WorkerType.java`,
`OrchestrationService.createCompensationTask()`, `PlanController.java`, `v1.yaml`,
nuovo modulo `compensator-manager-worker/`

---

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

### 6. Token Budget per WorkerType

**Problema**: nessun limite ai token consumati. Nessuna visibilità sui costi per tipo di worker.

**Soluzione**: sub-budget per `WorkerType` in `PlanRequest`, tracking atomico via Redis `INCRBY`.

```json
{
  "budget": {
    "onExceeded": "NO_NEW_DISPATCH",
    "perWorkerType": { "BE": 200000, "FE": 100000, "CONTEXT_MANAGER": 50000 }
  }
}
```

**Comportamento configurabile**: `FAIL_FAST | SOFT_LIMIT | NO_NEW_DISPATCH`

**Redis key**: `agentfw:tokens:{planId}:{workerType}` (INCRBY atomico, no race condition)

**File**: `PlanRequest.java`, `Plan.java`, `TokenBudgetInterceptor.java` (NEW),
`WorkerAutoConfiguration.java`

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
```

---

## Priorità di implementazione

| # | Feature | Sforzo stimato | Impatto | Dipendenze |
|---|---------|---------------|---------|------------|
| 8 | DAG endpoint Mermaid | 0.5g | Medio | — |
| 2+3 | Missing-context + Auto-retry | 2g | Alto | — |
| 5 | SSE + TrackerSyncService | 1g | Alto | — |
| 6 | Token budget per WorkerType | 1g | Medio | — |
| TM | TASK_MANAGER worker | 2g | Alto | — |
| 7 | Context cache | 1g | Medio | TASK_MANAGER |
| 1 | Event Sourcing puro | 5g | Molto alto | — (foundation) |
| 5b | SSE late join con replay | 0.5g | Alto | ES puro |
| 9 | Hierarchical plans | 3g | Alto | — |
| 10 | HookPolicy extensions | 2g | Alto | — |
| 4 | COMPENSATOR_MANAGER | 2g | Medio | — |
| lib | Modulo agent-common (HookPolicy) | 0.5g | Medio | — |
