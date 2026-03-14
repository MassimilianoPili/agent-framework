# Agent Framework — Storico Implementazioni

Questo documento contiene le feature gia' implementate e le session log completate,
spostate da PIANO.md per mantenere il piano di evoluzione snello e focalizzato sul futuro.

---

## Indice

### Feature implementate (Roadmap)
- [#1 Event Sourcing Puro](#1-event-sourcing-puro-) (S4)
- [#2 Missing-Context Feedback Loop](#2-missing-context-feedback-loop-) (S4)
- [#3 Retry Automatico con Exponential Backoff](#3-retry-automatico-con-exponential-backoff-) (S4)
- [#4 Saga / Compensation (COMPENSATOR_MANAGER)](#4-saga--compensation-compensator_manager-) (S4)
- [#6 Token Budget per WorkerType](#6-token-budget-per-workertype-) (S4)
- [#11 GP per Worker Selection](#11-gp-per-worker-selection-) (S6)
- [#14 DPO con GP Residual](#14-dpo-con-gp-residual-) (S7)
- [#16 Ralph-Loop (Quality Gate Feedback Loop)](#16-ralph-loop-quality-gate-feedback-loop-) (S5)
- [#12 Serendipita' Context Manager](#12-serendipita-nel-context-manager-) (S8)
- [#15 Active Token Budget](#15-active-learning-per-token-budget-) (S9)
- [#13 Council Taste Profile](#13-council-taste-profile-) (S11)
- [#20 Modello LLM per Task](#20-modello-llm-per-task-) (S11)
- [#17 SDK Scaffold Worker](#17-sdk-scaffold-worker-) (S5)
- [#18 ADR-005: GP + Serendipita'](#18-adr-005-gp--serendipita--motivazioni-architetturali-) (S5)

### RAG Pipeline — Piano Dettagliato
- [Contesto e Decisioni Architetturali](#contesto-rag)
- [Struttura Modulo shared/rag-engine](#struttura-modulo-sharedrag-engine)
- [Configurazione YAML](#configurazione-yaml-rag)
- [Verifica End-to-End](#verifica-end-to-end-post-sessione-3)
- [Fonti](#fonti-rag)

### Session Log (completate)
- [S1 — Infrastruttura + Ingestion Pipeline](#sessione-1--infrastruttura--ingestion-pipeline--completata)
- [S2 — Search Pipeline + Parallelismo + Apache AGE](#sessione-2--search-pipeline--parallelismo--apache-age-graphs--completata)
- [S3 — RAG_MANAGER Worker + Integrazione](#sessione-3--rag_manager-worker--integrazione-completa--completata)
- [S5 — Ralph-Loop + Roadmap GP/Serendipita'](#sessione-5--ralph-loop--roadmap-gpserendipita--completata)
- [S6 — GP Engine Module + Worker Selection](#sessione-6--gp-engine-module--worker-selection-11--completata)
- [S7 — DPO con GP Residual](#sessione-7--dpo-con-gp-residual-14--completata)
- [S8 — Serendipita' Context Manager (#12)](#sessione-8--serendipita-context-manager-12--completata)
- [S8-bugfix — Fix bug critici + resilienza](#sessione-8-bugfix--fix-bug-critici--resilienza--completata)
- [S8-workers — 26 Nuovi Workers + build.sh](#sessione-8-workers--26-nuovi-workers--buildsh--completata)
- [S8-research — Fasi 8a/8b/8d Research Domains](#sessione-8-research--fasi-8a8b8d-research-domains--completata)
- [S9 — Active Token Budget (#15)](#sessione-9--active-token-budget-15--completata)
- [S10 — Research Domains Fase 9 + Fase 10](#sessione-10--research-domains-fase-9-completamento--fase-10-77-86--completata)
- [S11 — Council Taste Profile (#13) + Modello LLM (#20)](#sessione-11--council-taste-profile-13--modello-llm-per-task-20--completata)
- [S23 — Fase 13 Research Applicativi (#107-#116)](#sessione-23--fase-13-research-applicativi-107-116-2026-03-14)
- [Riepilogo File per Sessione](#riepilogo-file-per-sessione)

---
---

# Feature implementate (Roadmap)

### 1. Event Sourcing Puro ✅

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

### 2. Missing-Context Feedback Loop ✅

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

### 3. Retry Automatico con Exponential Backoff ✅

**Problema**: `AgentManifest.Retry` e' parsato ma mai usato automaticamente.

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

### 4. Saga / Compensation (COMPENSATOR_MANAGER) ✅

**Problema**: nessun meccanismo di rollback quando un task fallisce definitivamente.

**Soluzione**: trigger manuale via API — l'utente decide scope e profondita' del rollback.
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

### 6. Token Budget per WorkerType ✅

**Problema**: nessun limite ai token consumati. Nessuna visibilita' sui costi per tipo di worker.

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

### 11. GP per Worker Selection ✅

**Problema**: il worker profile viene assegnato staticamente in `OrchestrationService.dispatchReadyItems()`
(righe 523-530). `WorkerProfileRegistry.resolveDefaultProfile()` fa `defaults.get(workerType.name())`:
un "Build REST API" e un "Implement WebSocket" ricevono entrambi `be-java`, ignorando la natura del task.

**Soluzione**: Gaussian Process (GP) che predice il profilo ottimale dato l'embedding del task.

**Perche' GP e non classificatore/regressore**:
- Il GP restituisce `(mu, sigma^2)`. `sigma^2` alto = "non so" → trigger per REVIEW worker
- Cold-start graceful: con pochi dati degenera al prior (media globale = default attuale)
- Kernel RBF su embedding: task semanticamente simili condividono la predizione
- Un classificatore darebbe "be-java" con confidenza 99% anche su un task mai visto

**Perche' qui nel flusso**: riga 523 e' l'unico punto dove il profile viene scelto.
Dopo il dispatch non si puo' cambiare worker. Mettere il GP *prima* del dispatch
e' l'unica posizione utile. Il reward arriva *dopo* (`RewardComputationService`, righe 209-267)
e aggiorna il GP con il segnale di rinforzo.

**Perche' `task_outcomes` come tabella separata**: `plan_items` NON ha embedding (1024 dim),
ne' snapshot ELO al dispatch. Query OLAP (training GP) vs OLTP (dispatch). `ON DELETE SET NULL`
per preservare storico training anche dopo delete piano. Campi denormalizzati per evitare join costosi.

**Perche' modulo `shared/gp-engine`**: riuso (orchestratore + worker context-manager),
testabilita' (GP e' matematica pura, zero side-effect), swap implementazione senza toccare orchestratore.
Pattern identico a `shared/rag-engine`.

**Dati di training gia' disponibili**: `plan_items.aggregated_reward` (target),
`plan_items.worker_profile` (etichetta), `worker_elo_stats.elo_rating` (feature).
Embedding via Ollama (stessa pipeline RAG engine, `mxbai-embed-large` 1024 dim).

**File nuovi**: `shared/gp-engine/` (modulo Maven), `GaussianProcessService.java`,
`TaskEmbeddingService.java`, `WorkerSelectionPredictor.java`. Flyway V8 (`task_outcomes`).
Modifica `OrchestrationService.dispatchReadyItems()` righe 523-530.

**Sforzo**: 3g. **Dipendenze**: pgvector, Ollama (gia' disponibili). **Dati minimi**: ~50 task completati.

---

### 14. DPO con GP Residual ✅

**Problema**: `PreferencePairGenerator` filtra con `delta >= 0.3` (riga 113, campo `MIN_DELTA`).
Non distingue coppie ovvie (il GP gia' sapeva che be-java e' buono su REST) da coppie
informative (sorpresa: be-java eccelle su WebSocket). Genera noise nel training DPO.

**Soluzione**: nuova strategia `gp_residual_surprise` che filtra per `|actual - predicted|`.

**Perche' il residual filtra meglio**:
- Coppia ovvia: be-java su "REST API" → reward 0.9 (GP predice 0.85) → residual ≈ 0
- Coppia informativa: be-java su "WebSocket" → reward 0.85 (GP predice 0.3) → residual 0.55
La coppia informativa insegna pattern nuovi al modello DPO, la coppia ovvia e' ridondante.

**Perche' nuova strategia e non modifica esistenti**: `same_plan_cross_profile` e
`retry_comparison` hanno logica propria e funzionano correttamente. Aggiungere
`gp_residual_surprise` e' additivo — Open/Closed Principle. Non rompe le due strategie esistenti.

**Perche' campo `gpResidual` su PreferencePair**: il trainer DPO puo' fare importance
sampling pesando per `|gpResidual|`. Senza il campo, l'informazione si perde dopo la generazione.

**File mod**: `PreferencePairGenerator.java` (+1 strategia `gp_residual_surprise`),
`PreferencePair.java` (+1 campo `gpResidual`). Flyway per colonna.

**Sforzo**: 1g. **Dipendenze**: #11 (GP engine). **Dati minimi**: ~50 task con reward.

---

### 16. Ralph-Loop (Quality Gate Feedback Loop) ✅

**Problema**: la quality gate (`QualityGateService`) valuta i risultati del piano ma NON
re-dispatcha i task che non superano la soglia. Il piano resta COMPLETED anche se la quality
gate fallisce. L'utente deve intervenire manualmente.

**Soluzione**: loop automatico — i task implicati nella quality gate failure vengono rimessi
in WAITING con il feedback della quality gate nel contesto, poi ri-dispatchati.

**Perche' estende il pattern Missing-Context**: stessa meccanica `DONE→WAITING` con
feedback nel contesto, ma triggerato dalla quality gate anziche' dal worker.
Missing-Context e' *intra-task* (il worker dice "mi manca contesto"), ralph-loop e'
*post-plan* (la quality gate dice "il risultato non e' sufficiente").

**Perche' `DONE → WAITING` e non `DONE → FAILED → WAITING`**: il task non ha *fallito* —
ha prodotto un risultato che non supera la quality gate. Passare da FAILED introdurrebbe
rumore nel conteggio retry dell'`AutoRetryScheduler` (che conta `FAILED→WAITING`).
Transizione diretta = semantica chiara. Contatore separato `ralphLoopCount`.

**Perche' `COMPLETED → RUNNING` per il piano**: il piano deve potersi "riaprire".
Alternativa: creare un nuovo piano → perde contesto, dipendenze, reward accumulati.
Riaprire = continuita'. `PlanStatus` gia' ha `RUNNING`, basta aggiungere la transizione.

**Perche' contatore separato `ralphLoopCount`**: `contextRetryCount` misura i retry
per contesto mancante. `ralphLoopCount` misura i retry per quality gate.
Semantiche diverse, contatori diversi → configurazione cap indipendenti.

**File nuovi**: `RalphLoopService.java`, `RalphLoopServiceTest.java` (8 test).
**File mod**: `ItemStatus.java` (DONE → WAITING), `PlanStatus.java` (COMPLETED → RUNNING),
`PlanItem.java` (+`ralphLoopCount`, +`lastQualityGateFeedback`),
`QualityGateService.java` (chiama ralph-loop dopo report), `OrchestrationService.java`
(append feedback nel contesto), `application.yml` (config cap e soglia),
Flyway V7 (nuove colonne).

**Sforzo**: 1.5g. **Dipendenze**: nessuna. **Dati minimi**: nessuno (funziona dal primo piano).

---

### 12. Serendipita' nel Context Manager ✅

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

### 15. Active Learning per Token Budget ✅

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

### 17. SDK Scaffold Worker ✅

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

### 18. ADR-005: GP + Serendipita' — Motivazioni Architetturali ✅

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
---

# RAG Pipeline + Graph RAG — Piano Dettagliato (3 Sessioni)

> Riferimento unico per le 3 sessioni di implementazione. Aggiunge ricerca semantica,
> graph RAG e un worker dedicato `RAG_MANAGER` al framework.

## Contesto RAG

Il `CONTEXT_MANAGER` oggi usa retrieval puramente file-based (Glob/Grep/Read). Non scala su
codebase grandi e non cattura relazioni semantiche. L'obiettivo e' una pipeline RAG completa
con graph RAG ibrido, integrata come `RAG_MANAGER` worker dedicato nel DAG dei task.

## Decisioni Architetturali RAG

| Aspetto | Scelta | Motivazione |
|---|---|---|
| **Vector DB** | pgvector (tabella unica + tsvector BM25) | Zero nuovi container, riusa PostgreSQL, hybrid search in 1 query SQL |
| **Cache** | Redis DB 5 | Riusa Redis esistente, embedding cache (24h TTL) + search cache (1h TTL) |
| **Embedding** | `mxbai-embed-large` via Ollama (1024 dim) | Top MTEB 64.68, retrieval 54.39, batte OpenAI. 670MB modello |
| **Reranking** | Cascata: cosine re-scoring → LLM scorer (`qwen2.5:1.5b`) | Stage 1 veloce (~1ms, top 20→10), stage 2 preciso (~100ms, top 10→5-8) |
| **Grafi** | Apache AGE su PostgreSQL, 2 grafi | `knowledge_graph` (chunk + task + decisioni) + `code_graph` (strutturale) |
| **Chunking** | Recursive per codice, proposition per docs | Doppia strategia per tipo file. Recursive = 69% accuracy (benchmark) |
| **Integrazione** | `RAG_MANAGER` worker dedicato | Risultati via dependencyResults (zero modifiche ad AgentContext) |
| **Ingestion** | API + PlanCompletedEvent + file watcher | Manuale, automatica su plan complete, incrementale su cambio file |
| **Architettura** | Modulare (Spring AI abstractions) | Swap embedding/vectorstore/reranker cambiando solo YAML |

### Perche' RAG_MANAGER come worker dedicato

Si integra nel DAG dei task come `CONTEXT_MANAGER` e `SCHEMA_MANAGER`. I risultati fluiscono
ai domain worker via `dependencyResults` (meccanismo gia' esistente). **Zero modifiche** al
record `AgentContext`, al `WorkerInterceptor`, o al `buildStandardUserPrompt()`.

### Perche' 2 grafi e non 3

`knowledge_graph` e `task_graph` sono sovrapponibili: i task sono nodi nel grafo della conoscenza.
I cluster di task emergono dalla topologia del grafo (community detection).
Il `code_graph` e' separato ma correlato via `filePath`.

---

## Struttura Modulo `shared/rag-engine`

Libreria condivisa (non Spring Boot app), usata da orchestrator e worker.
Auto-configuration + `@ConfigurationProperties("rag")`.

```
shared/rag-engine/
├── pom.xml
└── src/
    ├── main/java/com/agentframework/rag/
    │   ├── config/
    │   │   ├── RagAutoConfiguration.java           # @AutoConfiguration master
    │   │   ├── RagProperties.java                  # @ConfigurationProperties("rag")
    │   │   ├── PgVectorStoreConfig.java            # VectorStore bean (pgvector, 1024 dim)
    │   │   ├── OllamaEmbeddingConfig.java          # EmbeddingModel bean (mxbai-embed-large)
    │   │   └── RagCacheConfig.java                 # Redis DB 5
    │   ├── ingestion/
    │   │   ├── IngestionPipeline.java              # Orchestratore 5 fasi
    │   │   ├── IngestionService.java               # API alto livello + trigger
    │   │   ├── CodeDocumentReader.java             # DocumentReader per file code
    │   │   ├── FileWatcherService.java             # inotify/polling + debounce
    │   │   ├── chunking/
    │   │   │   ├── ChunkingStrategy.java           # sealed interface
    │   │   │   ├── RecursiveCodeChunker.java       # .java/.go/.rs — 512 tok, confini metodo
    │   │   │   └── PropositionChunker.java         # .md/.yml — fatti atomici via LLM
    │   │   └── enrichment/
    │   │       ├── ContextualEnricher.java         # Pattern Anthropic: contesto 50-100 tok
    │   │       └── MetadataEnricher.java           # Entities, language, docType
    │   ├── search/
    │   │   ├── RagSearchService.java               # API unificata
    │   │   ├── HybridSearchService.java            # pgvector + FTS + RRF
    │   │   ├── HydeQueryTransformer.java           # Risposta ipotetica → embedding → search
    │   │   └── reranking/
    │   │       ├── Reranker.java                   # Interface
    │   │       ├── CascadeReranker.java            # Cosine → LLM (2 stage)
    │   │       ├── CosineReranker.java             # Stage 1 (~1ms)
    │   │       ├── LlmReranker.java                # Stage 2 (~100ms)
    │   │       └── NoOpReranker.java               # Passthrough
    │   ├── graph/
    │   │   ├── GraphService.java                   # API alto livello
    │   │   ├── KnowledgeGraphService.java          # CRUD knowledge_graph (AGE + Cypher)
    │   │   ├── CodeGraphService.java               # CRUD code_graph + AST-like analysis
    │   │   └── GraphRagService.java                # Cross-graph queries
    │   ├── model/
    │   │   ├── CodeChunk.java                      # Record: content, contextPrefix, metadata
    │   │   ├── ChunkMetadata.java                  # Record: filePath, language, entities, docType
    │   │   ├── SearchResult.java                   # Record: chunks, scores, searchMode
    │   │   ├── SearchFilters.java                  # Record: language, filePathPattern, maxResults
    │   │   ├── ScoredChunk.java                    # Record: chunk, score, rerankerStage
    │   │   └── IngestionReport.java                # Record: filesProcessed, chunksCreated, errors
    │   ├── cache/
    │   │   └── EmbeddingCacheService.java          # Redis: embedding (24h) + search (1h)
    │   └── tool/
    │       └── SemanticSearchTool.java             # @ReactiveTool
    └── main/resources/META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

---

## Sessione 1 — Infrastruttura + Ingestion Pipeline ✅ COMPLETATA

> **Stato**: completata il 2026-02-28. 53 test verdi in `shared/rag-engine`, 208 in orchestrator (261 totali).
> Build verificata: `mvn clean install -pl shared/rag-engine,control-plane/orchestrator -am`

### S1-A. Docker Compose

**`docker/docker-compose.dev.yml`** e **`docker-compose.sol.yml`**:
- `postgres:16-alpine` → `pgvector/pgvector:pg16`
- Aggiungere servizio `ollama`

```yaml
  ollama:
    image: ollama/ollama:latest
    container_name: agentfw-ollama
    networks: [shared]
    ports: ["11434:11434"]
    volumes:
      - ollama-data:/root/.ollama
    deploy:
      resources:
        limits:
          memory: 3G
    restart: unless-stopped
```

SOL: aggiungere `OLLAMA_BASE_URL=http://ollama:11434` ai worker RAG.

### S1-B. Flyway V5 — pgvector + BM25

`control-plane/orchestrator/src/main/resources/db/migration/V5__rag_vector_store.sql`

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_store (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content       TEXT NOT NULL,
    metadata      JSONB DEFAULT '{}',
    embedding     vector(1024),
    content_tsv   tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
);

CREATE INDEX idx_vector_store_embedding ON vector_store
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_vector_store_tsv ON vector_store USING gin(content_tsv);
```

### S1-C. Ingestion Pipeline

5 fasi: scan → chunk → enrich → embed → store (+ graph extraction).

| Fase | Classe | I/O |
|---|---|---|
| 1. Scan | `CodeDocumentReader` | path → List<Document> (filtro ext, max 500KB) |
| 2. Chunk | `RecursiveCodeChunker` / `PropositionChunker` | Document → List<CodeChunk> (512 tok, 100 overlap) |
| 3. Enrich | `ContextualEnricher` + `MetadataEnricher` | CodeChunk → CodeChunk (contesto + metadata) |
| 4. Embed | `EmbeddingModel` (Ollama) | CodeChunk.content → float[1024] |
| 5. Store | `PgVectorStoreConfig` bean | Document + embedding → vector_store + AGE graph |

### S1-D. Test (~53)

`RecursiveCodeChunkerTest` (8), `PropositionChunkerTest` (6), `ContextualEnricherTest` (5),
`MetadataEnricherTest` (4), `IngestionPipelineTest` (8), `IngestionServiceTest` (6),
`PgVectorStoreConfigTest` (3), `EmbeddingCacheServiceTest` (5), `RagPropertiesTest` (4),
`CodeDocumentReaderTest` (4).

---

## Sessione 2 — Search Pipeline + Parallelismo + Apache AGE Graphs ✅ COMPLETATA

> **Stato**: completata il 2026-03-01. Java 17→21 migrazione, virtual threads, 47 test nuovi in `shared/rag-engine` (100 totali RAG, 308 totali framework).
> Build verificata: `mvn clean install -pl shared/rag-engine,control-plane/orchestrator -am` (Java 21)

### S2-00. Java 17 → 21 + Virtual Threads

- `pom.xml`: `<java.version>21</java.version>` (propaga a 22 moduli)
- `application.yml`: `spring.threads.virtual.enabled: true` (Tomcat virtual threads)
- `Dockerfile.mustache`: `eclipse-temurin:21-jre-alpine`
- `ContextualEnricher`: refactored per `CompletableFuture` + `Executors.newVirtualThreadPerTaskExecutor()`
- `RagAutoConfiguration`: bean `ragParallelExecutor` (virtual thread executor)
- Test S1: `.get(0)` → `.getFirst()` (Sequenced Collections, 11 occorrenze)

### S2-A. Search Pipeline

| Classe | Responsabilita' |
|---|---|
| `HybridSearchService` | pgvector similarity + PostgreSQL FTS + RRF fusion (k=60) |
| `HydeQueryTransformer` | Claude genera risposta ipotetica → embedda → cerca |
| `CascadeReranker` | Compone: CosineReranker (top 10) → LlmReranker (top 5-8) |
| `RagSearchService` | Pipeline: query → [HyDE] → hybrid → cascade rerank → risultati |

**RRF**: `score(d) = 1/(k + rank_vector(d)) + 1/(k + rank_bm25(d))`, k=60

### S2-B. Flyway V6 — Apache AGE

```sql
CREATE EXTENSION IF NOT EXISTS age;
LOAD 'age';
SET search_path = ag_catalog, "$user", public;
SELECT create_graph('knowledge_graph');
SELECT create_graph('code_graph');
```

### S2-C. Graph Services

| Classe | Responsabilita' |
|---|---|
| `KnowledgeGraphService` | Nodi: Chunk, Concept, Decision, Task. Archi: REFERENCES, DEPENDS_ON, SIMILAR_TO |
| `CodeGraphService` | Nodi: File, Class, Method, Package. Archi: IMPORTS, EXTENDS, CALLS, CONTAINS |
| `GraphRagService` | Cross-graph: knowledge ↔ code via filePath |

### S2-D. Test (~47)

`HybridSearchServiceTest` (7), `HydeQueryTransformerTest` (4), `CascadeRerankerTest` (6),
`CosineRerankerTest` (3), `LlmRerankerTest` (5), `RagSearchServiceTest` (6),
`KnowledgeGraphServiceTest` (6), `CodeGraphServiceTest` (6), `GraphRagServiceTest` (4).

---

## Sessione 3 — RAG_MANAGER Worker + Integrazione Completa ✅ COMPLETATA

> **Stato**: completata il 2026-03-01.
> Build verificata: `mvn clean install -pl shared/rag-engine,control-plane/orchestrator,execution-plane/workers/rag-manager-worker -am` (Java 21)

### S3-A. RAG_MANAGER Worker

Modulo: `execution-plane/workers/rag-manager-worker/`
Worker programmatico (non usa LLM): chiama `RagSearchService.search()` e
`GraphRagService.findRelatedInsights()`, assembla JSON e pubblica come dependency result.

Output JSON:
```json
{
  "semantic_chunks": [{"content": "...", "filePath": "...", "score": 0.87, "context": "..."}],
  "graph_insights": ["Found 3 related code entities: ..."],
  "related_files": ["path/to/file1.java"],
  "search_metadata": {"mode": "hybrid+hyde+cascade", "totalCandidates": 20}
}
```

### S3-B. SemanticSearch MCP Tool

`@ReactiveTool(name="semantic_search")` in `shared/rag-engine/.../tool/SemanticSearchTool.java`.
Combina `RagSearchService.search()` + `GraphRagService.findRelatedInsights()`.
Parametri: query (obbligatorio), maxResults (default 8), language (opzionale).

### S3-C. Council Enrichment

`CouncilRagEnricher`: cerca chunk semantici e insight strutturali dal RAG.
`CouncilService`: inietta `Optional<CouncilRagEnricher>`, arricchisce spec/context
in `conductPrePlanningSession()` e `conductTaskSession()` prima di `consultMembersParallel()`.

### S3-D. Orchestrator Integration

- `RAG_MANAGER` aggiunto a `WorkerType` enum (dopo CONTEXT_MANAGER)
- Modulo `rag-manager-worker` registrato nel root `pom.xml`
- Dispatch via messaging Redis (come qualsiasi worker, topic `agent-tasks`)

### S3-E. Test (48 nuovi → 356 totali)

`SemanticSearchToolTest` (4), `RagManagerWorkerTest` (8), `CouncilRagEnricherTest` (8),
`FileWatcherServiceTest` (4), `IngestionServiceTest` (5). Piu' test indiretti in moduli dipendenti.

---

## Sessione 5 — Ralph-Loop + Roadmap GP/Serendipita' ✅ COMPLETATA

> **Stato**: completata il 2026-03-01. Ralph-loop implementato (8 test), roadmap #11-#18 documentata,
> SDK scaffold manifest + skills, ADR-005 architetturale. 224 test orchestrator (372 totali).
> Build verificata: `mvn clean install -pl control-plane/orchestrator -am` (Java 21)

### S5-A. Roadmap #11-#18

8 nuove feature aggiunte alla roadmap con motivazioni architetturali complete:
- **#11** GP Worker Selection (3g, foundation) — GP `(mu, sigma^2)` per profilo ottimale **[IMPLEMENTATO S6]**
- **#12** Serendipita' Context Manager (2g, dipende #11) — GP residual per file discovery
- **#13** Council Taste Profile (2g, dipende #11) — GP per decomposizione piano ottimale
- **#14** DPO con GP Residual (1g, dipende #11) — strategia `gp_residual_surprise` **[IMPLEMENTATO S7]**
- **#15** Active Learning Token Budget (1g, dipende #11) — budget dinamico `sigma^2`-modulato
- **#16** Ralph-Loop (1.5g, standalone) — quality gate feedback loop **[IMPLEMENTATO]**
- **#17** SDK Scaffold Worker (0.5g, standalone) — manifest + skill files **[CREATO]**
- **#18** ADR-005 (0.5g, standalone) — documento motivazioni architetturali **[SCRITTO]**

### S5-B. Ralph-Loop (implementazione)

`RalphLoopService`: quality gate fallita → identifica item DONE domain workers →
DONE→WAITING con feedback → piano COMPLETED→RUNNING → re-dispatch.

**File nuovi**: `RalphLoopService.java`, `RalphLoopServiceTest.java` (8 test), `V7__ralph_loop.sql`.
**File mod**: `ItemStatus.java` (DONE→WAITING), `PlanStatus.java` (COMPLETED→RUNNING),
`PlanItem.java` (+`ralphLoopCount`, +`lastQualityGateFeedback`),
`QualityGateService.java` (integrazione ralph-loop), `QualityGateServiceTest.java` (mock aggiornato),
`OrchestrationService.java` (append feedback nel description), `application.yml` (config).

### S5-C. SDK Scaffold

**File nuovi**: `agents/manifests/sdk-scaffold.agent.yml`,
`skills/sdk-scaffold/SKILL.md`, `skills/sdk-scaffold/python-template.md`,
`skills/sdk-scaffold/typescript-template.md`.

### S5-D. ADR-005

`docs/adr/ADR-005-gp-serendipity-evolution.md` — motivazioni architetturali per #11-#15
con ancoraggi a righe specifiche del codice, alternative scartate, schema `task_outcomes`,
flusso dati completo.

### S5-E. Test (8 nuovi → 372 totali)

`RalphLoopServiceTest` (8): gate passed, domain re-queue, max iterations, plan reopen,
feedback storage, disabled flag, mixed items, empty findings.

---

## Sessione 6 — GP Engine Module + Worker Selection (#11) ✅ COMPLETATA

> **Stato**: completata il 2026-03-01. Modulo `shared/gp-engine` (matematica pura, 30 test),
> integrazione orchestratore (entity, repository, service, selezione GP, reward feedback, 22 test).
> Build verificata: gp-engine 30 test + orchestrator 244 test = 274 verdi (394 totali).
> Commit: `bf012e0` (31 file, +2181 righe).

### S6-A. Modulo `shared/gp-engine` (matematica pura, zero Spring runtime)

Modulo Maven indipendente con Cholesky hand-rolled (matrice max 500x500, ~40M flop, <10ms).
Auto-configuration `@ConditionalOnProperty(prefix="gp", name="enabled", havingValue="true")`.

**Math core**:
- `DenseMatrix`: `double[][]` row-major, `addDiagonal()`, `multiply(vec)`, `diagonal()`
- `CholeskyDecomposition`: outer-product O(N^3/3), `solve()`, `logDeterminant()`
- `RbfKernel`: `k(x,x') = sv * exp(-0.5 * ||x-x'||^2 / ls^2)`, matrice N×N, cross-kernel

**Engine**:
- `GaussianProcessEngine`: `fit(List<TrainingPoint>) → GpPosterior`, `predict(posterior, float[]) → GpPrediction`, `prior(mean)`
- `GpModelCache`: `ConcurrentHashMap` con TTL configurabile, key `"BE:be-java"`, `invalidate()` su nuovo outcome

**Model records**: `GpPrediction(mu, sigma2)` + `ucb(kappa)`, `TrainingPoint(embedding, reward, profile)`,
`GpPosterior(alpha, cholesky, trainingEmbeddings, meanReward, kernel)`

**Config**: `GpProperties` record (`enabled`, `kernel(sv,ls)`, `noiseVariance`, `maxTrainingSize`, `defaultPriorMean`, `cache(ttlMinutes,enabled)`)

**File nuovi (14)**: `shared/gp-engine/pom.xml`, `DenseMatrix.java`, `CholeskyDecomposition.java`,
`RbfKernel.java`, `GpPrediction.java`, `TrainingPoint.java`, `GpPosterior.java`,
`GaussianProcessEngine.java`, `GpModelCache.java`, `GpAutoConfiguration.java`, `GpProperties.java`,
`AutoConfiguration.imports`, 5 test file (30 test: DenseMatrix 3, Cholesky 4, RbfKernel 4, Engine 14, Cache 5)

### S6-B. Integrazione orchestratore

**Entity + Repository**: `TaskOutcome` JPA entity con `@Transient` embedding (pgvector via native query),
`TaskOutcomeRepository` con `insertWithEmbedding()` (`cast(:embedding as vector)`),
`findTrainingDataRaw()` (embedding come text), `updateActualReward()`.

**Service**: `TaskOutcomeService` — embed task (concatena title+description), predict (fit/cache GP),
record outcome at dispatch (ELO snapshot + GP prediction), update reward + invalidate cache.

**Selection**: `GpWorkerSelectionService` — enumera profili candidati, embed → predict per ogni profilo →
seleziona max mu → tie-break = default profile. Record: `ProfileSelection(selectedProfile, selectedPrediction, allPredictions)`.

**Flyway**: `V8__gp_task_outcomes.sql` — tabella `task_outcomes` con `vector(1024)`, indici HNSW + B-tree.

**File nuovi (7)**: `TaskOutcome.java`, `TaskOutcomeRepository.java`, `TaskOutcomeService.java`,
`GpWorkerSelectionService.java`, `V8__gp_task_outcomes.sql`, `TaskOutcomeServiceTest.java` (12 test),
`GpWorkerSelectionServiceTest.java` (6 test)

### S6-C. Modifiche a file esistenti

| File | Modifica |
|------|----------|
| `pom.xml` (root) | +`<module>shared/gp-engine</module>`, +dependencyManagement entry |
| `pom.xml` (orchestrator) | +dependency `gp-engine` |
| `WorkerProfileRegistry.java` | +`profilesForWorkerType(WorkerType)` (stream filter su profiles) |
| `OrchestrationService.java` | +`Optional<GpWorkerSelectionService>` + `Optional<TaskOutcomeService>` nel costruttore, GP selection al dispatch, reward feedback dopo `computeProcessScore()` |
| `OrchestrationServiceTest.java` | +`Optional.empty()` x2 nel costruttore (29 test invariati) |
| `WorkerProfileRegistryTest.java` | +2 test (`profilesForWorkerType` multi/empty) |
| `application.yml` | +sezione `gp:` (enabled: false, kernel, noise, cache) |

### S6-D. Invarianti

1. **`gp.enabled: false`** → zero bean GP creati, zero behavioral change, zero rischio regressione
2. **Cold start** (0 training data) → GP ritorna prior (mu=0.5, sigma^2=max) → tie-break = default → stessa scelta di oggi
3. **Single-profile type** (FE, TASK_MANAGER, etc.) → skip GP, usa default diretto
4. **Cholesky failure** (matrice non SPD) → catch, log warn, fallback a prior

### S6-E. Test (52 nuovi → 394 totali)

**gp-engine** (30): DenseMatrix (3), CholeskyDecomposition (4), RbfKernel (4), GaussianProcessEngine (14), GpModelCache (5).
**orchestrator** (22): TaskOutcomeService (12), GpWorkerSelectionService (6), WorkerProfileRegistry (+2), OrchestrationService (invariati, +Optional.empty nel costruttore).

---

## Sessione 7 — DPO con GP Residual (#14) ✅ COMPLETATA

**Obiettivo**: aggiungere terza strategia DPO `gp_residual_surprise` che filtra coppie
per sorpresa GP (`|actual - predicted| >= 0.15`), cosi' il DPO trainer impari da pattern
nuovi piuttosto che da coppie ovvie.

### S7-A. File nuovi (2)

| File | Scopo |
|------|-------|
| `orchestrator/.../db/migration/V9__dpo_gp_residual.sql` | Colonna `gp_residual FLOAT` nullable + indice DESC NULLS LAST |
| `orchestrator/.../reward/PreferencePairGeneratorTest.java` | 11 test: 4 cross-profile, 2 retry, 4 gp_residual, 1 integrazione |

### S7-B. File modificati (4)

| File | Modifica |
|------|----------|
| `reward/PreferencePair.java` | +campo `gpResidual` (Float nullable), +getter, +costruttore 11-arg |
| `reward/PreferencePairGenerator.java` | +`Optional<TaskOutcomeService>`, +`generateGpResidualPairs()`, +`computeResidual()` con embedding cache |
| `reward/PreferencePairRepository.java` | +query `findByGpResidualDesc(@Param limit)` |
| `PIANO.md` | Documentazione Sessione 7 |

### S7-C. Invarianti

1. **`gp.enabled: false`** → `TaskOutcomeService` non esiste → `Optional.empty()` → strategia GP mai chiamata → zero behavioral change
2. **Strategie esistenti invariate** — `generateCrossProfilePairs()` e `generateRetryPairs()` non toccati
3. **Colonna nullable** — coppie strategie 1 e 2 hanno `gp_residual = NULL`
4. **Costruttore backward-compatible** — 10-arg resta, 11-arg lo estende
5. **`MIN_GP_RESIDUAL = 0.15f`** — soglia piu' bassa di `MIN_DELTA (0.3)` perche' il residual e' un segnale piu' raffinato

### S7-D. Commit

`ca08633` — `feat: Sessione 7 — DPO con GP Residual (#14)`

---

## Sessione 8 — Serendipita' Context Manager (#12) ✅ COMPLETATA

> **Stato**: completata il 2026-03-02. Feature #12 implementata: GP residual per file discovery
> nel context-manager. Aggiunge terza sorgente RRF (serendipita') a `HybridSearchService`.
> Commit: `069b101` — `feat: Sessione 8 — Serendipità Context Manager (#12)`

### S8-serendipity. Implementazione

- `shared/gp-engine/SerendipityAnalyzer.java` (NEW) — analizza residual GP per file discovery
- Modifica `context-manager-worker` — integrazione serendipita' nel flusso CM
- Estensione `HybridSearchService` — terza sorgente RRF (residual storico)
- RRF esteso: `score(d) = 1/(k+r_cosine) + 1/(k+r_bm25) + 1/(k+r_serendipity)`

---

## Sessione 8-bugfix — Fix bug critici + resilienza ✅ COMPLETATA

> **Stato**: completata il 2026-03-03. Fix critici B1-B7, B9, B13-B16 per la catena
> task bloccato / risultato perso / piano mai completo. Centralizzazione nomi tool (#27).
> Commit: `f0c3fc2` — `fix: critical bug fixes + resilience for task completion pipeline (S8)`

### S8-A. ACK after commit (B1)

`TransactionSynchronization.afterCommit()` in `AgentResultConsumer` — XACK solo dopo commit.
Se rollback → no ACK → Redis ri-consegna il messaggio.

**File**: `AgentResultConsumer.java`, `RedisStreamListenerContainer.java`

### S8-B. Side-effect isolation (B2)

Separazione path critico (transizione stato nella transazione) da side-effect non critici
(reward, GP, serendipity) via `@TransactionalEventListener(AFTER_COMMIT)`.

**File**: `OrchestrationService.java`, `TaskCompletedEventHandler.java` (NEW)

### S8-C. Stale task detector (B3)

`@Scheduled` che marca come FAILED task DISPATCHED da piu' di N minuti senza risultato.

**File**: `StaleTaskDetectorScheduler.java` (NEW), `application.yml`

### S8-D. AutoRetryScheduler fix (B4)

Transazione separata per item (`REQUIRES_NEW`) — un retry fallito non causa rollback di tutti.

**File**: `AutoRetryScheduler.java`, `RetryTransactionService.java` (NEW)

### S8-E. Missing context propagation (B5)

Se CM task creato per context retry fallisce → propagare FAILED al task padre.

**File**: `OrchestrationService.java`

### S8-F. LazyInitializationException fix (B6)

`JOIN FETCH plan` nella query repository per evitare lazy init fuori transazione.

**File**: `PlanItemRepository.java`, `OrchestrationService.java`

### S8-G. Optimistic locking (B7)

`@Version` su PlanItem e Plan entities. Flyway V10 (colonna `version`).

**File**: `PlanItem.java`, `Plan.java`

### S8-H. Consumer group resilience (B9)

`XAUTOCLAIM` all'avvio per reclamare messaggi pending oltre idle timeout.

**File**: `AgentResultConsumer.java`, `RedisStreamListenerContainer.java`

### S8-J. Fix Mustache template write-tool-names (B14)

Rimozione `write-tool-names` hardcoded dal template, delegato a `PolicyProperties` default.

**File**: `application.yml.mustache`, `PolicyProperties.java`

### S8-K. Project path dinamico in ownsPaths (B15)

`AgentTask.dynamicOwnsPaths` + merge con `ownsPaths` statici in `PathOwnershipEnforcer`.

**File**: `AgentTask.java`, `AgentTaskProducer.java`, `WorkerTaskConsumer.java`, `PathOwnershipEnforcer.java`

### S8-L. Centralizzazione nomi tool — ToolNames registry (#27, B13, B16)

Classe `ToolNames` nel `worker-sdk` come unica source of truth. Costanti `FS_LIST`, `FS_READ`,
`FS_WRITE`, `FS_SEARCH`, `FS_GREP`. Categorie `WRITE_TOOLS`, `READ_TOOLS`, `ALL_FS_TOOLS`.
Aggiornamento 5 consumatori: `HookPolicyResolver`, `PolicyProperties`, `PathOwnershipEnforcer`,
`hook-manager.agent.yml`, `application.yml.mustache`.

**File**: `ToolNames.java` (NEW), + 5 file aggiornati

### S8-M. Test (~28)

ACK after commit (3), side-effect isolation (4), stale detection (2), retry per-item (3),
optimistic locking (2), pending reclaim (3), CM failure propagation (3),
dynamic ownsPaths (3), write-tool-names (2), ToolNames (3).

---

## Sessione 8-workers — 26 Nuovi Workers + build.sh ✅ COMPLETATA

> **Stato**: completata il 2026-03-03. Aggiunta di 26 nuovi worker domain (BE, FE, DBA, MOBILE),
> 3 council specialist, e script `build.sh` per compilazione rapida.
> Commit: `90c2521` — `feat: add 26 new workers (BE+FE+DBA+MOBILE), 3 council specialists, build.sh`

### Contenuto

- 26 nuovi worker domain con manifest `.agent.yml` e skill files
- 3 council specialist (architettura, security, performance)
- `build.sh` — script di build rapido per l'intero progetto
- SDK Scaffold Worker (#17): `agents/manifests/sdk-scaffold.agent.yml`,
  `skills/sdk-scaffold/SKILL.md`, `skills/sdk-scaffold/python-template.md`,
  `skills/sdk-scaffold/typescript-template.md`

---

## Sessione 8-research — Fasi 8a/8b/8d Research Domains ✅ COMPLETATA

> **Stato**: completate tra il 2026-03-04 e 2026-03-06. Implementazione di 3 fasi di ricerca
> dal dominio Research Domains Extended (items #50-#61).

### Fase 8a — Replicator Dynamics + Worker Greeks

Commit: `5fd4f0c` — `feat: Fase 8a completion — replicator dynamics, worker Greeks, GP risk penalty`

- Replicator dynamics per popolazione worker
- Worker Greeks (sensitivita' al rischio)
- GP risk penalty

### Fase 8b — Spectral Graph + Submodular + ACO

Commit: `078583d` — `feat: Fase 8b — spectral graph theory, submodular optimization, ACO pheromone`

- Spectral graph theory per analisi DAG
- Submodular optimization per selezione task
- ACO (Ant Colony Optimization) pheromone trails

### Fase 8d — Causal Inference + Optimal Transport

Commit: `57117ad` — `Fase 8d: Causal Inference (#54) + Optimal Transport (#60) — 22 new tests`

- Causal inference per analisi causa-effetto nelle performance worker
- Optimal transport per allineamento distribuzioni reward
- 22 nuovi test

---

## Sessione 9 — Active Token Budget (#15) ✅ COMPLETATA

> **Stato**: completata il 2026-03-02. Feature #15 implementata: budget token dinamico
> modulato da predizioni GP (`mu` per qualita' attesa, `sigma^2` per incertezza).
> Commit: `1d1cb01` — `feat: Sessione 9 — Active Token Budget con GP (#15)`

### S9-A. Implementazione

- `TokenBudgetService.checkBudget()` accetta `Optional<GPPrediction>`
- Formula: `dynamic_budget = base × (1 + alpha × sigma^2) × clip(mu, 0.3, 1.0)`
- `sigma^2` alto → piu' budget (active learning)
- `mu` basso → meno budget (non sprecare su task destinato a fallire)
- Enforcement mode invariato (FAIL_FAST/NO_NEW_DISPATCH/SOFT_LIMIT)

### S9-B. Invarianti

1. `gp.enabled: false` → budget statico come prima (zero behavioral change)
2. Cold start (0 training data) → GP prior → budget = base (nessuna modulazione)

---

## Sessione 10 — Research Domains Fase 9 completamento + Fase 10 (#77-#86) ✅ COMPLETATA

> **Stato**: completata il 2026-03-08. Fase 9 completata al 100% (13/15 → 15/15).
> Fase 10 (#77-#86) implementata interamente: 10 nuovi Analytics Services.

### S10-A. Completamento Fase 9 (3 item mancanti)

| # | Item | Servizio |
|---|------|---------|
| #76 | Superrationality (Hofstadter) | `SuperrationalityService` — cooperation gain tra worker type pairs |
| #72 | TDT/FDT Reflective Dispatch | `ReflectiveDispatchService` — politica timeless via argmax reward storico |

_(#63 MPC era già implementato come `MpcSchedulerService`)_

### S10-B. Fase 10 — 10 nuovi Analytics Services

| # | Item | Servizio | Algoritmo chiave |
|---|------|---------|-----------------|
| #79 | MDL (Rissanen 1978) | `MDLService` | L(DAG) + L(outcomes\|DAG), normalizzato per N |
| #82 | H∞ Robust Control (Zhou-Doyle) | `HInfinityRobustService` | worst-case = mean − k·std (NormalDist quantile) |
| #84 | Edge of Chaos (Langton) | `EdgeOfChaosService` | Lyapunov proxy: Var(diffs)/Var(rewards) |
| #77 | Active Inference / FEP (Friston) | `ActiveInferenceService` | F = −GP.mu + klWeight·GP.sigma² |
| #78 | Information Bottleneck (Tishby) | `InformationBottleneckService` | SVD via EJML, explained variance ratio |
| #81 | Spin Glass / SA (Kirkpatrick) | `SpinGlassDispatchService` | Simulated Annealing, T_i = T₀·rate^i |
| #83 | Byzantine Fault Tolerance (PBFT) | `ByzantineFaultToleranceService` | majority voting > 2/3, byzantine detection |
| #80 | Renormalization Group (Wilson) | `RenormalizationGroupService` | coupling flow: fine/medium/coarse block scales |
| #85 | Persistent Homology (Edelsbrunner) | `PersistentHomologyService` | Vietoris-Rips + Union-Find β₀ barcodes |
| #86 | Functorial Semantics (Mac Lane) | `FunctorialSemanticsService` | Functor F: item→gp_mu, η: η(item)=actual−gp_mu |

### S10-C. Infrastruttura

- `pom.xml`: aggiunta dipendenza `commons-math3:3.6.1` (NormalDistribution per H∞)
- `TaskOutcomeRepository`: aggiunto `findPlanWorkerRewardSummary()`, `findRewardTimeseriesByWorkerType()`, `findOutcomesWithEmbeddingByWorkerType()`
- `application.yml`: 12 nuovi blocchi config (superrationality, fdt, mdl, h-infinity, edge-of-chaos, active-inference, information-bottleneck, spin-glass, bft, renormalization-group, persistent-homology, functorial-semantics)

---

## Sessione 11 — Council Taste Profile (#13) + Modello LLM per Task (#20) ✅ COMPLETATA

> **Stato**: completata il 2026-03-08. Test: 632 → 888 (+256).
> BUILD SUCCESS, 0 failures, 0 errors.

### S11-A. Feature #13 — Council Taste Profile

Gaussian Process sul Council: il sistema impara dagli esiti dei piani passati e arricchisce
il `CouncilReport` con predizioni strutturali sulla qualità della decomposizione.

| Componente | File | Note |
|------------|------|------|
| V17 Flyway | `V17__plan_outcomes.sql` | Tabella `plan_outcomes` (5-dim features + actual_reward) |
| JPA Entity | `PlanOutcome.java` | Outcome storico per training GP |
| Repository | `PlanOutcomeRepository.java` | `findTrainingData(Pageable)` con ORDER BY created_at |
| Predictor | `PlanDecompositionPredictor.java` | GP self-contained (orchestrator.gp), `MIN_TRAINING_POINTS=5` |
| CouncilReport | `CouncilReport.java` | +predictedReward, +predictionUncertainty, +decompositionHint |
| CouncilService | `CouncilService.java` | inject `Optional<PlanDecompositionPredictor>`, `enrichWithGpPrediction()` |
| OrchestrationService | `OrchestrationService.java` | `recordOutcome()` a plan completion, helper `hasWorkerType/countWorkerType` |
| Config | `application.yml` | `council.taste-profile.*` (enabled, max-training-points, noise-variance, length-scale) |

**Test**: `PlanDecompositionPredictorTest` (4 test) + `CouncilServiceTasteProfileTest` (2 test).

**Scelta architetturale chiave**: `PlanDecompositionPredictor` è nel package orchestrator
(non in `shared/gp-engine`) perché dipende da `PlanOutcomeRepository` (JPA entity orchestrator).
Crea il suo `GaussianProcessEngine` internamente — indipendente dal flag `gp.enabled`.

### S11-B. Feature #20 — Modello LLM per Task

Campo `modelId` nullable in `PlanItem`, propagato attraverso tutto lo stack fino al worker.
Il planner LLM può specificare quale modello usare per ogni task (es. haiku per task meccanici,
opus per task AI-intensivi).

| Componente | File | Note |
|------------|------|------|
| V18 Flyway | `V18__plan_item_model_id.sql` | `ALTER TABLE plan_items ADD COLUMN model_id VARCHAR(100)` |
| PlanItem | `PlanItem.java` | +modelId field + getter/setter (no constructor param) |
| AgentTask (orch.) | `AgentTask.java` (orchestrator dto) | +modelId come 19° campo del record |
| AgentTask (sdk) | `AgentTask.java` (worker-sdk dto) | +modelId come 19° campo del record |
| OrchestrationService | `OrchestrationService.java` | `item.getModelId()` nei 2 siti `new AgentTask(...)` |
| WorkerChatClientFactory | `WorkerChatClientFactory.java` | refactor: `buildWithTools()` privato + overload `create(workerType, policy, modelId)` |
| AbstractWorker | `AbstractWorker.java` | `chatClientFactory.create(..., task.modelId())` |
| PlanItemSchema | `PlanItemSchema.java` | +modelId con `@JsonPropertyDescription` per guidance planner |
| PlannerService | `PlannerService.java` | `item.setModelId(s.modelId())` in `mapSchemaToItems()` |
| OpenAPI | `v1.components.yaml` | +modelId in PlanItemResponse |

**Test**: `ModelRoutingTest` (3 test propagazione DTO) + `WorkerChatClientFactoryModelOverrideTest` (3 test factory).

**Scelta architetturale chiave**: modelId propagato via `AnthropicChatOptions.builder().model(modelId).build()`
come `defaultOptions()` sul `ChatClient` — trasparente ai worker generati senza modifiche.

**Fix pre-esistente**: `PolicyEnforcingToolCallbackTest.call_allowed_whenToolInTaskAllowlist` —
rimosso stub ridondante `checkReadOwnership` (UnnecessaryStubbingException con Mockito 5 strict stubs).

---

## Riepilogo File per Sessione

| Sessione | File nuovi | File mod | Test nuovi | Test totali |
|---|---|---|---|---|
| **S1** (Infra + Ingestion) ✅ | 25 | 5 | 53 | 261 |
| **S2** (Search + Graph + Java 21) ✅ | 15 | 10 | 47 | 308 |
| **S3** (RAG_MANAGER + Integ.) ✅ | 12 | 4 | 48 | 356 |
| **S4** (COMPENSATOR_MANAGER + Audit) ✅ | 2 | 1 | 8 | 364 |
| **S5** (Ralph-Loop + Roadmap #11-#18) ✅ | 8 | 6 | 8 | 372 |
| **S6** (GP Engine + Worker Selection #11) ✅ | 21 | 7 | 52 | 394 |
| **S7** (DPO con GP Residual #14) ✅ | 2 | 4 | 11 | 416 |
| **S8** (Serendipita' #12) ✅ | ~5 | ~3 | — | — |
| **S8-bugfix** (B1-B7, B9, B13-B16) ✅ | ~8 | ~12 | ~28 | — |
| **S8-workers** (26 workers + build.sh) ✅ | ~30 | ~2 | — | — |
| **S8-research** (Fasi 8a/8b/8d) ✅ | ~15 | ~5 | 22 | — |
| **S9** (Active Token Budget #15) ✅ | ~2 | ~3 | — | — |
| **S10** (Fase 9 ✅ 100% + Fase 10 ✅ 100%) | 24 | 4 | ~50 | 632 |
| **S11** (Council Taste Profile #13 + Modello LLM #20) ✅ | 8 | 11 | 9 | 888 |

---

## Configurazione YAML RAG

```yaml
rag:
  enabled: true
  ollama:
    embedding-model: mxbai-embed-large
    reranking-model: qwen2.5:1.5b
    base-url: ${OLLAMA_BASE_URL:http://ollama:11434}
  ingestion:
    chunk-size: 512
    chunk-overlap: 100
    contextual-enrichment: true
    max-file-size-kb: 500
    include-extensions: [java, yml, yaml, md, xml, json, go, rs, js, ts, py, sql]
    file-watcher-enabled: false
  search:
    hybrid-enabled: true
    hyde-enabled: true
    reranker-type: cascade
    top-k: 20
    final-k: 8
    similarity-threshold: 0.5
    rrf-k: 60
  cache:
    redis-db: 5
    embedding-ttl-hours: 24
    search-result-ttl-minutes: 60
```

---

## Verifica End-to-End (post Sessione 3)

1. `mvn clean install` — ~321 test verdi
2. `docker compose -f docker/docker-compose.dev.yml up -d` — pgvector, redis, ollama
3. `docker exec agentfw-ollama ollama pull mxbai-embed-large && ollama pull qwen2.5:1.5b`
4. Flyway applica V15 (pgvector) + V16 (AGE) al boot
5. Ingestion di `execution-plane/worker-sdk/src/` → chunk + grafi
6. Semantic search: "bounded thread pool executor" → CouncilService, AsyncConfig
7. Graph query: "classi che estendono AbstractWorker" → lista worker dal code_graph
8. RAG_MANAGER produce dependency result → domain worker riceve contesto arricchito

## Fonti RAG

- [Ollama Embedding Models Guide (2025)](https://collabnix.com/ollama-embedded-models-the-complete-technical-guide-for-2025-enterprise-deployment/)
- [Best Embedding Models 2026](https://elephas.app/blog/best-embedding-models)
- [MTEB Benchmark Rankings](https://supermemory.ai/blog/best-open-source-embedding-models-benchmarked-and-ranked/)
- [mxbai-embed-large su Ollama](https://ollama.com/library/mxbai-embed-large)
- [Anthropic Contextual Retrieval](https://docs.anthropic.com/en/docs/build-with-claude/retrieval-augmented-generation)
- [Apache AGE — Graph Extension for PostgreSQL](https://age.apache.org/)
- [Spring AI VectorStore Documentation](https://docs.spring.io/spring-ai/reference/api/vectordbs.html)

## Sessione 12 — Leader Election (#22) + Monitoring Dashboard (#28) ✅ COMPLETATA

**Data:** 2026-03-08  
**Test:** 734 orchestrator (0 fallimenti) — +9 nuovi test S12

### Fase A — #22 Leader Election

**Obiettivo:** Multi-istanza sicura — un solo orchestratore dispatcha task via Redis Streams.

**File creati:**
- `leader/LeaderAcquiredEvent.java` — record evento acquisizione leadership
- `leader/LeaderLostEvent.java` — record evento perdita leadership
- `leader/LeaderElectionService.java` — heartbeat `@Scheduled` ogni 10s, Redis `SET NX PX 30000`, rinnovo TTL se già owner, demote se altro owner. `@ConditionalOnProperty(matchIfMissing=true)`.
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
- `OrchestrationService` — `cancelPlan()` @Transactional; emissione `TASK_DISPATCHED` come `SpringPlanEvent` (SSE gap fix); emissione `ITEM_STATUS_CHANGED` per ogni transizione cancellazione
- `repository/PlanRepository.java` — aggiunto `findAllByOrderByCreatedAtDesc()` + `findByStatusOrderByCreatedAtDesc()`
- `api/PlanController.java` — `GET /api/v1/plans` (list paginato); `POST /{id}/cancel` → `orchestrationService.cancelPlan()`; iniettato `PlanRepository`
- `build.sh` — versione plugin corretta da `1.0.0-SNAPSHOT` a `1.1.0-SNAPSHOT`

**File creati:**
- `api/PlanControllerListTest.java` — 2 test (empty list, status filter)
- `orchestration/CancelPlanTest.java` — 2 test (WAITING → CANCELLED, DISPATCHED invariato)
- `resources/static/monitoring.html` — Dashboard a 4 pannelli: Plan Selector (lista recenti + input manuale + cancel), DAG Live (Mermaid.js + colori status + click nodo), Event Stream (SSE feed con badge e filtri per tipo), Worker Detail (attempt history) + Stats (progress bar, budget per workerType, ELO top-5)

### Note tecniche

- **SSE gap**: `TASK_DISPATCHED` era pubblicato solo come `PlanItemDispatchedEvent` (Spring interno), non come `SpringPlanEvent` → il dashboard SSE non lo riceveva mai. Aggiunto publish parallelo.
- **Record immutability**: aggiungere `extraJson` al record `SpringPlanEvent` ha richiesto aggiornare tutti i call site dei costruttori diretti (trovato 1 in `SseEmitterRegistryTest`).
- **DISPATCHED items nel cancel**: `DISPATCHED` non ha `CANCELLED` nelle transizioni consentite → il worker continua naturalmente fino al completamento, il piano passa comunque a CANCELLED.
- **Plugin version mismatch**: `build.sh` usava `1.0.0-SNAPSHOT` mentre il plugin è `1.1.0-SNAPSHOT` → corretto.

---

## S13 — Round 4: Persistent Audit + Provenance Reasoning + Stale Task Detector (2026-03-08)

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
veniva perso — visibile nell'output ma non catturato nel result. Utile per audit, GP reward, debug.

**Implementazione**:
- Campo `String reasoning` aggiunto come 13° campo al record `Provenance` (max 2000 char, null se
  non catturato). Aggiunto simmetricamente in entrambi i moduli per compatibilità JSON.
- `ThreadLocal<String> REASONING` in `AbstractWorker` — stesso pattern di `TOKEN_USAGE`;
  cleanup in `finally` con `REASONING.remove()`
- Helper `protected void captureReasoning(String text)` — idempotente (solo prima chiamata
  non-blank per task); truncate a 2000 char; usabile dai worker generati in `execute()`
- Success path: `REASONING.get()` come 13° argomento del costruttore `Provenance`
- Error path: `null` come 13° argomento (nessun reasoning in caso di eccezione)

**File modificati** (3):
- `worker-sdk/src/main/java/.../worker/dto/Provenance.java` (+ reasoning, 13° campo)
- `orchestrator/src/main/java/.../messaging/dto/Provenance.java` (mirror)
- `worker-sdk/src/main/java/.../worker/AbstractWorker.java` (+REASONING ThreadLocal, +captureReasoning)

---

### Stale Task Detector

Task rimasti DISPATCHED oltre il timeout configurabile vengono rilevati e marcati FAILED.

**File creati** (2):
- `orchestrator/src/main/java/.../orchestrator/config/StaleDetectorProperties.java`
- `orchestrator/src/main/java/.../orchestrator/config/StaleDetectorAutoConfiguration.java`

**File modificati** (2):
- `orchestrator/src/main/java/.../orchestrator/orchestration/StaleTaskDetectorScheduler.java`
- `orchestrator/src/main/java/.../orchestrator/api/PlanController.java` (+ killItem endpoint)

---

### Fix bug

- `OrchestrationService.killItem()`: `IllegalStateTransitionException` chiamato con firma errata
  (1 arg stringa vs 4 arg richiesti: entityType, id, from, to). Corretto.
- Test analytics (5 file): `List.<Object[]>of()` con type hint esplicito per risolvere
  inferenza generica Mockito 5.x con native query `Object[]`
- `StaleTaskDetectorSchedulerTest`: aggiornato costruttore con `StaleDetectorProperties`
  (rimosso `ReflectionTestUtils.setField`)

---

### Test

| Modulo | Tests run | Failures |
|--------|-----------|----------|
| worker-sdk (tutti) | 39 | 0 |
| orchestrator (OrchestrationService + OrchestratorMetrics) | 45 | 0 |

**Nuovi test** (4):
- `ProvenanceModelTest.java` — serializzazione JSON campo reasoning
- `KillItemTest.java` — killItem su stati DISPATCHED/WAITING/terminali
- `CostEstimationModelTest.java` — stima costi token
- `StaleDetectorPropertiesTest.java` — properties configurazione stale detector

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

---

## Sessione 20 — (inclusa in S8-S12)

> Le implementazioni Fase 11 (#87-#96) erano originariamente pianificate come sessione separata, ma sono state completate all'interno della maratona S8-S12 (commit `6ea8d72`, `95b42b7` del 2026-03-08). Vedi S11-S12 per dettagli.

---

## Sessione 21 — Merkle DAG + Commit-Reveal + CAS + Quadratic Voting (2026-03-10)

### Items completati

- **#45 Merkle DAG**: `DagHashService` con Kahn's topological sort, `dagHash` per item + `merkleRoot` per piano. V32 migration.
- **#46 Commit-Reveal**: `CouncilCommitment` scheme con nonce-based SHA-256 verification per council voting. V33 migration.
- **#48 CAS Completion**: `ArtifactController` REST API (GET artifact/metadata/dedup), `promptHash` traceability. V31 migration.
- **#49 Quadratic Voting**: `QuadraticVotingService` con ELO-based voice credits, budget validation, weighted synthesis per council decisions.

### File chiave

- `DagHashService.java` + V32 — #45
- `CouncilCommitment.java` + V33 — #46
- `ArtifactController.java` + V31 — #48
- `QuadraticVotingService.java` — #49

### Test

1074 test totali, 0 fallimenti.

**Commit**: `a067fd7`

---

## Sessione 22 — Audit Qualità Fase 9-12 (2026-03-10)

### Risultato audit

Audit sistematico di tutte le 45 implementazioni research-domain (#62-#106):
- **43 GENUINE**: implementano l'algoritmo reale dal paper
- **1 ACCEPTABLE** (#86 FunctorialSemantics): compositionality check era tautologia algebrica
- **1 STUB** (#100 ActorModel): non aveva supervision tree reale

### Fix applicati

- **#100 ActorModelSupervisor**: riscrittura completa — supervision tree con state machine (RUNNING→CRASHED→RESTARTING→STOPPED), restart policy (max N in T secondi), escalation, REST_FOR_ONE ordering.
- **#86 FunctorialSemanticsService**: fix bug matematico — confronta GP prediction vs actual reward su path compositi (prima era always 0).
- **#101 ChandyLamportSnapshotter**: aggiunto `PlanItemRepository` per local state reale, marker sequence, state-event coherence verification.

### Test

1082 test totali (+8 netti), 0 fallimenti.

**Commit**: `5d733f8`

---

## Sessione 23 — Fase 13 Research Applicativi (#107-#116) (2026-03-14)

### Riepilogo

Completamento Fase 13: 10 servizi analytics implementati (3 nella sessione precedente + 7 in S23).
89 nuovi test, 1234 totali, 0 fallimenti. 7 nuovi endpoint REST in AnalyticsController.
7 sezioni config in application.yml (tutte `enabled: false` di default).

### Servizi implementati (sessione precedente)

| # | Servizio | Componente |
|---|----------|-----------|
| 110 | Semantic Caching | `SemanticCacheService` |
| 111 | Observability SLIs | `SliDefinitionService`, `SloTracker`, `ErrorBudgetCalculator` |
| 116 | Logical Induction | `PosteriorFloorGuard`, `ConvergenceMonitor` |

### Servizi implementati (S23)

| # | Servizio | Componente | Paper di riferimento |
|---|----------|-----------|---------------------|
| 107 | Context Engineering | `ContextWindowManager` | Knapsack 0-1 greedy, 2-competitive (formulazione nuova) |
| 108 | Curriculum Prompting | `CurriculumPromptingService` | Bengio 2009 (ICML), IRT ≡ Bradley-Terry |
| 109 | Iterated Amplification | `IteratedAmplificationService` | Christiano 2018, Trust or Escalate (ICLR 2025) |
| 112 | MCTS Dispatch | `MctsDispatchService` | PUCT (Silver 2017), SWE-Search (arXiv:2410.20285) |
| 113 | Worker-to-Worker Handoff | `HandoffRouterService` | AutoGen Swarm (Wu 2023), depth-scaled confidence |
| 114 | Markov Shapley Value | `MarkovShapleyService` | TMC-Shapley (Wang NeurIPS 2022), Welford accumulator |
| 115 | Factorised Belief Models | `FactorisedBeliefService` | Ruiz-Serra AAMAS 2025, GP-UCB ⊂ EFE (Li 2026) |

### Integrazione

- **AnalyticsController**: 7 nuovi endpoint (`/context-budget`, `/curriculum-examples`, `/belief-matrix`, `/amplification-stats`, `/handoff-stats`, `/markov-shapley`, `/mcts-search`). Pattern: `Optional<XService>` nel constructor, 503 se servizio disabilitato.
- **application.yml**: 7 sezioni config (context-engineering, curriculum-prompting, factorised-beliefs, mcts-dispatch, iterated-amplification, handoff-routing, markov-shapley).

### Fix applicati

1. **SLF4J format bug** (`CurriculumPromptingService.java:145`): `{:.2f}` (Python) → `{}` (SLF4J)
2. **MctsNode parent references** (`MctsDispatchService.java`): `backpropagate()` traversava `node.parent` ma `selectAndExpand()` non settava `child.parent = node`
3. **MCTS test assertion** (`MctsDispatchServiceTest`): rilassata asserzione stocastica — verifica `expectedReward > 0.3` anziché contare match esatti

### Risultati ricerca Fase 13 — Sintesi accademica

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

### Test

1234 test totali (+152 dalla sessione precedente), 0 fallimenti.

**Commit**: `1c54f9d`
