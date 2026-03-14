# Fase 2 — Orchestrator + Monitoring (#19-#29)

> Leader election, monitoring dashboard, persistent audit, provenance, worker lifecycle,
> observability gaps. Sessioni S12-S14.

---

## Sessione 12 — Leader Election (#22) + Monitoring Dashboard (#28) ✅ COMPLETATA

**Data:** 2026-03-08
**Test:** 734 orchestrator (0 fallimenti) — +9 nuovi test S12

### Fase A — #22 Leader Election

**Obiettivo:** Multi-istanza sicura — un solo orchestratore dispatcha task via Redis Streams.

**File creati:**
- `leader/LeaderAcquiredEvent.java` — record evento acquisizione leadership
- `leader/LeaderLostEvent.java` — record evento perdita leadership
- `leader/LeaderElectionService.java` — heartbeat `@Scheduled` ogni 10s, Redis `SET NX PX 30000`, rinnovo TTL se gia' owner, demote se altro owner. `@ConditionalOnProperty(matchIfMissing=true)`.
- `leader/LeaderElectionServiceTest.java` — 3 test (become leader, renew TTL, remain follower)

**File modificati:**
- `AgentResultConsumer` — `@EventListener(LeaderAcquiredEvent)` → start container; `@EventListener(LeaderLostEvent)` → stop container; `@PostConstruct` condizionale
- `OrchestrationService` — `Optional<LeaderElectionService>` iniettato; guard in `dispatchReadyItems()` se non-leader
- `OrchestrationServiceTest` / `MissingContextPropagationTest` — aggiunto `Optional.empty()` per nuovo param
- `application.yml` — `orchestrator.leader-election.enabled/ttl-ms/refresh-ms`

### Fase B — #28 Monitoring Dashboard

**Obiettivo:** Dashboard live per osservare piani, task e stream SSE in tempo reale.

**File modificati:**
- `domain/PlanStatus.java` — aggiunto `CANCELLED` (terminale); transizioni da RUNNING, PAUSED
- `domain/ItemStatus.java` — aggiunto `CANCELLED` (terminale); transizioni da WAITING, AWAITING_APPROVAL
- `event/SpringPlanEvent.java` — aggiunto campo `extraJson` (nullable); nuove costanti `PLAN_CANCELLED`, `ITEM_STATUS_CHANGED`, `BUDGET_UPDATE`; factory `forItemStatus()`, `forSystem()`, `forBudgetUpdate()`
- `sse/SseEmitterRegistryTest.java` — aggiornato costruttore record (9° arg `null`)
- `OrchestrationService` — `cancelPlan()` @Transactional; emissione `TASK_DISPATCHED` come `SpringPlanEvent` (SSE gap fix)
- `repository/PlanRepository.java` — aggiunto `findAllByOrderByCreatedAtDesc()` + `findByStatusOrderByCreatedAtDesc()`
- `api/PlanController.java` — `GET /api/v1/plans` (list paginato); `POST /{id}/cancel`
- `build.sh` — versione plugin corretta da `1.0.0-SNAPSHOT` a `1.1.0-SNAPSHOT`

**File creati:**
- `api/PlanControllerListTest.java` — 2 test (empty list, status filter)
- `orchestration/CancelPlanTest.java` — 2 test (WAITING → CANCELLED, DISPATCHED invariato)
- `resources/static/monitoring.html` — Dashboard a 4 pannelli: Plan Selector (lista recenti + input manuale + cancel), DAG Live (Mermaid.js + colori status + click nodo), Event Stream (SSE feed con badge e filtri per tipo), Worker Detail (attempt history) + Stats (progress bar, budget per workerType, ELO top-5)

### Note tecniche

- **SSE gap**: `TASK_DISPATCHED` era pubblicato solo come `PlanItemDispatchedEvent` (Spring interno), non come `SpringPlanEvent` → il dashboard SSE non lo riceveva mai. Aggiunto publish parallelo.
- **Record immutability**: aggiungere `extraJson` al record `SpringPlanEvent` ha richiesto aggiornare tutti i call site dei costruttori diretti.
- **DISPATCHED items nel cancel**: `DISPATCHED` non ha `CANCELLED` nelle transizioni consentite → il worker continua naturalmente fino al completamento.
- **Plugin version mismatch**: `build.sh` usava `1.0.0-SNAPSHOT` mentre il plugin e' `1.1.0-SNAPSHOT` → corretto.

---

## S13 — Persistent Audit + Provenance Reasoning + Stale Task Detector (2026-03-08)

### Feature G5 — Persistent Audit (PostgreSQL)

**Problema risolto**: `AuditManagerService` perdeva tutti gli eventi audit al restart
del container orchestratore (in-memory CopyOnWriteArrayList, max 10k eventi).

**Implementazione**:
- `AuditEvent` — JPA entity con campi: id (BIGSERIAL), taskKey, tool, worker, session,
  occurredAt (TIMESTAMPTZ), raw (JSONB con payload completo)
- `AuditEventRepository` — Spring Data JPA con query derivata `findByTaskKeyOrderByOccurredAtDesc`
  e `@Transactional deleteByOccurredAtBefore(Instant)` per cleanup
- `V20__audit_events.sql` — DDL + 2 indici (task_key, occurred_at)
- `AuditManagerService` — riscritta: da CopyOnWriteArrayList a JPA;
  `@Scheduled(cron = "0 0 3 * * *")` cleanup eventi > 30 giorni;
  `GET /audit/events` ora legge da DB (filtrabile per taskKey)

**File creati** (3):
- `orchestrator/src/main/java/.../orchestrator/audit/AuditEvent.java`
- `orchestrator/src/main/java/.../orchestrator/audit/AuditEventRepository.java`
- `orchestrator/src/main/resources/db/migration/V20__audit_events.sql`

**File modificati** (1):
- `orchestrator/src/main/java/.../orchestrator/hooks/AuditManagerService.java`

---

### Feature G2 — Decision Provenance (reasoning)

**Problema risolto**: Il ragionamento LLM (primo blocco testo prima di qualsiasi tool call)
veniva perso — visibile nell'output ma non catturato nel result.

**Implementazione**:
- Campo `String reasoning` aggiunto come 13° campo al record `Provenance` (max 2000 char)
- `ThreadLocal<String> REASONING` in `AbstractWorker` — stesso pattern di `TOKEN_USAGE`
- Helper `captureReasoning(String text)` — idempotente, truncate a 2000 char
- Success path: `REASONING.get()` come 13° argomento del costruttore `Provenance`
- Error path: `null` (nessun reasoning in caso di eccezione)

**File modificati** (3):
- `worker-sdk/src/main/java/.../worker/dto/Provenance.java` (+ reasoning, 13° campo)
- `orchestrator/src/main/java/.../messaging/dto/Provenance.java` (mirror)
- `worker-sdk/src/main/java/.../worker/AbstractWorker.java` (+REASONING ThreadLocal)

---

### Stale Task Detector

Task rimasti DISPATCHED oltre il timeout configurabile vengono rilevati e marcati FAILED.

**File creati** (2):
- `StaleDetectorProperties.java`
- `StaleDetectorAutoConfiguration.java`

**File modificati** (2):
- `StaleTaskDetectorScheduler.java`
- `PlanController.java` (+ killItem endpoint)

---

### Fix bug

- `OrchestrationService.killItem()`: `IllegalStateTransitionException` firma errata (1 arg vs 4)
- Test analytics (5 file): `List.<Object[]>of()` con type hint per Mockito 5.x
- `StaleTaskDetectorSchedulerTest`: aggiornato costruttore con `StaleDetectorProperties`

### Test

| Modulo | Tests run | Failures |
|--------|-----------|----------|
| worker-sdk | 39 | 0 |
| orchestrator (OrchestrationService + OrchestratorMetrics) | 45 | 0 |

**Nuovi test** (4): `ProvenanceModelTest`, `KillItemTest`, `CostEstimationModelTest`, `StaleDetectorPropertiesTest`.

**Commit**: `67008c1`, `0e21aef`, `827814d`

---

## Sessione 14 — Monitoring Dashboard + Observability Gaps + Worker Lifecycle (2026-03-08)

### Items completati

- **#28 Monitoring Dashboard**: UI con 4 pannelli (DAG Live, Event Stream, Worker Detail, Stats con token bars)
- **G1 Conversation History**: JSONB in `dispatch_attempts` (V21 migration)
- **G3 File Modification Tracking**: SHA-256 hashing, `FileModification` entity (V22 migration)
- **G6 Worker Event Pipeline**: Redis stream `agent-events` (TOOL_CALL_START/END, TOKEN_UPDATE)
- **#29 Worker Lifecycle Phase 1b**: JVM consolidation per in-process worker execution
- **#29 Worker Lifecycle Phase 2**: hybrid deployment con REST dispatch + HTTP callback

### File chiave

- `MonitoringDashboardController.java` + `monitoring-dashboard.html` (dashboard)
- `DispatchAttempt.java` alterato: campo JSONB `conversationHistory` (G1)
- `FileModification.java` + `FileModificationRepository.java` (G3)
- `WorkerEventPublisher.java` + `WorkerEventConsumer.java` (G6)
- `InProcessWorkerDispatcher.java` + `HttpCallbackWorkerDispatcher.java` (#29)

### Test

2102 test totali, 0 fallimenti.

**Commit**: `c17757e`, `527e0aa`, `dda9a5c`
