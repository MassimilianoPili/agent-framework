# Fase 3 — Mathematical Foundations (#30-#43)

> Hash chain, Ed25519, token economics, LTL verification, Shapley values,
> Hungarian algorithm, policy lattice, PID controller. Sessioni S15-S19.

---

## Sessione 15 — Context Cache + Quality Scoring + Adaptive Budget (2026-03-09)

### Items completati

- **#7 RedisContextCacheStore**: implementazione SPI `ContextCacheStore` backed by Redis, `@ConditionalOnBean(StringRedisTemplate)`, TTL 30min. Sostituisce `NoOpContextCacheStore`.
- **#35 Context Quality Scoring**: `ContextQualityService` — information-theoretic feedback (MI proxy + entropy penalty). Persiste in `task_outcomes`, alimenta `RewardComputationService` come 4a sorgente (peso 0.15).
- **#37 Adaptive Token Budget**: PID controller per `HookPolicy.maxTokenBudget`. Feedback loop in-memory (ConcurrentHashMap), evict on plan completion. Zero costo LLM.

### File chiave

- `RedisContextCacheStore.java` + `ContextCacheInterceptorTest.java` (15 test) — #7
- `ContextQualityService.java` + V23 migration + `RewardComputationService` 4-source weights (0.45/0.25/0.15/0.15) — #35
- `AdaptiveTokenBudgetService.java` + `BudgetPidProperties.java` — #37

### Test

+25 test netti. Tutti passing.

**Commit**: `63b42b2`, `c598879`, `50205d0`

---

## Sessione 16 — Token Economics + DAG Shapley + TOOL_MANAGER (2026-03-09)

### Items completati

- **#33 Token Economics Ledger**: double-entry append-only ledger (DEBIT/CREDIT). Domain worker → credit proporzionale a `aggregatedReward`; infra worker → cost-only. Efficiency ratio = credits/debits. V24 migration.
- **#40 DAG-aware Shapley Value**: Monte Carlo random permutations con DAG-constrained coalition value function `v(S)`. Distribuisce credito a infra worker che "sbloccano" successori. V25 migration (`shapley_value` su `plan_items`).
- **#24 L2 TOOL_MANAGER**: enrichment worker per per-task HookPolicy via Haiku. Fan-out in `EnrichmentInjectorService`. Policy resolution: TM > HM > Static fallback.

### File chiave

- `TokenLedger.java` + `TokenLedgerService.java` + `TokenLedgerRepository.java` (14 test) — #33
- `ShapleyDagService.java` + side-effect #9 in `TaskCompletedEventHandler` (13 test) — #40
- `tool-manager.agent.yml` + `EnrichmentInjectorService.java` alterato + `EnrichmentProperties.java` (27 test) — #24L2

### Test

+54 test netti. Tutti passing.

**Commit**: `46357a6`, `8ffcd3e`, `9b0905c`

---

## Sessione 17 — Auto-Split + Hash Chain + LTL + Policy Lattice (2026-03-09)

### Items completati

- **#26 L2 Auto-Split**: `TaskSplitterService` valuta token stimati pre-dispatch. Azioni: PROCEED/WARN/SPLIT/BLOCK. SPLIT converte task in SUB_PLAN. GP uncertainty boost 1.5× quando σ² alto. V26 migration.
- **#30 Hash Chain**: SHA-256 chain su `PlanEvent` per tamper detection.
- **#38 State Machine Verification**: LTL-style checks su transizioni di stato del piano.
- **#33 Token Observability**: Prometheus metrics (`orchestrator.ledger.debit/credit`), burn rate, low-efficiency alert. `TokenLedgerProperties` @ConfigurationProperties.
- **#39 Policy Lattice**: join-semilattice per merging `HookPolicy`. `PolicyLattice.java` (229 righe test).

### File chiave

- `TaskSplitterService.java` + `TaskSplitProperties.java` + V26 (18 test) — #26L2
- `PlanEventStore.java` alterato: hash chain append — #30
- `StateMachineVerifier.java` — #38
- `TokenLedgerService.java` riscritto (4 deps) + `TokenLedgerProperties.java` + Prometheus metrics (25 test) — #33
- `PolicyLattice.java` + test (229 righe) — #39

### Test

955 test totali, 0 fallimenti.

**Commit**: `121c645`, `66ccd20`

---

## Sessione 18 — Hungarian Algorithm + Self-Organized Criticality (2026-03-09)

### Items completati

- **#42 Hungarian Algorithm**: Kuhn-Munkres O(n³) minimum-cost bipartite assignment per globally optimal task-to-profile matching. `HungarianAlgorithm` standalone + `GlobalAssignmentSolver` (cost matrix da GP). Integration in `OrchestrationService` (pre-assign batch). Preview endpoint `GET /{id}/assignment-preview`.
- **#56 Self-Organized Criticality**: `SandpileSimulator` configurabile, `CriticalityMonitor` migrato a `CriticalityProperties` record. 3 Prometheus metrics. Endpoint analytics.

### File chiave

- `HungarianAlgorithm.java` + `GlobalAssignmentSolver.java` + `AssignmentResult.java` (15 test) — #42
- `SandpileSimulator.java` + `CriticalityMonitor.java` + `CriticalityProperties.java` — #56

### Test

987 test totali (+32 netti), 0 fallimenti.

**Commit**: `e2b36a4`, `f484ab3`

---

## Sessione 19 — Ed25519 Crypto + Policy Commitment (2026-03-09)

### Items completati

- **#31 Ed25519 Cryptographic Signing**: worker results firmati Ed25519 all'execution plane, verificati all'orchestratore. Key registration API, TOFU trust model, per-worker crypto config.
- **#32 Policy Commitment Hash**: `PolicyHasher` computa SHA-256 canonico di `HookPolicy` a storage time. Hash propagato in `AgentTask.policyHash`, verificato da `PolicyEnforcingToolCallback` prima di ogni tool call. Mismatch → `POLICY_TAMPERED` denial + audit log.

### File chiave

- `Ed25519Signer.java` + `Ed25519Verifier.java` + `WorkerKeyRegistryService.java` — #31
- `PolicyHasher.java` + `PolicyEnforcingToolCallback.java` alterato — #32

### Test

Tutti passing.

**Commit**: `1f249e7`, `22a6f79`
