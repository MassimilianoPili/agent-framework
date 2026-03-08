# Orchestrator — Control Plane

Spring Boot application che funge da cervello del framework: espone una REST API per la
gestione dei piani, decompone le specifiche in task tramite Claude AI, orchestra
l'esecuzione rispettando dipendenze e profili worker.

## API Endpoints

| Method | Path | Status | Descrizione |
|--------|------|--------|-------------|
| `POST` | `/api/v1/plans` | 202 | Crea piano da specifica, chiama planner, dispatcha prima onda |
| `GET` | `/api/v1/plans/{id}` | 200 | Stato corrente del piano con tutti gli item |
| `POST` | `/api/v1/plans/{id}/resume` | 202 | Riprende piano in stato PAUSED |
| `GET`  | `/api/v1/plans/{id}/events` | 200 | SSE stream eventi piano (late-join replay via `Last-Event-ID`) |
| `GET`  | `/api/v1/plans/{id}/graph` | 200 | DAG visuale (`?format=mermaid\|json`) |
| `POST` | `/api/v1/plans/{id}/items/{itemId}/retry` | 202 | Ritenta item fallito (FAILED → WAITING) |
| `POST` | `/api/v1/plans/{id}/items/{itemId}/approve` | 202 | Approva item in AWAITING_APPROVAL (→ WAITING) |
| `POST` | `/api/v1/plans/{id}/items/{itemId}/reject` | 202 | Rifiuta item in AWAITING_APPROVAL (→ FAILED) |
| `POST` | `/api/v1/plans/{id}/items/{itemId}/compensate` | 202 | Avvia compensazione via COMPENSATOR_MANAGER |
| `GET` | `/api/v1/plans/{id}/items/{itemId}/attempts` | 200 | Storico dispatch attempt per item |
| `GET` | `/api/v1/plans/{id}/snapshots` | 200 | Lista snapshot Memento del piano |
| `POST` | `/api/v1/plans/{id}/restore/{snapshotId}` | 200 | Ripristina piano da snapshot |
| `GET` | `/api/v1/plans/{id}/quality-gate` | 200 | Report quality gate per piano completato |
| `GET` | `/api/v1/rewards` | 200 | Reward records per task, NDJSON (`?planId=` opzionale) |
| `GET` | `/api/v1/rewards/stats` | 200 | ELO leaderboard per worker profile |
| `GET` | `/api/v1/rewards/preference-pairs` | 200 | DPO preference pairs NDJSON (`?minDelta=0.3&limit=500`) |
| `PUT` | `/api/v1/plans/{id}/items/{itemId}/issue-snapshot` | 200 | Salva snapshot issue tracker su item (TASK_MANAGER) |
| `GET` | `/api/v1/plans/{id}/council-report` | 200 | Report pre-planning council session |

Controller: `api/PlanController.java` (17 endpoint), `reward/RewardController.java` (3 endpoint), `hooks/AuditManagerService.java` (3 endpoint), `hooks/EventManagerService.java` (3 endpoint)

## Domain Model

### Plan

| Campo | Tipo | Note |
|-------|------|------|
| id | UUID | Primary key |
| spec | String (TEXT) | Specifica in linguaggio naturale |
| status | PlanStatus | State machine: PENDING → RUNNING → COMPLETED/FAILED/PAUSED |
| createdAt | Instant | Non null, default now() |
| completedAt | Instant | Nullable |
| depth | int | Profondità nella gerarchia sub-plan (0 = root plan) |
| parentPlanId | UUID | UUID del piano padre per i sub-plan (nullable) |
| budgetJson | String (TEXT) | Serializzazione JSON di `PlanRequest.Budget` (nullable) |
| sourceCommit | String(64) | Hash commit git al momento della creazione (nullable) |
| workingTreeDiffHash | String(64) | Hash SHA-256 del diff working tree al momento della creazione (nullable) |
| items | List\<PlanItem\> | OneToMany, cascade, ordered by ordinal |

### PlanItem

| Campo | Tipo | Note |
|-------|------|------|
| id | UUID | Primary key |
| taskKey | String(20) | Pattern: `BE-001`, `CT-001`, `RV-001` |
| title | String(500) | Titolo task leggibile |
| description | String (TEXT) | Descrizione dettagliata |
| workerType | WorkerType | Enum: `BE`, `FE`, `AI_TASK`, `CONTRACT`, `REVIEW`, `CONTEXT_MANAGER`, `SCHEMA_MANAGER`, `HOOK_MANAGER`, `AUDIT_MANAGER`, `EVENT_MANAGER`, `TASK_MANAGER`, `COMPENSATOR_MANAGER`, `SUB_PLAN` |
| workerProfile | String(50) | Profilo concreto: be-java, fe-react, etc. (risolto al dispatch se null) |
| dependsOn | List\<String\> | TaskKey delle dipendenze |
| status | ItemStatus | State machine: WAITING → DISPATCHED → DONE/FAILED; AWAITING_APPROVAL |
| result | String (TEXT) | JSON risultato dal worker |
| issueSnapshot | String (TEXT) | Snapshot JSON del ticket issue-tracker (TASK_MANAGER) |
| childPlanId | UUID | UUID del piano figlio creato inline (SUB_PLAN, nullable) |
| awaitCompletion | boolean | Se true, item rimane DISPATCHED finché il child plan termina (SUB_PLAN) |
| subPlanSpec | String (TEXT) | Specifica del sub-plan da scomporre (SUB_PLAN, nullable) |
| reviewScore | Float | Score [-1.0, +1.0] assegnato dal worker REVIEW; null fino al completamento del REVIEW task |
| processScore | Float | Score [0.0, 1.0] deterministico da metriche Provenance (token, retry, durata); null fino a DONE |
| aggregatedReward | Float | Aggregazione Bayesiana pesata di tutte le fonti disponibili; null se nessuna fonte ancora disponibile |
| rewardSources | String (TEXT) | JSON snapshot dei valori per fonte e pesi effettivi (`{"review":0.8,"process":0.6,"quality_gate":null,"weights":{…}}`) |

### PlanEvent (Event Sourcing — append-only)

| Campo | Tipo | Note |
|-------|------|------|
| id | UUID | Primary key |
| planId | UUID | FK verso `plans` |
| itemId | UUID | FK verso `plan_items` (nullable — eventi piano-level) |
| eventType | String(64) | `PLAN_STARTED`, `PLAN_PAUSED`, `PLAN_RESUMED`, `TASK_DISPATCHED`, `TASK_COMPLETED`, `TASK_FAILED`, `PLAN_COMPLETED` |
| payload | String (TEXT) | JSON payload dell'evento (nullable) |
| occurredAt | Instant | Timestamp immutabile |
| sequenceNumber | long | Monotonicamente crescente per-plan; usato come SSE event ID |

### PlanTokenUsage

| Campo | Tipo | Note |
|-------|------|------|
| id | UUID | Primary key |
| planId | UUID | FK verso `plans` |
| taskKey | String(20) | Chiave task |
| workerType | String(50) | Tipo worker |
| inputTokens | int | Token input consumati |
| outputTokens | int | Token output consumati |
| recordedAt | Instant | Timestamp registrazione |

### DispatchAttempt (Command pattern)

| Campo | Tipo | Note |
|-------|------|------|
| id | UUID | Primary key |
| attemptNumber | int | 1, 2, 3... (unique con item_id) |
| success | boolean | Esito dell'esecuzione |
| durationMs | Long | Tempo di esecuzione |
| failureReason | String (TEXT) | Motivo del fallimento |

### PlanSnapshot (Memento pattern)

| Campo | Tipo | Note |
|-------|------|------|
| id | UUID | Primary key |
| label | String(100) | Nome dello snapshot |
| planData | String (TEXT) | JSON completo del piano + items |

## State Machine

```
PlanStatus                                    ItemStatus

PENDING ──► RUNNING ──► COMPLETED             WAITING ──► DISPATCHED ──► DONE
                   ├──► FAILED                    │              └──► FAILED ◄─ DISPATCHED
                   └──► PAUSED                    │                      │
                         │                        └──► AWAITING_APPROVAL ─┤
                         └──► RUNNING (resume)          │        │        │
                                                   (approve) (reject) (timeout)
                                                         │        │
                                                       WAITING  FAILED
                                                         │
                                                       WAITING (retry from FAILED)
```

Note sulle transizioni:
- `AWAITING_APPROVAL`: item con `riskLevel=CRITICAL` o `requiredHumanApproval != NONE` — il dispatcher salta l'item invece di mandarlo sul broker.
- `PAUSED`: il piano viene messo in pausa (es. budget esaurito con `NO_NEW_DISPATCH`) e riprende via `POST /{id}/resume`.
- Transizioni illegali lanciano `IllegalStateTransitionException`.

Le transizioni sono validate in `Plan.transitionTo()` e `PlanItem.transitionTo()`.
Transizioni illegali lanciano `IllegalStateTransitionException`.

## Orchestration Service

Classe centrale: `orchestration/OrchestrationService.java`

**Metodi pubblici:**

| Metodo | Annotazione | Descrizione |
|--------|------------|-------------|
| `createAndStart(spec)` | @Transactional | Crea piano → planner → dispatch prima onda |
| `onTaskCompleted(result)` | @Transactional | Aggiorna stato → dispatch sbloccati → check completamento |
| `retryFailedItem(itemId)` | @Transactional | FAILED → WAITING → re-dispatch |
| `resumePlan(planId)` | @Transactional | PAUSED → RUNNING → dispatch sbloccati |
| `approveItem(planId, itemId)` | @Transactional | AWAITING_APPROVAL → WAITING → triggerDispatch |
| `rejectItem(planId, itemId, reason)` | @Transactional | AWAITING_APPROVAL → FAILED |
| `triggerDispatch(planId)` | @Transactional | Dispatcha item pronti senza cambiare stato piano (usato da approve) |
| `createCompensationTask(itemId, reason)` | @Transactional | Crea PlanItem COMPENSATOR_MANAGER figlio |
| `getAttempts(itemId)` | readOnly | Storico dispatch per item |
| `getPlan(planId)` | readOnly | Stato piano |

**Dispatch logic (`dispatchReadyItems`):**
1. Query DB: item WAITING con tutte le dipendenze DONE
2. Per ogni item: risolvi profilo di default se null (BE→be-java, FE→fe-react)
3. Valida capabilities con Specification pattern (CompositeSpec)
3b. Risolvi `HookPolicy` via `HookManagerService.resolvePolicy(planId, taskKey, workerType)` (null se non disponibile)
4. Crea DispatchAttempt → `AgentTask(…, policy)` → pubblica su topic
5. Transizione: WAITING → DISPATCHED

**HookManagerService integration:**
- Al completamento di un item `HOOK_MANAGER`, `onTaskCompleted()` chiama `HookManagerService.storePolicies(planId, resultJson)`.
- Al completamento del piano, `checkPlanCompletion()` chiama `HookManagerService.evictPlan(planId)` per rilasciare la cache in-memory.
- Se HM non ha ancora completato (o non è nel piano), `resolvePolicy()` delega a `HookPolicyResolver` (fallback statico per `WorkerType`).

**Idempotency guard**: risultati duplicati per item gia' terminali vengono ignorati
(necessario per at-least-once delivery di Service Bus).

## Spring Events

Tutti gli eventi sono sottoclassi di `SpringPlanEvent` (campo `eventType` per discriminare):

| Evento | eventType | Payload | Pubblicato quando |
|--------|-----------|---------|------------------|
| `PlanCreatedEvent` | `PLAN_CREATED` | planId, spec, itemCount | Piano passa a RUNNING |
| `PlanItemDispatchedEvent` | `ITEM_DISPATCHED` | planId, itemId, taskKey, workerProfile | Item dispatchato |
| `PlanItemCompletedEvent` | `ITEM_COMPLETED` | planId, itemId, taskKey, success, durationMs | Item completato/fallito |
| `PlanCompletedEvent` | `PLAN_COMPLETED` | planId, status, itemCount, failedCount | Tutti gli item terminali |

`PlanEventStore.append()` viene chiamato in ogni handler di transizione stato di `OrchestrationService`
con propagazione `MANDATORY` (stessa transazione). Gli eventi sono anche inviati live agli SSE subscriber
tramite `SseEmitterRegistry.broadcast()`.

## Database (Flyway)

Migrazioni consolidate per argomento (6 file). Ogni migrazione include `COMMENT ON` per auto-documentazione dello schema.

| Migrazione | Tabelle | Scopo |
|-----------|---------|-------|
| V1 | `plans`, `plan_items`, `plan_item_deps`, `quality_gate_reports`, `quality_gate_findings`, `dispatch_attempts`, `plan_snapshots` | Core orchestrator: piani, task DAG, quality gates, dispatch audit trail, checkpoint/restore |
| V2 | (alter) `plans` + `plan_items`, `plan_token_usage` | Resilienza: auto-retry con backoff, token budget per worker-type, issue snapshot, git state, pausing |
| V3 | `plan_event`, (alter) `plans` + `plan_items` | Event sourcing (audit trail + SSE replay) + piani gerarchici (SUB_PLAN, depth, parent) |
| V4 | (alter) `plans` + `plan_items`, `worker_elo_stats`, `preference_pairs` | Council pre-planning + reward signal (Bayesian scoring, ELO rating, DPO preference pairs) |
| V5 | `vector_store` + estensione pgvector | RAG vector store: embedding 1024 dim (mxbai-embed-large), HNSW cosine, GIN metadata JSONB, BM25 full-text search con trigger tsvector |
| V6 | estensione Apache AGE + grafi | Graph RAG: grafi `knowledge_graph` (chunk, concetti, decisioni) e `code_graph` (file, classi, metodi, package) |
| V7 | (alter) `plan_items` | Ralph-Loop: `ralph_loop_count` + `last_quality_gate_feedback` per quality gate feedback loop |
| V8 | `task_outcomes` | GP training data: embedding pgvector(1024), ELO snapshot, predizione GP (mu, sigma2), actual reward. Indici HNSW + worker + created_at |
| V9 | (alter) `preference_pairs` | DPO GP Residual: `gp_residual FLOAT` nullable + indice DESC NULLS LAST per coppie ordinate per informatività |
| V20 | `audit_events` | Audit eventi persistenti (PostgreSQL): id, taskKey, tool, worker, session, occurredAt (TIMESTAMPTZ), raw (JSONB). Indici su task_key e occurred_at. Cleanup nightly (30 gg) |

## Feature avanzate

### Token Budget

Aggiungere un budget alla richiesta per limitare il consumo totale di token:

```json
POST /api/v1/plans
{
  "spec": "...",
  "budget": {
    "maxTotalTokens": 100000,
    "onExceeded": "NO_NEW_DISPATCH",
    "perWorkerType": { "BE": 40000 }
  }
}
```

`TokenBudgetService` somma i token da `plan_token_usage` prima di ogni dispatch. Se il limite
viene superato: `FAIL_FAST` → piano FAILED; `NO_NEW_DISPATCH` → piano PAUSED; `SOFT_LIMIT` → solo log.

### Event Sourcing ibrido e SSE

`PlanEvent` è un log append-only: ogni transizione di stato scrive un record con
`sequenceNumber` monotonicamente crescente. Il read-model (`plans`, `plan_items`) continua
ad essere aggiornato in-place nella stessa transazione (`Propagation.MANDATORY`).

**SSE late-join replay**: `SseEmitterRegistry.subscribe()` legge tutti i `PlanEvent` passati
via `PlanEventStore.findByPlanId()` e li invia al client prima di registrarlo per gli eventi
live. Il client può passare `Last-Event-ID` per ricevere solo gli eventi mancanti.

### Human Approval

Dispatch di un item con `riskLevel=CRITICAL` non va sul broker: l'orchestrator lo lascia in
`AWAITING_APPROVAL`. Il piano continua con gli altri item indipendenti.

| Endpoint | Effetto |
|----------|---------|
| `POST /{id}/items/{itemId}/approve` | AWAITING_APPROVAL → WAITING → dispatch (via `triggerDispatch`) |
| `POST /{id}/items/{itemId}/reject` | AWAITING_APPROVAL → FAILED (con `rejectionReason`) |

Con `requiredHumanApproval=NOTIFY_TIMEOUT`, un job schedulato controlla `approvalTimeoutMinutes`
e fa scadere l'approvazione in FAILED se non arriva risposta.

### Stale Task Detector

`StaleTaskDetectorScheduler` rileva task rimasti in stato `DISPATCHED` oltre il timeout configurabile
e li marca `FAILED` con `failureReason: "stale_timeout"`.

Configurazione in `application.yml`:
```yaml
agent.stale-detector:
  timeout-minutes: 30        # timeout default per tutti i worker type
  per-type:
    BE: 60                   # override per BE (task più lunghi)
```

Endpoint operativo per forzare la terminazione manuale:
`DELETE /api/v1/plans/{planId}/items/{itemId}` — transita DISPATCHED o WAITING → FAILED.

File chiave: `config/StaleDetectorProperties.java`, `config/StaleDetectorAutoConfiguration.java`,
`orchestration/StaleTaskDetectorScheduler.java`, `api/PlanController.java` (+`killItem`).

### Context Cache (SPI)

`ContextCacheStore` è un'SPI per cachare il contesto assemblato da `AgentContextBuilder`,
evitando di ricaricare skill e prompt identici per task dello stesso tipo.

- Default: `NoOpContextCacheStore` (nessuna cache — compatibile con qualsiasi worker).
- Override con Redis: fornire un bean `RedisContextCacheStore` che implementa `ContextCacheStore`.
- `ContextCacheHolder` (ThreadLocal) espone la cache al `ContextCacheInterceptor`.
- Cache key: `SHA-256(workerType + systemPromptFile + skillPaths)`.

### SUB_PLAN

Item di tipo `SUB_PLAN` vengono gestiti inline dall'orchestrator (nessun hop sul broker):

1. `handleSubPlan(item, plan)` verifica `plan.depth < maxDepth` (default: 3).
2. Chiama `PlannerService.decompose(subPlanSpec)` → crea piano figlio con `depth+1`.
3. Scrive `childPlanId` sull'item; dispatcha il figlio.
4. `@EventListener onChildPlanCompleted()`: alla terminazione del figlio, aggiorna l'item padre.

### COMPENSATOR_MANAGER

`createCompensationTask(itemId, reason)` crea un `PlanItem` di tipo `COMPENSATOR_MANAGER`
che punta allo stesso piano. Il worker dedicato riceve il task via topic e usa strumenti git
(`git_revert`, `git_checkout`, `git_stash`) per annullare le modifiche.

## Configurazione

Chiavi principali in `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/agentframework
    username: ${DB_USER:agentframework}
    password: ${DB_PASSWORD:}
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
messaging:
  provider: redis
  redis:
    host: ${REDIS_HOST:redis}
    database: 3

worker-profiles:
  profiles:
    be-java: { workerType: BE, topic: agent-tasks, subscription: be-java-worker-sub }
    # ... (caricato da config/worker-profiles.yml)
  defaults:
    BE: be-java
    FE: fe-react
```

## Build & Run

```bash
# Build
mvn clean install -pl control-plane/orchestrator

# Run (con Artemis e PostgreSQL locali)
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run -pl control-plane/orchestrator

# Run con profilo specifico
mvn spring-boot:run -pl control-plane/orchestrator -Dspring-boot.run.profiles=dev
```

## File chiave

| Path | Scopo |
|------|-------|
| `api/PlanController.java` | REST controller (17 endpoint: plan CRUD, retry, approve/reject, compensate, snapshots, SSE, graph, council) |
| `orchestration/OrchestrationService.java` | Logica di orchestrazione |
| `orchestration/WorkerProfileRegistry.java` | Registro profili con validazione @PostConstruct |
| `planner/PlannerService.java` | Decomposizione spec → plan via Claude |
| `messaging/AgentTaskProducer.java` | Dispatch task su topic |
| `messaging/AgentResultConsumer.java` | Consumo risultati worker |
| `messaging/dto/AgentTask.java` | DTO task dispatch |
| `messaging/dto/AgentResult.java` | DTO risultato worker |
| `domain/Plan.java` | Entity piano con state machine |
| `domain/PlanItem.java` | Entity item con state machine |
| `domain/DispatchAttempt.java` | Entity Command pattern |
| `domain/PlanSnapshot.java` | Entity Memento pattern |
| `specification/CompositeSpec.java` | Specification pattern (AND composito) |
| `event/*.java` | 4 Spring Application Events |
| `hooks/HookPolicy.java` | **@Deprecated stub** — usare `com.agentframework.common.policy.HookPolicy` |
| `reward/RewardComputationService.java` | Logica core: processScore + reviewScore + qualityGateScore + aggregazione Bayesiana |
| `reward/EloRatingService.java` | Aggiornamento ELO pairwise per piano (K=32) — eseguito una volta a piano completato |
| `reward/WorkerEloStats.java` | Entity ELO rating per profilo worker (`worker_elo_stats`) |
| `reward/WorkerEloStatsRepository.java` | Repository JPA; `findAllByOrderByEloRatingDesc()` per leaderboard |
| `reward/PreferencePairGenerator.java` | Generazione DPO pair: 3 strategie (cross-profile + retry + gp_residual_surprise) |
| `reward/PreferencePair.java` | Entity coppia preferenza DPO (`preference_pairs`, +campo `gpResidual` per informativity score) |
| `reward/PreferencePairRepository.java` | Repository con query `findByMinDelta`, `findByGpResidualDesc` |
| `gp/TaskOutcomeService.java` | Bridge GP↔Orchestrator: embed task, predict reward, record outcome at dispatch, update reward at completion |
| `gp/TaskOutcomeRepository.java` | Repository native pgvector: insert embedding, load training data, update reward |
| `gp/TaskOutcome.java` | Entity GP training (`task_outcomes`): embedding, ELO snapshot, predizione, actual reward |
| `gp/GpWorkerSelectionService.java` | Selezione profilo adattiva via GP UCB (esplorazione-sfruttamento); condizionale su `gp.enabled` |
| `reward/RewardController.java` | REST `/api/v1/rewards/*` — 3 endpoint NDJSON export (reward records, ELO stats, DPO pairs) |
| `hooks/HookManagerService.java` | Cache policy per-plan; `storePolicies` / `resolvePolicy` / `evictPlan` |
| `hooks/HookPolicyResolver.java` | Fallback statico: risolve `HookPolicy` per `WorkerType` da `WorkerProfileRegistry` |
| `hooks/AuditManagerService.java` | `@RestController` `/audit` — riceve e storicizza eventi audit da `audit-log.sh` su PostgreSQL (`AuditEventRepository`); cleanup `@Scheduled` nightly (30 gg); `GET /events?taskKey=` per filtro |
| `hooks/EventManagerService.java` | `@RestController` `/events` — tracker violazioni hook per task; `POST /violation`, `GET /violations` |
| `eventsourcing/PlanEvent.java` | Entity append-only per event sourcing + SSE replay |
| `eventsourcing/PlanEventStore.java` | `append()` con `Propagation.MANDATORY`; `findByPlanId()` per replay |
| `sse/SseEmitterRegistry.java` | Registry SSE emitter con late-join replay dei `PlanEvent` passati |
| `council/CouncilService.java` | Orchestrazione sessioni council: pre-planning (globale) + task-level (per-item) |
| `council/CouncilReport.java` | Record 8 campi (architectureDecisions, securityConsiderations, testingStrategy, etc.) |
| `council/CouncilProperties.java` | Config: enabled, max-members, pre-planning-enabled, task-session-enabled |
| `config/AsyncConfig.java` | Thread pool condiviso `orchestratorAsyncExecutor` per quality gate e snapshot |
| `service/PlanSnapshotListener.java` | Listener asincrono: crea snapshot a `PlanCreated` e `PlanCompleted` |
| `orchestration/QualityGateService.java` | Genera report quality gate via LLM al completamento piano (asincrono) |
