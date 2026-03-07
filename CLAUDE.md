# Agent Framework

Multi-agent orchestration framework for AI-driven code generation from natural language specifications.

## Architecture

Three-plane architecture:
- **Control Plane** (`control-plane/orchestrator/`): Spring Boot 3.4.1 — REST API, planner (Claude), state DB, Redis Streams messaging
- **Execution Plane** (`execution-plane/workers/`): Specialized AI workers (BE, FE, AI-task, contract, review) using worker-sdk
- **MCP Layer** (`mcp/`): Tool access control with deny-all baseline + per-workerType allowlist
- **Hooks** (`.claude/hooks/`): Deterministic enforcement — PreToolUse blocks, audit logging, secret validation
- **Event Store** (`control-plane/orchestrator/`): `PlanEvent` append-only, audit trail permanente, proiezioni materializzate
- **RAG Engine** (`shared/rag-engine/`): pgvector + Apache AGE, hybrid search (coseno + BM25 + RRF), reranking
- **GP Engine** (`shared/gp-engine/`): Gaussian Process per worker selection + DPO
- **Council** (`control-plane/orchestrator/council/`): Pre-planning advisory (8 membri dinamici, 4 manager + 4 specialist)

## Build

```bash
mvn clean install -DskipTests
```

## Run Orchestrator (dev)

```bash
cd control-plane/orchestrator
docker compose -f docker/docker-compose.dev.yml up -d postgres
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## Required Environment Variables

| Variable | Description |
|----------|-------------|
| `ANTHROPIC_API_KEY` | Anthropic API key for Claude |
| `AZURE_SERVICEBUS_CONNECTION_STRING` | Azure Service Bus connection string |
| `DB_USER` | PostgreSQL username |
| `DB_PASSWORD` | PostgreSQL password |
| `DB_HOST` | PostgreSQL host (default: localhost) |

## Test End-to-End

```bash
curl -X POST http://localhost:8080/api/v1/plans \
  -H "Content-Type: application/json" \
  -d '{"spec":"Build a REST API for user management with JWT authentication"}'
```

## Project Layout

| Directory | Purpose |
|-----------|---------|
| `agents/` | Agent profile definitions (*.agent.md) |
| `prompts/` | Core prompt templates (*.prompt.md) |
| `skills/` | Reusable skill documents |
| `templates/` | Code templates (BE/FE/infra) |
| `patterns/` | Architectural pattern docs |
| `contracts/` | JSON Schemas, OpenAPI, events |
| `config/` | Framework configuration (YAML) |
| `control-plane/` | Orchestrator (Spring Boot) |
| `execution-plane/` | Worker SDK + specialized workers |
| `mcp/` | MCP registry, policies, schemas |
| `.claude/hooks/` | Deterministic enforcement hooks |

## Key Conventions

- **Java 21**, Spring Boot 3.4.1, Spring AI 1.0.0
- **Package root**: `com.agentframework`
- **AutoConfiguration pattern**: `@AutoConfiguration` + `@Import` (like mcp-devops-tools)
- **Tool registration**: Automatic via `@ReactiveTool` scan by `ReactiveToolAutoConfiguration`
- **Messaging**: Redis Streams — singolo stream `agent-tasks` (dispatch) + `agent-results` (completamento), consumer groups per worker type, DB Redis 3
- **Hooks enforcement**: `AGENT_WORKER_TYPE` env var determines per-worker policy

## Implemented Features

| Feature | Descrizione | File principali |
|---------|-------------|-----------------|
| **Event Sourcing** | `PlanEvent` append-only con retention permanente. `Plan`/`PlanItem` sono proiezioni materializzate. Abilita audit trail e SSE replay. | `PlanEvent.java`, `PlanEventStore.java`, `PlanEventRepository.java` |
| **Missing-Context Loop** | Se un worker segnala `missing_context`, l'orchestratore crea automaticamente un task CONTEXT_MANAGER/TASK_MANAGER e rimette il worker in WAITING. Max retry configurabile per manifest. | `OrchestrationService.handleMissingContext()` |
| **Auto-Retry** | `AutoRetryScheduler` ritenta task falliti con exponential backoff (`baseDelay * 2^attempt`). Configurabile: `maxRetries`, `baseDelay`, `maxDelay`. | `AutoRetryScheduler.java`, `PlanItem.retryCount/nextRetryAt` |
| **Compensation** | `COMPENSATOR_MANAGER` per saga pattern. Analizza fallimenti e genera task correttivi. Riapertura piano con nuovi item. | `OrchestrationService.compensateFailedItems()` |
| **Token Budget** | Budget token configurabile per WorkerType. Check pre-dispatch previene overrun. | `OrchestrationService.dispatchReadyItems()`, `application.yml` |
| **RAG Pipeline** | Ricerca semantica ibrida: pgvector (embedding 1024 dim) + Apache AGE (graph DB) + BM25. 3 grafi: `knowledge_graph`, `code_graph`, `task_graph`. | `shared/rag-engine/`, `RagManagerWorker.java` |
| **GP Worker Selection** | Gaussian Process predice il profilo worker ottimale dato l'embedding del task. `sigma^2` alto → trigger REVIEW. Cold-start graceful (degenera al prior). | `shared/gp-engine/`, `GaussianProcessService.java`, `WorkerSelectionPredictor.java` |
| **DPO con GP Residual** | Direct Preference Optimization con residual del GP. `task_outcomes` tabella separata per training (OLAP vs OLTP). | `shared/gp-engine/`, Flyway V8 (`task_outcomes`) |
| **Ralph-Loop** | Quality Gate feedback loop: il REVIEW worker puo' rimandare task al domain worker con feedback specifico per miglioramento iterativo. | `OrchestrationService`, Ralph-Loop integration |
| **Council** | Pre-planning advisory con 8 membri dinamici (4 manager + 4 specialist). Produce raccomandazioni architetturali (CSP, pattern, security) che influenzano il piano. | `CouncilService.java`, `council/` package |

## Messaging (Redis Streams)

| Stream | Direzione | Contenuto |
|--------|-----------|-----------|
| `agent-tasks` | Orchestratore → Worker | Task da eseguire (JSON: taskKey, workerType, context, dependencies) |
| `agent-results` | Worker → Orchestratore | Risultati completati (JSON: taskKey, status, resultJson, artifacts) |

- **Consumer groups**: un gruppo per worker type, ogni worker istanza e' un consumer nel gruppo
- **Filtering**: client-side in `WorkerTaskConsumer.shouldProcess()` (singolo stream, tutti i tipi)
- **Redis DB**: 3 (dedicato al messaging, separato da cache/sessioni)
- **ACK**: `XACK` dopo processing (vedi PIANO.md B1 per bug noto: ACK fuori transazione)

## RAG Pipeline

Infrastruttura per Retrieval-Augmented Generation, implementata in 3 sessioni (S1-S3, ~100 test).

- **Vector DB**: pgvector su PostgreSQL (`embeddings` database), tabella `vector_store`, indice HNSW cosine distance
- **Embedding**: `mxbai-embed-large` via Ollama (1024 dim), cache Redis DB 5 (24h TTL)
- **Graph DB**: Apache AGE v1.6.0 (estensione PostgreSQL), 3 grafi: `knowledge_graph`, `code_graph`, `task_graph`
- **Hybrid Search**: coseno (pgvector) + BM25 (tsvector) + Reciprocal Rank Fusion (RRF, k=60)
- **Reranking**: cross-encoder locale (Ollama `qwen2.5:1.5b`) per riordinare i risultati
- **Ingestion**: chunking (512 token, 50 overlap) → embedding → vector store + graph extraction → AGE
- **Worker**: `RAG_MANAGER` (nota: pipeline costruita ma non collegata — vedi PIANO.md #23)

## GP Engine

Modulo `shared/gp-engine/` per selezione intelligente del profilo worker.

- **Gaussian Process**: kernel RBF su embedding task (1024 dim). Predice `(mu, sigma^2)` per ogni profilo candidato
- **Training data**: `task_outcomes` tabella (embedding, profilo scelto, `aggregated_reward`, ELO snapshot)
- **DPO**: Direct Preference Optimization con residual GP — usa coppie di preferenza per affinare la selezione
- **Cold start**: con pochi dati (<50 task), degenera al prior (= selezione default attuale)
- **Trigger REVIEW**: se `sigma^2` supera soglia → incertezza alta → il task passa prima a REVIEW
