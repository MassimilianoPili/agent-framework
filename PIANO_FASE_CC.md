# Fase CC — Claude Code Patterns per Agent Framework (CC-01 → CC-13)

## Context

La pagina WikiJS [Pattern Claude Code → Agent Framework](https://wiki.massimilianopili.com/en/agent-framework/architecture/claude-code-patterns) mappa 28 pattern architetturali di Claude Code che il framework può adottare. Stato attuale: ✅ 4, 🔧 7, ❌ 17. Dopo esclusione di pattern già coperti da Fasi 19-21, restano **13 pattern in 5 unità logiche**.

**Posizionamento**: tra Fase 20 e Fase 21. Fase 20 sblocca prerequisiti (Git Safety, CompileTestFix), Fase CC migliora il worker SDK, Fase 21 beneficia dei pattern CC per i nuovi worker (TESTING, SECURITY, DOCUMENTATION userebbero PhasedLlmWorker, Progress Tracking, Project Instructions).

**Effort totale**: 15.0g | **Items**: CC-01 → CC-13

### Pattern esclusi (già coperti)
- ✅: P9, P11, P25, P27
- Fase 19: P14→#167, P15→#168, P19→#172, P20→#173
- Fase 20: P16→#185, P18→#186
- N/A: P23 (output styles non rilevante per agenti autonomi)
- Parziali OK: P12, P13, P17, P24 (funzionano, miglioramento incrementale)

---

## Sub-fase CC-a — Core (4.5g)

### CC-01 — Auto-compacting miglioramento (P1) — 1.5g

**Gap**: `CompactingToolCallingManager` esiste ma compatta solo post-hoc. Claude Code stima token PRIMA della call (pre-call budget check) e fa summarization intelligente, non solo troncamento. Manca conteggio token nelle tool definitions.

**Approccio**:
- Pre-call token estimation in `executeToolCalls()`: se sotto 25% budget, compaction preventiva
- Stima token per tool definitions in `resolveToolDefinitions()`
- Configurazione via `WorkerProperties`: `compaction.threshold`, `compaction.maxTokens`
- Evento `CONTEXT_COMPACTION` via `WorkerEventPublisher` con metriche before/after

**File**:
- `worker-sdk/.../claude/CompactingToolCallingManager.java` (MOD)
- `worker-sdk/.../config/CompactingToolCallingManagerPostProcessor.java` (MOD)
- `worker-sdk/.../config/WorkerProperties.java` (MOD)
- `worker-sdk/.../event/WorkerEventPublisher.java` (MOD)

---

### CC-02 — Project Instructions (P2) — 0.5g

**Gap**: Nessun equivalente di CLAUDE.md. I piani non portano istruzioni di progetto persistenti.

**Approccio**:
- Colonna `project_instructions TEXT` in tabella `plans` (Flyway)
- API `POST /api/v1/plans` accetta campo opzionale
- `OrchestrationService.dispatchItem()` propaga via `AgentTask`
- `AgentContextBuilder.build()` inietta nel system prompt dopo agent file

**File**:
- Flyway migration (NEW)
- `orchestrator/.../domain/Plan.java` (MOD)
- `orchestrator/.../orchestration/OrchestrationService.java` (MOD)
- `worker-sdk/.../dto/AgentTask.java` (MOD)
- `worker-sdk/.../context/AgentContextBuilder.java` (MOD)

---

### CC-06 — Progress Tracking (P7) — 1.0g

**Gap**: `WorkerEventPublisher` emette TOOL_CALL_START/END e TOKEN_UPDATE. Manca evento PROGRESS strutturato (analogo TodoWrite).

**Approccio**:
- `WorkerPhase` enum: `EXPLORING`, `IMPLEMENTING`, `VERIFYING`, `FINALIZING`
- `AbstractWorker.reportProgress(phase, message, percent)`
- `LlmWorker` emette automaticamente EXPLORING al start, IMPLEMENTING dopo prima tool call
- SSE evento `WORKER_PROGRESS` con payload strutturato

**File**:
- `worker-sdk/.../event/WorkerEventPublisher.java` (MOD)
- `worker-sdk/.../event/WorkerPhase.java` (NEW)
- `worker-sdk/.../AbstractWorker.java` (MOD)
- `worker-sdk/.../LlmWorker.java` (MOD)

---

### CC-10 — Human-in-the-Loop (P21) — 1.0g

**Gap**: `AWAITING_APPROVAL` esiste per task ad alto rischio. Ma un worker non può chiedere input umano DURANTE l'esecuzione.

**Approccio**:
- Nuovo `ItemStatus.WAITING_INPUT` con transizioni RUNNING↔WAITING_INPUT
- Worker restituisce `status: "WAITING_INPUT"` + `question: "..."` nel JSON
- SSE evento `WAITING_INPUT` con domanda
- Endpoint `POST /api/v1/plans/{planId}/items/{itemId}/respond`
- Timeout configurabile (default 30min) → FAILED

**File**:
- `orchestrator/.../domain/ItemStatus.java` (MOD)
- `orchestrator/.../orchestration/OrchestrationService.java` (MOD)
- `orchestrator/.../api/PlanItemController.java` (MOD)

---

## Sub-fase CC-b — Context Intelligence (3.5g)

### CC-03 — Worker Memory (P3) — 1.5g

**Gap**: Nessuna memoria persistente per-project che sopravvive tra piani. Ogni piano parte da zero.

**Approccio**:
- Tabella `worker_memories`: `id UUID, project_path, category, key, value TEXT, source_plan_id`
- Worker completati possono restituire `_memories: [{key, value, category}]` nel risultato
- `OrchestrationService.handleResult()` estrae e persiste
- Al dispatch, memories rilevanti caricate e incluse in `AgentTask`
- `AgentContextBuilder` inietta sezione `## Project Memory`
- REST: `GET /api/v1/projects/{path}/memories`

**File**:
- Flyway migration (NEW)
- `orchestrator/.../knowledge/WorkerMemoryRepository.java` (NEW)
- `orchestrator/.../knowledge/WorkerMemoryService.java` (NEW)
- `orchestrator/.../api/MemoryController.java` (NEW)
- `orchestrator/.../orchestration/OrchestrationService.java` (MOD)
- `worker-sdk/.../dto/AgentTask.java` (MOD)
- `worker-sdk/.../context/AgentContextBuilder.java` (MOD)

**Dipendenza**: CC-02 (projectPath nel Plan)

---

### CC-04 — System Reminders / ToolResultEnricher (P5) — 1.0g

**Gap**: Claude Code inietta "system reminders" nei risultati tool. Il framework non rinforza istruzioni durante l'esecuzione.

**Approccio**:
- `ToolResultEnricherInterceptor` implementa `WorkerInterceptor`
- Decora `ToolCallback`: dopo esecuzione, appende reminder (project instructions summary, workspace path, relevant_files)
- Frequenza: ogni N tool call (default 5) o quando token > soglia
- Wrapper di `PolicyEnforcingToolCallback`

**File**:
- `worker-sdk/.../interceptor/ToolResultEnricherInterceptor.java` (NEW)
- `worker-sdk/.../policy/ToolResultEnricherCallback.java` (NEW)
- `worker-sdk/.../config/WorkerAutoConfiguration.java` (MOD)

**Dipendenza**: CC-02 (project instructions come source)

---

### CC-05 — Discovery Phase (P6) — 1.0g

**Gap**: `PlannerService.decompose()` chiama Claude una volta. Nessuna fase di esplorazione pre-planning.

**Approccio**:
- `PlannerService.discover()` precede `decompose()`
- Prompt dedicato `planner-discovery.prompt.md`: analisi spec, file coinvolti, rischi, pattern — senza generare task
- Output discovery passato come contesto a `decompose()`
- Config: `planner.discovery.enabled=true|false` (default false)
- Se disponibili, usa `WorkerMemory` (CC-03) come input

**File**:
- `orchestrator/.../planner/PlannerService.java` (MOD)
- `prompts/planner-discovery.prompt.md` (NEW)
- `orchestrator/.../planner/DiscoveryResult.java` (NEW record)

**Dipendenza**: CC-03 (memories come input opzionale)

---

## Sub-fase CC-c — Worker Isolation (3.0g)

### CC-08 — Worktree Isolation (P28) — 2.0g

**Gap**: Tutti i worker di un piano scrivono nella stessa directory. Nessun isolamento per-worker.

**Approccio**:
- `WorkspaceManager.createWorkerWorkspace(planId, taskKey)` → `/workspace/{planId}/{taskKey}/`
- Dopo completamento, `WorkspaceMerger` copia file nella directory shared `/workspace/{planId}/shared/`
- Conflitti: se stesso file prodotto da 2 worker → segnalazione + delega a REVIEW
- Se git disponibile (#185): `git worktree add` per isolamento reale. Fallback: directory semplici
- `WorkspaceCleanupScheduler` esteso per sotto-directory worker

**File**:
- `orchestrator/.../workspace/WorkspaceManager.java` (MOD)
- `orchestrator/.../workspace/WorkspaceMerger.java` (NEW)
- `orchestrator/.../workspace/WorkspaceCleanupScheduler.java` (MOD)
- `orchestrator/.../orchestration/OrchestrationService.java` (MOD)

**Dipendenza**: #185 (opzionale, per git worktree reali)

---

### CC-09 — Session Resume (P4) — 1.0g

**Gap**: Worker falliti ripartono da zero. Event Sourcing permette replay piano, ma non replay conversazione worker.

**Approccio**:
- `ConversationCheckpoint`: dopo ogni tool call, `CompactingToolCallingManager` serializza conversation history → Redis (`checkpoint:{planId}:{taskKey}`, TTL 1h)
- Su ri-dispatch (auto-retry), `AgentContextBuilder` cerca checkpoint e ripristina
- Checkpoint include: messages, token count, tools called, last phase
- Flag `resumable` in `AgentTask` (default true per auto-retry, false per ralph-loop)

**File**:
- `worker-sdk/.../claude/ConversationCheckpoint.java` (NEW record)
- `worker-sdk/.../claude/CompactingToolCallingManager.java` (MOD)
- `worker-sdk/.../cache/RedisContextCacheStore.java` (MOD)
- `worker-sdk/.../claude/WorkerChatClientFactory.java` (MOD)
- `worker-sdk/.../dto/AgentTask.java` (MOD)

**Dipendenza**: CC-01 (checkpoint nel compacting manager)

---

## Sub-fase CC-d — Advanced (4.0g)

### CC-07 — Phased Execution (P8) — 1.0g

**Gap**: Worker eseguono una singola call LLM. Nessuna struttura EXPLORE → IMPLEMENT → VERIFY.

**Approccio**:
- `PhasedLlmWorker extends LlmWorker` con 3 fasi sequenziali
- EXPLORE: `explorationInstructions()` (abstract) — legge file, capisce contesto
- IMPLEMENT: `implementationInstructions()` (abstract) + output EXPLORE
- VERIFY: `verificationInstructions()` (default: verifica coerenza)
- Ogni fase emette `WorkerPhase` progress (CC-06)
- Fasi condividono conversation history (ChatClient Spring AI)
- Backward compatible: worker opt-in estendendo `PhasedLlmWorker`

**File**:
- `worker-sdk/.../PhasedLlmWorker.java` (NEW)

**Dipendenza**: CC-06 (progress events)

---

### CC-11 — Progress Reporting Interno (P22) — 1.0g

**Gap**: SSE mostra cambio stato ma non cosa sta FACENDO il worker.

**Approccio** (estende CC-06):
- `CompactingToolCallingManager` intercetta reasoning text dell'assistant dopo ogni tool call
- Emette `WORKER_REASONING` (troncato 500 char) via `WorkerEventPublisher`
- SSE propaga al client per visualizzazione real-time
- Rate limiting: max 1 reasoning event per tool-call cycle

**File**:
- `worker-sdk/.../claude/CompactingToolCallingManager.java` (MOD)
- `worker-sdk/.../event/WorkerEventPublisher.java` (MOD)

**Dipendenza**: CC-06

---

### CC-12 — Parallel Tool Calling (P10) — 1.5g

**Gap**: Tool execution è seriale. Claude Code invoca tool in parallelo quando non hanno dipendenze.

**Approccio**:
- `ParallelToolCallingManager` wrappa `CompactingToolCallingManager`
- Multiple tool_use blocks nella stessa risposta → `CompletableFuture.supplyAsync()` (virtual threads Java 21)
- Timeout per-tool: `agent.worker.tool-execution-timeout` (default 60s)
- Se un tool fallisce, gli altri continuano. Risultati assemblati nell'ordine originale
- BeanPostProcessor analogo a `CompactingToolCallingManagerPostProcessor`

**File**:
- `worker-sdk/.../claude/ParallelToolCallingManager.java` (NEW)
- `worker-sdk/.../config/ParallelToolCallingManagerPostProcessor.java` (NEW)
- `worker-sdk/.../config/WorkerAutoConfiguration.java` (MOD)
- `worker-sdk/.../config/WorkerProperties.java` (MOD)

**Dipendenza**: CC-01 (si compone col compacting)

---

### CC-13 — Deferred Tool Loading (P26) — 1.0g

**Gap**: Tutte le tool definitions caricate nel prompt iniziale (~50 tool, ~5K token).

**Approccio**:
- `DeferredToolRegistry`: mantiene lista completa, espone al ChatClient solo core set
- Tool speciale `tool_search(query)` sempre disponibile: LLM cerca tool on-demand
- Tool trovati aggiunti dinamicamente alla sessione
- Risparmio stimato: da ~50 a ~15 core + on-demand (~5K token)

**File**:
- `worker-sdk/.../claude/DeferredToolRegistry.java` (NEW)
- `worker-sdk/.../claude/ToolSearchCallback.java` (NEW)
- `worker-sdk/.../claude/WorkerChatClientFactory.java` (MOD)

---

## Riepilogo

| # | Pattern | Sforzo | Sub-fase | Dipendenza |
|---|---------|--------|----------|------------|
| CC-01 | P1 Auto-compacting++ | 1.5g | CC-a | — |
| CC-02 | P2 Project Instructions | 0.5g | CC-a | — |
| CC-06 | P7 Progress Tracking | 1.0g | CC-a | — |
| CC-10 | P21 Human-in-the-Loop | 1.0g | CC-a | — |
| CC-03 | P3 Worker Memory | 1.5g | CC-b | CC-02 |
| CC-04 | P5 System Reminders | 1.0g | CC-b | CC-02 |
| CC-05 | P6 Discovery Phase | 1.0g | CC-b | CC-03 |
| CC-08 | P28 Worktree Isolation | 2.0g | CC-c | #185 (opt) |
| CC-09 | P4 Session Resume | 1.0g | CC-c | CC-01 |
| CC-07 | P8 Phased Execution | 1.0g | CC-d | CC-06 |
| CC-11 | P22 Progress Reporting++ | 1.0g | CC-d | CC-06 |
| CC-12 | P10 Parallel Tool Calls | 1.5g | CC-d | CC-01 |
| CC-13 | P26 Deferred Tool Loading | 1.0g | CC-d | — |
| | **Totale** | **15.0g** | | |

## Ordine implementazione

```
Fase CC-a (core, 4.5g):          CC-01 → CC-02 → CC-06 → CC-10
Fase CC-b (context, 3.5g):       CC-03 → CC-04 → CC-05
Fase CC-c (isolation, 3.0g):     CC-08 → CC-09
Fase CC-d (advanced, 4.0g):      CC-07 → CC-11 → CC-12 → CC-13
```

CC-a e CC-c possono procedere in parallelo. CC-b dipende da CC-02 (CC-a). CC-d dipende da CC-01 e CC-06 (CC-a).

## File hub (modificati da 3+ pattern)

1. **`CompactingToolCallingManager.java`** — CC-01, CC-09, CC-11, CC-12 (4 pattern)
2. **`AgentContextBuilder.java`** — CC-02, CC-03, CC-04, CC-09 (4 pattern)
3. **`OrchestrationService.java`** — CC-02, CC-03, CC-08, CC-10 (4 pattern)
4. **`WorkerEventPublisher.java`** — CC-01, CC-06, CC-11 (3 pattern)
5. **`AgentTask.java`** — CC-02, CC-03, CC-09 (3 pattern)

## Flyway Migrations

- V_CC1: `project_instructions TEXT` su `plans`
- V_CC2: tabella `worker_memories` (id, project_path, category, key, value, source_plan_id, timestamps)
- V_CC3: `WAITING_INPUT` in ItemStatus (se enum in DB, altrimenti no migration)

## Verifica

1. **CC-01**: Worker con context >75% → compaction preventiva, evento CONTEXT_COMPACTION nel log
2. **CC-02**: `POST /api/v1/plans` con `projectInstructions` → testo iniettato nel system prompt di ogni worker
3. **CC-03**: Worker completa con `_memories` → persistite in DB → disponibili nel piano successivo stesso progetto
4. **CC-06**: Worker in esecuzione → eventi SSE `WORKER_PROGRESS` con fase e percentuale
5. **CC-08**: Due worker dello stesso piano → directory isolate, merge controllato post-completamento
6. **CC-10**: Worker restituisce `WAITING_INPUT` → SSE con domanda → `POST .../respond` → worker riprende
7. **CC-12**: LLM restituisce 3 tool_use → 3 tool eseguiti in parallelo (log conferma parallelismo)
8. **E2E**: Piano con `projectInstructions` + `discovery.enabled=true` → discovery pre-planning → worker con progress tracking → phased execution (EXPLORE/IMPLEMENT/VERIFY)
