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
| **RAG** | **RAG Pipeline + Graph RAG (3 sessioni)** | **10g** | **Molto alto** | — |

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
    │   ├── search/                                  # ← Sessione 2
    │   │   ├── RagSearchService.java               # API unificata
    │   │   ├── HybridSearchService.java            # pgvector + FTS + RRF
    │   │   ├── HydeQueryTransformer.java           # Risposta ipotetica → embedding → search
    │   │   └── reranking/
    │   │       ├── Reranker.java                   # Interface
    │   │       ├── CascadeReranker.java            # Cosine → LLM (2 stage)
    │   │       ├── CosineReranker.java             # Stage 1 (~1ms)
    │   │       ├── LlmReranker.java                # Stage 2 (~100ms)
    │   │       └── NoOpReranker.java               # Passthrough
    │   ├── graph/                                   # ← Sessione 2
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
    │   └── tool/                                    # ← Sessione 3
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
    embedding     vector(1024),          -- mxbai-embed-large: 1024 dimensioni
    search_vector tsvector               -- BM25 full-text search
);

CREATE INDEX idx_vector_store_embedding ON vector_store
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);
CREATE INDEX idx_vector_store_metadata ON vector_store
    USING gin (metadata jsonb_path_ops);
CREATE INDEX idx_vector_store_fts ON vector_store
    USING gin (search_vector);

CREATE OR REPLACE FUNCTION update_search_vector() RETURNS trigger AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', NEW.content);
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_update_search_vector
    BEFORE INSERT OR UPDATE OF content ON vector_store
    FOR EACH ROW EXECUTE FUNCTION update_search_vector();
```

### S1-C. Modulo Maven `shared/rag-engine`

Root `pom.xml`: `<module>shared/rag-engine</module>` + dependency management.

Dipendenze:
- `spring-ai-pgvector-store-spring-boot-starter`
- `spring-ai-ollama-spring-boot-starter`
- `spring-boot-starter-data-redis`
- `spring-boot-starter-data-jdbc`
- `io.github.massimilianopili:spring-ai-reactive-tools`

### S1-D. RagProperties

```java
@ConfigurationProperties("rag")
public record RagProperties(
    boolean enabled,                        // true
    Ingestion ingestion,
    Search search,
    Ollama ollama,
    Cache cache
) {
    public record Ingestion(
        int chunkSize,                      // 512
        int chunkOverlap,                   // 100
        boolean contextualEnrichment,       // true
        int maxFileSizeKb,                  // 500
        List<String> includeExtensions,     // [java, yml, yaml, md, xml, json, go, rs, js, ts, py, sql]
        boolean fileWatcherEnabled          // false
    ) {}
    public record Search(
        boolean hybridEnabled,              // true
        boolean hydeEnabled,                // true
        String rerankerType,                // "cascade"
        int topK,                           // 20
        int finalK,                         // 8
        double similarityThreshold,         // 0.5
        int rrfK                            // 60
    ) {}
    public record Ollama(
        String embeddingModel,              // mxbai-embed-large
        String rerankingModel,              // qwen2.5:1.5b
        String baseUrl                      // http://ollama:11434
    ) {}
    public record Cache(
        int redisDb,                        // 5
        int embeddingTtlHours,              // 24
        int searchResultTtlMinutes          // 60
    ) {}
}
```

### S1-E. Ingestion Components

| Classe | Responsabilita' |
|---|---|
| `CodeDocumentReader` | Scansiona directory, filtra estensione/size, crea `Document` Spring AI |
| `RecursiveCodeChunker` | `.java/.go/.rs/.js/.ts/.py` — 512 tok, 100 overlap, confini metodo |
| `PropositionChunker` | `.md/.yml/.xml` — fatti atomici via LLM, fallback split per headers |
| `ContextualEnricher` | Pattern Anthropic: Claude genera 50-100 tok contesto, prepend a chunk |
| `MetadataEnricher` | Language, entities (classi/package), docType, keyphrases |
| `IngestionPipeline` | Orchestra 5 fasi: extract → chunk → enrich → embed → index |
| `IngestionService` | API + `@EventListener(PlanCompletedEvent)` + scheduling |
| `FileWatcherService` | WatchService NIO (inotify Linux), debounce 5s |

### S1-F. Test (~43)

`RagPropertiesTest` (5), `RecursiveCodeChunkerTest` (8), `PropositionChunkerTest` (6),
`ContextualEnricherTest` (4), `MetadataEnricherTest` (5), `IngestionPipelineTest` (6),
`EmbeddingCacheServiceTest` (5), `CodeDocumentReaderTest` (4).

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

## Sessione 3 — RAG_MANAGER Worker + Integrazione Completa

### S3-A. RAG_MANAGER Worker

Output JSON:
```json
{
  "semantic_chunks": [{"content": "...", "filePath": "...", "score": 0.87, "context": "..."}],
  "graph_insights": [{"type": "code_dependency", "source": "...", "target": "...", "relation": "IMPORTS"}],
  "related_files": ["path/to/file1.java"],
  "search_metadata": {"mode": "hybrid+hyde+cascade", "totalCandidates": 20, "rerankStages": 2}
}
```

### S3-B. SemanticSearch MCP Tool

`@ReactiveTool(name="SemanticSearch")` per CONTEXT_MANAGER e RAG_MANAGER.

### S3-C. Council Enrichment

`CouncilRagEnricher`: cerca decisioni passate dal knowledge_graph.
`CouncilService`: inietta `Optional<CouncilRagEnricher>`.

### S3-D. Orchestrator Integration

- `RAG_MANAGER` in `WorkerProfileRegistry` e `PlannerService`
- Dispatch via messaging (come qualsiasi worker)

### S3-E. Test (~23)

`SemanticSearchToolTest` (4), `RagManagerWorkerTest` (6), `CouncilRagEnricherTest` (4),
`FileWatcherServiceTest` (4), `IngestionServiceTest` (5).

---

## Riepilogo File per Sessione

| Sessione | File nuovi | File mod | Test nuovi | Test totali |
|---|---|---|---|---|
| **S1** (Infra + Ingestion) ✅ | 25 | 5 | 53 | 261 |
| **S2** (Search + Graph + Java 21) ✅ | 15 | 10 | 47 | 308 |
| **S3** (RAG_MANAGER + Integ.) | ~10 | ~5 | ~23 | ~331 |

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
