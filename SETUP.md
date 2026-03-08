# Setup Guide

Guida operativa per buildare, avviare e usare l'agent-framework.
Per la documentazione architetturale, vedi [docs/architecture/overview.md](docs/architecture/overview.md).

## 1. Prerequisiti

| Requisito | Versione | Verifica |
|-----------|----------|----------|
| Java | 21 (Eclipse Temurin) | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker | 24+ con Compose V2 | `docker compose version` |
| API Key | Anthropic (Claude) | `echo $ANTHROPIC_API_KEY` |

Ottenere una API key: [console.anthropic.com](https://console.anthropic.com/)

## 2. Build

Build completo (43 worker + 10 moduli shared/infra = 53 moduli Maven):

```bash
./build.sh
```

Il primo build scarica ~500 MB di dipendenze Maven. I build successivi sono incrementali.

Output atteso:

```
═══ Step 1/2: Generating worker modules + registry ═══
═══ Step 2/2: Building reactor ═══
[INFO] Reactor Summary:
[INFO]   messaging-api ................................. SUCCESS
[INFO]   messaging-redis ............................... SUCCESS
[INFO]   rag-engine .................................... SUCCESS
[INFO]   gp-engine ..................................... SUCCESS
[INFO]   orchestrator .................................. SUCCESS
[INFO]   worker-sdk .................................... SUCCESS
[INFO]   be-java-worker ................................ SUCCESS
[INFO]   ...
[INFO] BUILD SUCCESS
[INFO] 53 modules
```

### Perche' build.sh e non mvn diretto?

Il compiler plugin (`agent-compiler-maven-plugin`) genera i worker da
`agents/manifests/*.agent.yml`. Maven risolve `<modules>` prima di eseguire i plugin,
quindi al primo build le directory worker non esistono. `build.sh` risolve il
chicken-and-egg con un build a due fasi: generazione (`-N` non-recursive) poi reactor completo.

### Rebuild veloce

Quando i worker sono gia' generati (directory esistono):

```bash
mvn install -DskipTests
```

### Rigenerare i worker dopo modifica manifest

```bash
mvn -N \
  com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:validate-manifests \
  com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-workers \
  com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-registry
mvn install -DskipTests
```

I file in `execution-plane/workers/` sono **generati** — non modificarli a mano.
Per customizzare un worker, modificare il manifest YAML o il SKILL.md corrispondente.

## 3. Avvio Locale (Dev)

```bash
docker compose -f docker/docker-compose.dev.yml up -d
```

Servizi avviati:

| Servizio | Container | Porta | Credenziali | Scopo |
|----------|-----------|-------|-------------|-------|
| PostgreSQL 16 + pgvector | agentfw-postgres | 5432 | `agentframework` / `agentframework` | Database piani, stato, vector store |
| Redis 7 | agentfw-redis | 6379 | — | Messaging (DB 3), cache (DB 4), RAG cache (DB 5) |
| Ollama | agentfw-ollama | 11434 | — | Embedding + reranking modelli |

### Setup modelli Ollama (primo avvio)

```bash
docker exec agentfw-ollama ollama pull mxbai-embed-large    # embedding 1024 dim
docker exec agentfw-ollama ollama pull qwen2.5:1.5b          # reranking
```

### Verifica

```bash
docker compose -f docker/docker-compose.dev.yml ps
```

Tutti e tre i container devono risultare `running (healthy)`.

Flyway crea automaticamente le tabelle del database al primo avvio dell'orchestrator.
Non serve inizializzazione manuale.

## 4. Avvio Orchestrator

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run -pl control-plane/orchestrator
```

L'orchestrator usa il profilo `redis` di default (Redis Streams per messaging).

### Verifica

```bash
curl http://localhost:8080/management/health
```

Output atteso: `{"status":"UP"}`

Nel log di startup, verificare:

```
WorkerProfileRegistry validated: 34 profiles, 5 defaults
Started AgentFrameworkOrchestratorApplication in X.XXs
```

L'orchestrator ascolta sulla porta **8080** e accetta richieste REST.

### Profili Spring disponibili

| Profilo | Effetto |
|---------|---------|
| _(default)_ | Redis Streams per messaging |
| `jms` | Apache Artemis JMS (richiede Artemis avviato) |
| `mcp` | Abilita connessione a MCP server esterno per tool |

## 5. Avvio Worker

I worker sono necessari solo per **eseguire** i task. L'orchestrator puo' creare
piani anche senza worker attivi (i task restano in stato DISPATCHED).

Ogni worker richiede `ANTHROPIC_API_KEY` nell'ambiente.

### Comando generico

```bash
mvn spring-boot:run -pl execution-plane/workers/<nome>-worker
```

### Worker disponibili (43 totali)

**Backend (12)**

| Worker | Stack | Comando |
|--------|-------|---------|
| be-java | Spring Boot | `mvn spring-boot:run -pl execution-plane/workers/be-java-worker` |
| be-kotlin | Spring Boot (Kotlin) | `mvn spring-boot:run -pl execution-plane/workers/be-kotlin-worker` |
| be-go | Go stdlib | `mvn spring-boot:run -pl execution-plane/workers/be-go-worker` |
| be-rust | Rust | `mvn spring-boot:run -pl execution-plane/workers/be-rust-worker` |
| be-node | Node.js | `mvn spring-boot:run -pl execution-plane/workers/be-node-worker` |
| be-python | Python/FastAPI | `mvn spring-boot:run -pl execution-plane/workers/be-python-worker` |
| be-dotnet | .NET C# | `mvn spring-boot:run -pl execution-plane/workers/be-dotnet-worker` |
| be-elixir | Elixir/Phoenix | `mvn spring-boot:run -pl execution-plane/workers/be-elixir-worker` |
| be-ocaml | OCaml | `mvn spring-boot:run -pl execution-plane/workers/be-ocaml-worker` |
| be-quarkus | Quarkus (Java) | `mvn spring-boot:run -pl execution-plane/workers/be-quarkus-worker` |
| be-cpp | C++ | `mvn spring-boot:run -pl execution-plane/workers/be-cpp-worker` |
| be-laravel | Laravel (PHP) | `mvn spring-boot:run -pl execution-plane/workers/be-laravel-worker` |

**Frontend (6)**

| Worker | Stack | Comando |
|--------|-------|---------|
| fe-react | React | `mvn spring-boot:run -pl execution-plane/workers/fe-react-worker` |
| fe-vue | Vue.js | `mvn spring-boot:run -pl execution-plane/workers/fe-vue-worker` |
| fe-angular | Angular | `mvn spring-boot:run -pl execution-plane/workers/fe-angular-worker` |
| fe-svelte | Svelte | `mvn spring-boot:run -pl execution-plane/workers/fe-svelte-worker` |
| fe-vanillajs | Vanilla JS | `mvn spring-boot:run -pl execution-plane/workers/fe-vanillajs-worker` |
| fe-nextjs | Next.js | `mvn spring-boot:run -pl execution-plane/workers/fe-nextjs-worker` |

**Database (10)**

| Worker | Stack | Comando |
|--------|-------|---------|
| dba-postgres | PostgreSQL | `mvn spring-boot:run -pl execution-plane/workers/dba-postgres-worker` |
| dba-mysql | MySQL/MariaDB | `mvn spring-boot:run -pl execution-plane/workers/dba-mysql-worker` |
| dba-oracle | Oracle | `mvn spring-boot:run -pl execution-plane/workers/dba-oracle-worker` |
| dba-mssql | SQL Server | `mvn spring-boot:run -pl execution-plane/workers/dba-mssql-worker` |
| dba-sqlite | SQLite/libSQL | `mvn spring-boot:run -pl execution-plane/workers/dba-sqlite-worker` |
| dba-mongo | MongoDB | `mvn spring-boot:run -pl execution-plane/workers/dba-mongo-worker` |
| dba-graphdb | Neo4j/AGE | `mvn spring-boot:run -pl execution-plane/workers/dba-graphdb-worker` |
| dba-vectordb | pgvector | `mvn spring-boot:run -pl execution-plane/workers/dba-vectordb-worker` |
| dba-redis | Redis/Valkey | `mvn spring-boot:run -pl execution-plane/workers/dba-redis-worker` |
| dba-cassandra | Cassandra/ScyllaDB | `mvn spring-boot:run -pl execution-plane/workers/dba-cassandra-worker` |

**Mobile (2)**

| Worker | Stack | Comando |
|--------|-------|---------|
| mobile-kotlin | Android (Kotlin/Compose) | `mvn spring-boot:run -pl execution-plane/workers/mobile-kotlin-worker` |
| mobile-swift | iOS (Swift/SwiftUI) | `mvn spring-boot:run -pl execution-plane/workers/mobile-swift-worker` |

**Infrastruttura (9)**

| Worker | Ruolo |
|--------|-------|
| context-manager | Esplorazione codebase per contesto mancante |
| schema-manager | Inferenza interfacce API |
| rag-manager | Ricerca semantica su vector/graph DB |
| task-manager | Gestione lifecycle task |
| compensator-manager | Saga pattern per rollback task falliti |
| hook-manager | Generazione policy hook per task |
| audit-manager | Audit trail operazioni |
| event-manager | Gestione eventi sistema |
| advisory | Consigli architetturali |

**Quality (4)**

| Worker | Ruolo |
|--------|-------|
| contract | Validazione contratti/schema |
| review | Code review automatica |
| ai-task | Task AI generici |
| sdk-scaffold | Scaffold SDK per nuovi linguaggi |

### Quale worker scegliere?

L'orchestrator seleziona automaticamente in base alla spec. I default sono:

| Tipo task | Worker default |
|-----------|---------------|
| Backend | be-java (Spring Boot) |
| Frontend | fe-react |
| Database | dba-postgres |
| Mobile | mobile-swift |

Per forzare un profilo specifico nella spec: `"workerProfile": "mobile-kotlin"`.

### Modalita' MCP Client (opzionale)

Per connettere i worker a un server MCP esterno:

```bash
SPRING_PROFILES_ACTIVE=mcp MCP_GIT_URL=http://localhost:8080 \
  mvn spring-boot:run -pl execution-plane/workers/be-java-worker
```

Per dettagli sulle 3 modalita' (in-process, external, hybrid): [mcp/docs/usage.md](mcp/docs/usage.md).

## 6. Primo Plan — End-to-End

### Creare un piano

```bash
curl -s -X POST http://localhost:8080/api/v1/plans \
  -H "Content-Type: application/json" \
  -d '{"spec":"Build a REST API for user management with CRUD endpoints"}' \
  | python3 -m json.tool
```

Risposta (esempio):

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "RUNNING",
  "items": [
    {
      "taskKey": "BE-001",
      "workerType": "BE",
      "workerProfile": "be-java",
      "status": "WAITING"
    }
  ]
}
```

### Monitorare in tempo reale (SSE)

```bash
curl -N http://localhost:8080/api/v1/plans/{planId}/events
```

### Verificare stato

```bash
curl -s http://localhost:8080/api/v1/plans/{planId} | python3 -m json.tool
```

Flusso:

```
PENDING  →  Council advisory (se abilitato) → Planner decompone la spec in task
RUNNING  →  PlanItems creati, orchestrator dispatcha verso i worker
         →  Worker eseguono e ritornano AgentResult
         →  Quality gate valuta risultati (ralph-loop se necessario)
COMPLETED/FAILED  →  Tutti gli items terminati
```

### Endpoint utili

| Azione | Comando |
|--------|---------|
| Stato piano | `curl http://localhost:8080/api/v1/plans/{planId}` |
| Quality gate | `curl http://localhost:8080/api/v1/plans/{planId}/quality-gate` |
| Retry task fallito | `curl -X POST http://localhost:8080/api/v1/plans/{planId}/items/{itemId}/retry` |
| Storico dispatch | `curl http://localhost:8080/api/v1/plans/{planId}/items/{itemId}/attempts` |
| Snapshot piano | `curl http://localhost:8080/api/v1/plans/{planId}/snapshots` |
| Ripristina snapshot | `curl -X POST http://localhost:8080/api/v1/plans/{planId}/restore/{snapshotId}` |

## 7. Deploy su SOL Server

Il framework gira sul server SOL riusando l'infrastruttura Docker esistente.

### Infrastruttura gia' disponibile

- **Redis** (`redis:7-alpine`) sulla rete `shared` — DB 3 libero per messaging
- **Ollama** (`ollama/ollama`) sulla rete `shared` — modello `mxbai-embed-large` gia' scaricato
- **Rete Docker** `shared` — tutti i container si raggiungono per nome DNS

### Setup credenziali

```bash
cd /data/massimiliano/agent-framework
cat > docker/sol.env << 'EOF'
ANTHROPIC_API_KEY=claude_client
DB_PASSWORD=agentfw_s3cure
EOF
```

**Nota**: `ANTHROPIC_API_KEY=claude_client` e' il client_id per il proxy-ai (`proxy-ai:8097`),
NON una API key standard Anthropic. Il compose imposta `SPRING_AI_ANTHROPIC_BASE_URL=http://proxy-ai:8097`
su orchestrator e worker, redirigendo tutte le chiamate Claude attraverso il reverse proxy
che gestisce OAuth, tier enforcement e forwarding.

### Avvio completo

```bash
mkdir -p data/postgres
docker compose -f docker/docker-compose.sol.yml --env-file docker/sol.env up -d
```

### Avvio selettivo (solo worker necessari)

Per risparmiare RAM, avviare solo i servizi richiesti dal progetto:

```bash
# Esempio: progetto Android (mobile-kotlin)
docker compose -f docker/docker-compose.sol.yml --env-file docker/sol.env up -d \
  agentfw-postgres orchestrator mobile-kotlin-worker context-manager-worker review-worker

# Esempio: progetto fullstack Java + React
docker compose -f docker/docker-compose.sol.yml --env-file docker/sol.env up -d \
  agentfw-postgres orchestrator be-java-worker fe-react-worker contract-worker review-worker
```

### Servizi nel docker-compose.sol.yml

| Servizio | Container | RAM stimata | Ruolo |
|----------|-----------|-------------|-------|
| agentfw-postgres | agentfw-postgres | ~80 MB | Database piani e stato (pgvector) |
| orchestrator | agentfw-orchestrator | ~512 MB | Coordinamento, pianificazione, dispatch |
| be-java-worker | agentfw-be-java-worker | ~256 MB | Backend Java/Spring Boot |
| fe-react-worker | agentfw-fe-react-worker | ~256 MB | Frontend React |
| contract-worker | agentfw-contract-worker | ~256 MB | Validazione contratti |
| review-worker | agentfw-review-worker | ~256 MB | Code review |
| context-manager-worker | agentfw-context-manager-worker | ~256 MB | Espansione contesto |
| schema-manager-worker | agentfw-schema-manager-worker | ~256 MB | Inferenza schema |
| mobile-kotlin-worker | agentfw-mobile-kotlin-worker | ~256 MB | Android Kotlin/Compose |

L'Ollama del compose e' disabilitato — riusa l'istanza gia' attiva sulla rete `shared`.
Il `OLLAMA_BASE_URL` dell'orchestrator punta a `http://ollama:11434` (DNS Docker).

**Immagine Postgres**: il compose usa `sol/postgres:pg16-age` (custom, con pgvector + Apache AGE).
L'immagine base `pgvector/pgvector:pg16` NON funziona perche' la migrazione Flyway V6 richiede AGE.

**Skill files**: i worker montano `.claude/` e `skills/` come volumi read-only con `FS_SKILLS_DIR=/skills`.
Senza questo mount, i worker falliscono con "Skill file not found".

### Verifica

```bash
docker ps --filter name=agentfw
docker logs agentfw-orchestrator --tail 30
```

### Sottomettere un piano

```bash
# Dall'host SOL (via IP container sulla rete shared)
ORCH_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' agentfw-orchestrator)
curl -s -X POST "http://$ORCH_IP:8080/api/v1/plans" \
  -H "Content-Type: application/json" \
  -d '{"spec":"...", "requiresReview": true, "riskLevel": "LOW"}' | python3 -m json.tool

# Stato piano
curl -s "http://$ORCH_IP:8080/api/v1/plans/{planId}" | python3 -m json.tool
```

**Nota**: `curl` non e' disponibile nel container orchestrator (Alpine JRE). Usare l'IP del
container dall'host oppure un container con curl sulla stessa rete `shared`.

### Pull modello reranking (opzionale)

```bash
docker exec ollama ollama pull qwen2.5:1.5b
```

Senza il modello di reranking, la RAG pipeline funziona comunque con solo embedding.

## 8. Variabili d'Ambiente

| Variabile | Default | Obbligatoria | Descrizione |
|-----------|---------|:------------:|-------------|
| `ANTHROPIC_API_KEY` | — | Si | API key Anthropic per Claude |
| `DB_HOST` | `localhost` | No | Host PostgreSQL |
| `DB_USER` | `agentframework` | No | Utente database |
| `DB_PASSWORD` | (vuoto) | No | Password database (impostare in produzione) |
| `REDIS_HOST` | `redis` | No | Host Redis |
| `REDIS_PORT` | `6379` | No | Porta Redis |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | No | URL Ollama per embedding/reranking |
| `SPRING_AI_ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | No | Base URL per le chiamate Claude. Su SOL: `http://proxy-ai:8097` |
| `FS_SKILLS_DIR` | — | No | Directory override per skill files (SKILL.md). Su SOL: `/skills` con volume mount |
| `SPRING_PROFILES_ACTIVE` | — | No | Profili: `redis` (Redis Streams), `jms` (Artemis), `mcp` (MCP client) |
| `MCP_GIT_URL` | `http://mcp-server:8080` | No | URL server MCP per tool Git (solo con profilo `mcp`) |
| `MCP_REPO_FS_URL` | `http://mcp-server:8080` | No | URL server MCP per tool filesystem (solo con profilo `mcp`) |

In locale con `docker-compose.dev.yml`, l'unica variabile obbligatoria e' `ANTHROPIC_API_KEY`.
Sul server SOL, impostare anche `DB_PASSWORD` in `docker/sol.env`.

## 9. Provider Messaging

Redis Streams e' il provider di default. Per switchare:

### Redis Streams (default)

```yaml
messaging:
  provider: redis
  redis:
    host: ${REDIS_HOST:redis}
    port: ${REDIS_PORT:6379}
    database: 3           # DB dedicato (0/1/2 riservati a Gitea su SOL)
    cache-database: 4
```

### JMS (Apache Artemis)

Attivare il profilo `jms` e avviare Artemis:

```bash
SPRING_PROFILES_ACTIVE=jms mvn spring-boot:run -pl control-plane/orchestrator
```

### Azure Service Bus

```yaml
messaging:
  provider: servicebus

azure:
  servicebus:
    connection-string: "Endpoint=sb://...;SharedAccessKeyName=...;SharedAccessKey=..."
```

Per dettagli: [messaging/README.md](messaging/README.md).

## 10. Deploy Azure

L'infrastruttura Azure e' definita in Bicep con 4 ambienti parametrizzati.

### Risorse create

- **Container Apps Environment** — orchestrator + worker containers
- **Service Bus** — topic/subscription con SQL filter per routing
- **PostgreSQL Flexible Server** — storage piani e stato
- **Key Vault** — segreti (API key, connection string)
- **Application Insights** — monitoring e tracing

### Deploy

```bash
az login
az deployment group create \
  --resource-group rg-agent-framework-dev \
  --template-file infra/azure/bicep/main.bicep \
  --parameters infra/azure/bicep/env/develop.parameters.json
```

Ambienti disponibili: `develop`, `test`, `collaudo`, `prod`.
Parametri per ambiente in `infra/azure/bicep/env/{ambiente}.parameters.json`.

### Docker Images

Ogni worker ha un Dockerfile generato dal compiler plugin:

```bash
cd execution-plane/workers/be-java-worker
docker build -t agent-framework/be-java-worker:latest .
```

## 11. IDE Setup

### IntelliJ IDEA

1. **File → Open** → selezionare la directory `agent-framework`
2. IntelliJ rileva automaticamente il progetto Maven multi-modulo
3. Impostare **Project SDK** su Java 21 (Temurin)
4. **Build → Build Project** per validare la configurazione

### VS Code

1. Installare **Java Extension Pack** + **Spring Boot Extension Pack**
2. Aprire la directory `agent-framework`
3. Il Language Server Java indicizza automaticamente i moduli

## 12. Troubleshooting

| Problema | Causa probabile | Soluzione |
|----------|----------------|-----------|
| `BUILD FAILURE` al primo build | Java non e' 21 | `java -version` — installare Temurin 21 |
| `BUILD FAILURE` su worker-sdk | build.sh non usato | Usare `./build.sh` al primo build (genera i worker) |
| `Connection refused` su :5432 | PostgreSQL non avviato | `docker compose -f docker/docker-compose.dev.yml up -d postgres` |
| `Connection refused` su :6379 | Redis non avviato | `docker compose -f docker/docker-compose.dev.yml up -d redis` |
| `WorkerProfileRegistry validation failed` | Config profili inconsistente | Controllare `application.yml` sezione `worker-profiles` |
| Plan rimane in `PENDING` | API key mancante o invalida | Verificare `echo $ANTHROPIC_API_KEY` |
| Worker non riceve task | Subscription mismatch | Verificare `agent.worker.task-subscription` nel worker `application.yml` |
| RAG search ritorna vuoto | Modello Ollama mancante | `docker exec agentfw-ollama ollama pull mxbai-embed-large` |
| `409 Conflict` su retry | Item non in stato FAILED | Solo items FAILED possono essere riprovati |

### Verificare stato container

```bash
docker compose -f docker/docker-compose.dev.yml ps
docker compose -f docker/docker-compose.dev.yml logs postgres --tail 20
docker compose -f docker/docker-compose.dev.yml logs redis --tail 20
```

### Reset completo ambiente locale

```bash
docker compose -f docker/docker-compose.dev.yml down -v   # Cancella volumi (DB, Redis, Ollama)
docker compose -f docker/docker-compose.dev.yml up -d      # Ricrea da zero
./build.sh                                                  # Rebuild completo
```

## 13. Costi API

L'orchestrator usa Claude Opus 4.6 per la pianificazione.
I worker usano Claude Sonnet 4.6 per l'esecuzione.

| Modello | Input | Output |
|---------|-------|--------|
| Claude Opus 4.6 | $15 / M token | $75 / M token |
| Claude Sonnet 4.6 | $3 / M token | $15 / M token |
| Claude Haiku 4.5 | $0.80 / M token | $4 / M token |

Un piano tipico (5-8 task) consuma ~100K-150K token totali (~$1-3).

## Riferimenti

- [Orchestrator REST API + domain model](control-plane/orchestrator/README.md)
- [Worker SDK](execution-plane/worker-sdk/README.md)
- [Lista completa worker](execution-plane/workers/README.md)
- [Compiler plugin](execution-plane/agent-compiler-maven-plugin/README.md)
- [Messaging architecture](messaging/README.md)
- [MCP tool integration](mcp/README.md)
- [Configuration management](config/README.md)
- [ADR (Architecture Decision Records)](docs/adr/)
