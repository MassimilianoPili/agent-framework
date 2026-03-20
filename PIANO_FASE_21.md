# Fase 21 — Domain Specialization & Production Integration (#187-#198)

## Context

Fase 20 (Execution Grounding) ha dato al framework le gambe: sandbox, git safety, compile-test-fix, cross-plan learning. Fase 21 è il passaggio da "framework che funziona internamente" a "framework che produce valore misurabile su progetti reali". Il tema unificante: **ogni item chiude un gap operativo visibile** — worker mancanti, servizi placeholder, analytics non collegati, hardware non sfruttato.

Gap principali che Fase 21 colma:
- **Worker roster incompleto**: nessun worker TESTING, SECURITY, DOCUMENTATION (22 WorkerType ma 0 per QA/sicurezza/docs)
- **TrackerSyncService placeholder**: riga 51 `// TODO: invoke tracker-mcp tool when available` — piani invisibili al mondo esterno
- **118 analytics services**: 12 dark bean compilati+testati ma mai invocati in produzione (A2 Fasi 2-4 pending dal 15 marzo)
- **RAG Pipeline disconnesso**: `RAG_MANAGER` WorkerType esiste, rag-engine ha 50+ file, mai collegato a EnrichmentInjector
- **GPU Gaia inutilizzato**: RTX 3090 pronto, embedding ancora su CPU a 1024 dim

**Effort totale**: 27.0g | **Items**: #187-#198 (12 items)

---

## Sub-fase 21a — Worker Specialization (8.5g)

### #187 — Testing & QA Worker (3.0g, Tier 0)

**Problema**: Il framework produce codice ma non test. REVIEW verifica qualità, nessun worker genera test suite (unit, integration, e2e, property-based).

**Servizio**: `TestingQaWorkerService`

**Approccio**:
- Nuovo `WorkerType.TESTING` in [WorkerType.java](control-plane/orchestrator/src/main/java/com/agentframework/orchestrator/domain/WorkerType.java)
- 4 profili: `testing-unit`, `testing-integration`, `testing-e2e`, `testing-property`
- `EnrichmentInjectorService` inietta TESTING come dipendenza automatica di REVIEW
- Integrazione con `CompileTestFixLoopService` (#186) per validare i test generati
- Output: test suite + coverage report in formato strutturato JSON

**File**:
- `execution-plane/workers/testing-worker/` (NEW module)
- `agents/manifests/testing-*.agent.yml` (4 manifest)
- `prompts/testing.prompt.md` (NEW)
- `EnrichmentInjectorService.java` (MOD — add TESTING injection)

**Paper**: CodaMosa (ICSE 2023), LIBRO (ICSE 2024), TestPilot (FSE 2024)

**Dipendenza**: #186 (CompileTestFix) per execution loop

---

### #188 — Security Analysis Worker (3.0g, Tier 0)

**Problema**: SecretScanner (#167) e PromptInjectionDetector (#137) coprono minacce specifiche, ma nessun worker fa analisi SAST-like sistematica: OWASP, injection, dependency CVE, config hardening.

**Servizio**: `SecurityAnalysisWorkerService`

**Approccio**:
- Nuovo `WorkerType.SECURITY`
- 3 profili: `security-sast` (injection, XSS, SSRF — output SARIF), `security-dependency` (CVE, licenze), `security-config` (secrets in env, TLS, CORS)
- SECURITY task iniettato dopo domain workers, prima di REVIEW
- Findings JSON con `severity`, `cweId`, `affectedFile`, `lineRange`, `recommendation`

**File**:
- `execution-plane/workers/security-worker/` (NEW module)
- `agents/manifests/security-*.agent.yml` (3 manifest)
- `prompts/security.prompt.md` (NEW)

**Paper**: PurpleLlama CyberSecEval (2024), SVEN (ICLR 2023), SecCodePLT (2025)

---

### #189 — Documentation Worker (2.5g, Tier 1)

**Problema**: Nessun worker genera documentazione: README, OpenAPI spec, ADR, CHANGELOG. CommentAnalyzer (#173) verifica coerenza post-hoc, non produce docs.

**Servizio**: `DocumentationWorkerService`

**Approccio**:
- Nuovo `WorkerType.DOCUMENTATION`
- 4 profili: `doc-api` (OpenAPI 3.1), `doc-readme`, `doc-adr`, `doc-changelog`
- Output validato strutturalmente (OpenAPI con swagger-parser, Markdown con linter)
- Integrazione con CommentAnalyzer per consistenza

**File**:
- `execution-plane/workers/documentation-worker/` (NEW module)
- `agents/manifests/doc-*.agent.yml` (4 manifest)
- `prompts/documentation.prompt.md` (NEW)

---

## Sub-fase 21b — Tracker & CI/CD Integration (7.0g)

### #190 — Tracker Sync Implementation (3.0g, Tier 0)

**Problema**: [TrackerSyncService.java:51](control-plane/orchestrator/src/main/java/com/agentframework/orchestrator/tracker/TrackerSyncService.java#L51) è un placeholder `// TODO`. I piani non creano issue, non aggiornano stato, non commentano risultati.

**Servizio**: `TrackerSyncService` (completamento)

**Approccio**:
- `TrackerProvider` interface + `GiteaTrackerProvider` (via mcp-gitea-tools, 17 tool) + `JiraTrackerProvider` (via mcp-jira-tools)
- Mapping: `Plan` → Gitea Milestone / Jira Epic; `PlanItem` → Issue / Task
- Eventi: `PLAN_CREATED` → crea milestone, `TASK_DISPATCHED` → crea issue, `TASK_COMPLETED` → chiude issue, `TASK_FAILED` → commento errore
- Idempotency keys per retry senza duplicazioni
- Config: `tracker.provider=gitea|jira|none`, `tracker.project`

**File**:
- `tracker/TrackerSyncService.java` (REWRITE)
- `tracker/TrackerProvider.java` (NEW interface)
- `tracker/GiteaTrackerProvider.java` (NEW)
- `tracker/JiraTrackerProvider.java` (NEW)

**Flyway**: V43 — `ALTER TABLE plan_items ADD COLUMN external_issue_id TEXT; ALTER TABLE plans ADD COLUMN external_milestone_id TEXT;`

**Dipendenza**: #184 (ExternalIntegrationHub) per MCP tool dispatch

---

### #191 — CI/CD Pipeline Trigger & Feedback (2.5g, Tier 0)

**Problema**: Il framework committa codice (#185 git safety, #186 compile-test-fix) ma non triggera CI/CD e non consuma risultati. Piani che compilano localmente possono fallire in CI. Ghaleb (MSR 2026): solo 3.25% degli agenti tocca CI/CD.

**Servizio**: `CiCdIntegrationService`

**Approccio**:
- Trigger via `gitea_trigger_workflow` MCP tool
- Polling via `gitea_get_workflow_run` per stato
- Output: `CiCdResult { status, logs, failedSteps, duration }`
- Se FAILURE → genera PlanItem correttivo automatico via CompileTestFixLoop
- Webhook inbound opzionale: `POST /api/v1/plans/{id}/ci-callback`
- Config: `cicd.auto-trigger=true`, `cicd.poll-interval=30s`, `cicd.max-wait=15m`

**File**:
- `cicd/CiCdIntegrationService.java` (NEW)
- `cicd/CiCdResult.java` (NEW record)
- `api/PlanController.java` (ADD webhook endpoint)

**Dipendenza**: #184 + #185

---

### #192 — RAG Pipeline Orchestration Wiring (1.5g, Tier 0)

**Problema**: `RAG_MANAGER` WorkerType esiste (WorkerType.java:43-60), `shared/rag-engine/` ha 50+ file con hybrid search + reranking, ma `EnrichmentInjectorService` non inietta RAG_MANAGER. Il RAG costruito in Fasi 1-3 non è mai stato usato.

**Servizio**: `RagOrchestrationWiringService`

**Approccio**:
- `enrichment.rag.enabled=true` in `EnrichmentProperties`
- `enrichment.rag.min-chunks` soglia: se vectorstore ha < N chunk, skip
- DAG arricchito: `CM → RAG → TM → [domain workers]`
- Ingestion trigger: `FileWatcherService` (rag-engine) aggiorna vectorstore dopo commit

**File**:
- `config/EnrichmentInjectorService.java` (MOD — add RAG injection)
- `config/EnrichmentProperties.java` (MOD — add `rag.*`)
- `execution-plane/workers/rag-manager-worker/` (VERIFY/FIX)

**Dipendenza**: nessuna (infrastruttura Fasi 1-3 completa)

---

## Sub-fase 21c — A2 Dark Bean Integration Fasi 2-4 (6.0g)

### #193 — Reward Pipeline Integration — A2 Fase 2 (2.0g, Tier 1)

**Problema**: `RewardComputationService` calcola reward aggregato ma ignora 5 analytics: `ShapleyValueService`, `PotentialRewardShapingService`, `ProspectTheoryService`, `GoodhartDetectorService`, `CausalShapleyService`. Servizi compilati, testati, mai invocati.

**Servizio**: `RewardAdvisorFacade`

**Approccio**:
- Facade pattern (come `DispatchAdvisorFacade`) con 5 servizi `@Nullable`
- Shapley → decomposizione contributo per-worker
- PotentialRewardShaping → bonus/malus convergenza
- ProspectTheory → calibrazione loss aversion
- GoodhartDetector → penalità se metrica singola migliora ma altre degradano
- CausalShapley → attribuzione causale successo/fallimento
- Output: `AugmentedReward { baseReward, shapleyDecomp, shapingBonus, prospectCalibrated, goodhartPenalty }`

**File**:
- `reward/RewardAdvisorFacade.java` (NEW)
- `reward/RewardComputationService.java` (MOD)

**Contributo originale**: combinazione multi-theory (Shapley + Prospect Theory + Goodhart detection) nel reward pipeline di un framework agente. Letteratura tratta ciascuno separatamente.

---

### #194 — Budget & Council Integration — A2 Fasi 3-4 (2.0g, Tier 1)

**Problema**: Budget e Council non consultano i rispettivi analytics. PidBudgetController ignora ErgodicBudgetAnalyzer. Council non usa SycophancyDetector né CouncilDiversityService.

**Servizio**: `BudgetCouncilIntegration`

**Approccio budget** (3 servizi):
- `ErgodicBudgetAnalyzer` → signal al PidBudgetController (regime non-ergotico → allargare limiti)
- `ErrorBudgetCalculator` → SLO-based cap (budget errore esaurito → blocca dispatch)
- `RealOptionsService` → consiglio defer/accelerate basato su opzioni reali

**Approccio council** (4 servizi):
- `CouncilDiversityService` → penalizzare membership troppo omogenea
- `SycophancyDetectorService` → segnalare quando tutti i membri concordano
- `VotingProtocolService` → aggregazione voti strutturata
- `CalibrationAuditService` → tracciare quanto le previsioni Council si avverano

**File**:
- `budget/PidBudgetController.java` (MOD)
- `council/CouncilService.java` (MOD)
- `council/CouncilAdvisorFacade.java` (NEW)

---

### #195 — Process Mining & Observability Integration — A2 Fase 2b (2.0g, Tier 1)

**Problema**: `ProcessMiningService`, `SloTracker`, `ConvergenceMonitor`, `WorkerDriftMonitor`, `RootCauseAnalyzer` esistono ma non sono listener di eventi. Piani completano senza analisi dei pattern di esecuzione.

**Servizio**: `ProcessMiningEventListener` + `SloEventListener`

**Approccio**:
- Event-driven: ogni servizio si sottoscrive a `SpringPlanEvent` e aggiorna stato interno
- `RootCauseAnalyzer` invocato automaticamente su `PLAN_FAILED`
- `ConvergenceMonitor` traccia se GP posterior variance decresce nel tempo
- `WorkerDriftMonitor` rileva degradazione performance per worker profile
- Endpoint: `GET /api/v1/analytics/process-mining`, `GET /api/v1/analytics/slo`

**File**:
- `analytics/ProcessMiningEventListener.java` (NEW)
- `analytics/SloEventListener.java` (NEW)
- `api/AnalyticsController.java` (NEW)

---

## Sub-fase 21d — Domain Application & GPU (5.5g)

### #196 — COBOL Worker Bootstrap (2.5g, Tier 1)

**Problema**: 220 miliardi di righe COBOL in produzione (banche, PA, assicurazioni). Il framework ha 14 profili BE ma nessuno per linguaggi legacy. PIANO_AGENT_COBOL.md descrive il piano completo.

**Servizio**: `CobolWorkerBootstrap`

**Approccio**:
- Nuovo profilo `be-cobol` sotto `WorkerType.BE` (routing per workerProfile)
- Container con GnuCOBOL + JDK 21 per compilazione e validazione incrociata
- Prompt template specializzato (divisioni, WORKING-STORAGE, PERFORM, EVALUATE)
- Integrazione con ExecutionRuntimeOrchestrator (#177) per compilare COBOL e Java
- Test con sample programs (batch, CICS, embedded SQL)

**Contributo originale**: nessun framework agente open-source ha worker COBOL con compilazione in-loop e parallel-run validation. IBM watsonx Code Assistant for Z è proprietario.

**File**:
- `execution-plane/workers/be-cobol-worker/` (NEW module)
- `docker/cobol-runtime/Dockerfile` (NEW — GnuCOBOL + JDK 21)
- `agents/manifests/be-cobol.agent.yml` (NEW)
- `prompts/be-cobol.prompt.md` (NEW)

**Dipendenza**: #177 (ExecutionRuntime) per container pool

---

### #197 — GPU Coprocessor Integration (2.0g, Tier 1)

**Problema**: Embedding su CPU (mxbai-embed-large 1024 dim). Server Gaia con RTX 3090 pronto (Tailscale 100.109.3.40, Ollama con GPU passthrough, socat proxy su SOL → localhost:11434). Bottleneck: embedding e reranking RAG.

**Servizio**: `GpuCoprocessorService`

**Approccio**:
- Upgrade modello: `qwen3-embedding:8b` (4096 dim, MRL) — nessun cambio codice per endpoint (socat mantiene localhost:11434)
- Batch embedding: `IngestionPipeline` invia chunk in batch da 32
- Reranking: upgrade a qwen3:8b su GPU — 10x più veloce che CPU
- Config: `rag.embedding.dimensions=4096` (configurabile via MRL)
- Principio di inesorabilità: reindex notturno batch, servizio non bloccato

**File**:
- `shared/rag-engine/.../config/RagProperties.java` (MOD — dimensioni configurabili)
- `shared/rag-engine/.../config/PgVectorStoreConfig.java` (MOD — dimensioni dinamiche)
- `shared/rag-engine/.../ingestion/IngestionPipeline.java` (MOD — batch embedding)

**Flyway**: V44 — vector store upgrade a 4096 dimensioni (colonna parallela + reindex progressivo)

---

### #198 — Multi-Tenant Rate Limiting & Quotas (1.0g, Tier 1)

**Problema**: Nessun rate limiting. Qualsiasi utente può creare piani illimitati e consumare token illimitati. TenantIsolationService (#138) gestisce isolamento dati, non throttling.

**Servizio**: `RateLimitQuotaService`

**Approccio**:
- Token bucket in Redis DB 3: `rate:{tenantId}:plans`, `rate:{tenantId}:tokens`, `rate:{tenantId}:tasks`
- Default: `quota.max-concurrent-plans=3`, `quota.max-tokens-per-day=1_000_000`, `quota.max-tasks-per-hour=100`
- Check in `OrchestrationService.createAndStart()` e `dispatchReadyItems()`
- HTTP 429 con `Retry-After`

**File**:
- `quota/RateLimitQuotaService.java` (NEW)
- `quota/QuotaProperties.java` (NEW)
- `orchestration/OrchestrationService.java` (MOD — add check)

---

## Riepilogo

| # | Titolo | Sforzo | Sub-fase | Dipendenza F20 |
|---|--------|--------|----------|----------------|
| 187 | Testing & QA Worker | 3.0g | 21a | #186 |
| 188 | Security Analysis Worker | 3.0g | 21a | — |
| 189 | Documentation Worker | 2.5g | 21a | — |
| 190 | Tracker Sync Implementation | 3.0g | 21b | #184 |
| 191 | CI/CD Pipeline Trigger | 2.5g | 21b | #184+#185 |
| 192 | RAG Pipeline Wiring | 1.5g | 21b | — |
| 193 | Reward Pipeline (A2F2) | 2.0g | 21c | — |
| 194 | Budget & Council (A2F3-4) | 2.0g | 21c | — |
| 195 | Process Mining (A2F2b) | 2.0g | 21c | — |
| 196 | COBOL Worker Bootstrap | 2.5g | 21d | #177 |
| 197 | GPU Coprocessor Integration | 2.0g | 21d | — |
| 198 | Rate Limiting & Quotas | 1.0g | 21d | — |
| | **Totale** | **27.0g** | | |

## Ordine implementazione

```
Fase 21a (worker specialization, 8.5g):       #187 → #188 → #189
Fase 21b (tracker & CI/CD, 7.0g):             #192 → #190 → #191
Fase 21c (A2 dark bean Fasi 2-4, 6.0g):       #193 → #194 → #195
Fase 21d (domain & GPU, 5.5g):                #197 → #196 → #198
```

**Parallelismo**: 21c e 21d non dipendono da Fase 20 — possono partire immediatamente. 21a dipende da #186, 21b da #184.

## Flyway Migrations

- **V43**: `external_issue_id` (TEXT) su `plan_items`, `external_milestone_id` (TEXT) su `plans`
- **V44**: vector store 4096 dim (colonna parallela + reindex progressivo)

## Nuovi WorkerType

```java
TESTING,       // #187 — testing-unit, testing-integration, testing-e2e, testing-property
SECURITY,      // #188 — security-sast, security-dependency, security-config
DOCUMENTATION  // #189 — doc-api, doc-readme, doc-adr, doc-changelog
```

WorkerType: da 22 a 25. Profili worker: da 47 a ~58.

## Contributi originali

1. **#187+#186**: Closed-loop test generation con execution feedback in framework multi-agente (letteratura genera test in singolo modello)
2. **#190**: Sincronizzazione bidirezionale plan-to-tracker con idempotency (SWE-Agent/OpenHands operano su issue singole)
3. **#193+#194**: Multi-theory reward shaping (Shapley + Prospect Theory + Goodhart detection) — letteratura tratta ciascuno separatamente
4. **#196**: COBOL worker con parallel-run validation — non esiste in framework open-source

## Verifica

1. **Build**: `mvn clean install -DskipTests` deve compilare con i 3 nuovi moduli worker
2. **Flyway**: `V43` e `V44` applicati senza errore su `embeddings` DB
3. **Integration test**: TrackerSyncService con Gitea mock — crea milestone + issue
4. **RAG wiring**: piano con `enrichment.rag.enabled=true` → RAG_MANAGER task creato nel DAG
5. **Dark bean**: `RewardAdvisorFacade` invocato → `AugmentedReward` nel log
6. **E2E**: piano completo con spec "Build REST API" → task TESTING + SECURITY generati automaticamente
