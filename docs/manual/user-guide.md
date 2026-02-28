# Agent Framework — Manuale Utente

## Indice

1. [Introduzione](#1-introduzione)
2. [Quick Start](#2-quick-start)
3. [Architettura](#3-architettura)
4. [REST API](#4-rest-api)
5. [Agent Manifest](#5-agent-manifest)
6. [Worker SDK](#6-worker-sdk)
7. [Messaging](#7-messaging)
8. [Policy e Sicurezza](#8-policy-e-sicurezza)
9. [Code Generation](#9-code-generation)
10. [Deployment](#10-deployment)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. Introduzione

Agent Framework e' un sistema multi-agente per software delivery automatizzato a partire
da specifiche in linguaggio naturale. Il framework decompone una specifica in un piano
di task, li assegna a worker specializzati (backend, frontend, contract, review), e
orchestra l'esecuzione rispettando dipendenze, policy e quality gate.

### Architettura a 3 piani

| Piano | Componente | Ruolo |
|-------|-----------|-------|
| **Control Plane** | Orchestrator (Spring Boot) | REST API, planner AI (Claude), state machine, dispatch |
| **Execution Plane** | 8 Worker generati | Esecuzione task via Claude AI con tool MCP |
| **MCP Layer** | 5 MCP server + policy | Accesso controllato a git, filesystem, OpenAPI, Azure, test |

I worker sono generati compile-time da manifest YAML tramite un plugin Maven.
Il messaging e' pluggabile (JMS/Artemis, Redis Streams, Azure Service Bus).
Le policy di sicurezza sono definite in YAML e applicate a runtime.

---

## 2. Quick Start

### Prerequisiti

- Java 17 (Temurin consigliato)
- Maven 3.9+
- Docker e Docker Compose
- Una API key Anthropic (`ANTHROPIC_API_KEY`)

### 1. Avvio dipendenze

```bash
cd agent-framework
docker compose -f docker/docker-compose.dev.yml up -d
```

Avvia PostgreSQL 16 (porta 5432) e Apache Artemis (porta 61616) per lo sviluppo locale.

### 2. Build

```bash
mvn clean install
```

Compila tutti i 16 moduli: messaging SPI, orchestrator, compiler plugin, worker SDK, 8 worker generati.

### 3. Avvio orchestrator

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run -pl control-plane/orchestrator
```

L'orchestrator si avvia sulla porta 8080. Flyway crea automaticamente le tabelle al primo avvio.

### 4. Submit del primo plan

```bash
curl -X POST http://localhost:8080/api/v1/plans \
  -H "Content-Type: application/json" \
  -d '{"spec": "Build a REST API for user management with CRUD endpoints, validation, and unit tests"}'
```

Risposta (202 Accepted):
```json
{
  "id": "a1b2c3d4-...",
  "spec": "Build a REST API for user management...",
  "status": "RUNNING",
  "createdAt": "2026-02-27T10:00:00Z",
  "items": [
    {
      "id": "...",
      "taskKey": "CT-001",
      "title": "Define OpenAPI contract for User API",
      "workerType": "CONTRACT",
      "status": "DISPATCHED"
    },
    {
      "id": "...",
      "taskKey": "BE-001",
      "title": "Implement User entity and repository",
      "workerType": "BE",
      "workerProfile": "be-java",
      "status": "WAITING",
      "dependsOn": ["CT-001"]
    }
  ]
}
```

### 5. Verifica stato

```bash
curl http://localhost:8080/api/v1/plans/a1b2c3d4-...
```

Il piano progredisce automaticamente: quando CT-001 completa, BE-001 viene dispatchato, e cosi' via
fino a COMPLETED o FAILED.

---

## 3. Architettura

### Compile-time vs Runtime

```
COMPILE TIME                              RUNTIME

  agents/manifests/                         ┌──────────────┐
  ├── be-java.agent.yml                     │  Orchestrator │
  ├── be-go.agent.yml        mvn            │  (REST API)   │
  ├── fe-react.agent.yml  ─────────►        │  + Planner    │
  ├── contract.agent.yml   generate         └──────┬───────┘
  └── review.agent.yml                             │ dispatch
         │                                         ▼
         │ agent-compiler              ┌────────────────────┐
         │ maven plugin                │   Message Broker    │
         │                             │  (Artemis/Redis/SB) │
         ▼                             └─────────┬──────────┘
  execution-plane/workers/                       │ consume
  ├── be-java-worker/                            ▼
  ├── be-go-worker/                    ┌──────────────────┐
  ├── fe-react-worker/                 │    Workers (8)    │
  ├── ai-task-worker/                  │  AbstractWorker   │
  ├── contract-worker/                 │  + Claude AI      │
  └── review-worker/                   │  + MCP Tools      │
         │                             └──────────┬───────┘
         │ generate-registry                      │ publish
         ▼                                        ▼
  config/                              ┌──────────────────┐
  ├── worker-profiles.yml              │  agent-results    │
  ├── agent-registry.yml               │  (topic)          │
  └── hooks-config.json                └──────────────────┘
```

### Data flow

1. **Utente** invia una specifica (linguaggio naturale) via `POST /api/v1/plans`
2. **Planner** (Claude AI) decompone la specifica in PlanItem con dipendenze
3. **Orchestrator** persiste il piano e dispatcha i task senza dipendenze pendenti
4. **Message broker** ruota i task al worker corretto via SQL filter su `workerProfile`
5. **Worker** esegue il task usando Claude AI + tool MCP (git, fs, test, etc.)
6. **Worker** pubblica `AgentResult` con provenance (token, tool, hash, traceId)
7. **Orchestrator** aggiorna lo stato e dispatcha i task sbloccati
8. Quando tutti i task sono terminali, il piano passa a COMPLETED o FAILED

### State machine

**PlanStatus:**
```
PENDING ──► RUNNING ──► COMPLETED
                   └──► FAILED ──► RUNNING (retry)
```

**ItemStatus:**
```
WAITING ──► DISPATCHED ──► DONE
       └──► FAILED ◄──────┘
                   └──► WAITING (retry)
```

### Worker profile routing

Ogni tipo di worker (BE, FE) puo' avere piu' profili (be-java, be-go, be-rust, be-node).
Il routing avviene tramite SQL filter sulla subscription Azure Service Bus:

| Worker Type | Profile | SQL Filter |
|-------------|---------|-----------|
| BE | be-java | `workerProfile = 'be-java'` |
| BE | be-go | `workerProfile = 'be-go'` |
| FE | fe-react | `workerProfile = 'fe-react'` |
| AI_TASK | (nessuno) | `workerType = 'AI_TASK'` |
| CONTRACT | (nessuno) | `workerType = 'CONTRACT'` |

Se il planner non assegna un profilo, l'orchestrator risolve il default:
**BE → be-java**, **FE → fe-react**.

---

## 4. REST API

Base URL: `http://localhost:8080`

### Endpoint

| Method | Path | Body | Response | Descrizione |
|--------|------|------|----------|-------------|
| `POST` | `/api/v1/plans` | `{"spec": "..."}` | 202 + PlanResponse | Crea piano, chiama planner, dispatcha prima onda |
| `GET` | `/api/v1/plans/{planId}` | — | 200 + PlanResponse | Stato corrente del piano con tutti gli item |
| `POST` | `/api/v1/plans/{planId}/items/{itemId}/retry` | — | 202 | Ritenta item fallito (FAILED → WAITING → DISPATCHED) |
| `GET` | `/api/v1/plans/{planId}/items/{itemId}/attempts` | — | 200 + List | Storico dispatch attempt per item |
| `GET` | `/api/v1/plans/{planId}/snapshots` | — | 200 + List | Lista snapshot (Memento) del piano |
| `POST` | `/api/v1/plans/{planId}/restore/{snapshotId}` | — | 200 | Ripristina piano da snapshot |
| `GET` | `/api/v1/plans/{planId}/quality-gate` | — | 200 | Report quality gate per piano completato |

### Esempio: creare un piano

```bash
curl -X POST http://localhost:8080/api/v1/plans \
  -H "Content-Type: application/json" \
  -d '{
    "spec": "Add a /health endpoint to the user service that returns service status and dependency checks"
  }'
```

### Esempio: retry di un item fallito

```bash
curl -X POST http://localhost:8080/api/v1/plans/abc123/items/def456/retry
```

### Esempio: storico dispatch attempt

```bash
curl http://localhost:8080/api/v1/plans/abc123/items/def456/attempts
```

Risposta:
```json
[
  {
    "id": "att-001",
    "attemptNumber": 1,
    "dispatchedAt": "2026-02-27T10:01:00Z",
    "completedAt": "2026-02-27T10:03:22Z",
    "success": false,
    "failureReason": "Compilation error in UserController.java",
    "durationMs": 142000
  },
  {
    "id": "att-002",
    "attemptNumber": 2,
    "dispatchedAt": "2026-02-27T10:05:00Z",
    "completedAt": "2026-02-27T10:06:45Z",
    "success": true,
    "durationMs": 105000
  }
]
```

---

## 5. Agent Manifest

I manifest YAML definiscono ogni worker. Si trovano in `agents/manifests/*.agent.yml`.

### Struttura completa

```yaml
apiVersion: agent-framework/v1
kind: AgentManifest

metadata:
  name: be-java                          # Identificativo univoco (kebab-case)
  displayName: "Backend Java Worker"     # Nome leggibile
  description: "Spring Boot backend"     # Descrizione

spec:
  workerType: BE                         # BE | FE | AI_TASK | CONTRACT | REVIEW
  workerProfile: be-java                 # Profilo concreto (stack-specific)
  topic: agent-tasks                     # Topic di input (quasi sempre agent-tasks)
  subscription: be-java-worker-sub       # Subscription univoca per routing

  model:
    name: claude-sonnet-4-6              # Modello Claude da usare
    maxTokens: 16384                     # Token massimi nella risposta
    temperature: 0.2                     # Creativita' (0.0-1.0)

  prompts:
    systemPromptFile: skills/be.agent.md # Prompt di sistema (personalita' worker)
    skills:                              # Skill aggiuntive iniettate nel contesto
      - skills/springboot-workflow-skills/
      - skills/crosscutting/
    instructions: |                      # Istruzioni task-specific
      Follow contract-first pattern.
      Use constructor injection.
    resultSchema: |                      # Schema JSON atteso nel risultato
      { "files_created": [], "summary": "" }

  tools:
    dependencies:                        # Dipendenze Maven per tool MCP
      - io.github.massimilianopili:mcp-filesystem-tools
      - io.github.massimilianopili:mcp-devops-tools
    allowlist:                           # Tool consentiti (whitelist)
      - Read
      - Write
      - Edit
      - Glob
      - Grep
      - Bash
    mcpServers:                          # Server MCP da attivare
      - git
      - repo-fs
      - openapi
      - test

  ownership:
    ownsPaths:                           # Path dove il worker puo' scrivere
      - backend/
      - templates/be/
    readOnlyPaths:                       # Path in sola lettura
      - contracts/

  concurrency:
    maxConcurrentCalls: 3                # Esecuzioni parallele max
```

### Come aggiungere un nuovo worker

1. Creare `agents/manifests/be-python.agent.yml` con la struttura sopra
2. Rigenerare i moduli:
   ```bash
   mvn com.agentframework:agent-compiler-maven-plugin:generate-workers
   mvn com.agentframework:agent-compiler-maven-plugin:generate-registry
   ```
3. Il nuovo modulo appare in `execution-plane/workers/be-python-worker/`
4. Aggiungere il modulo al `pom.xml` root nella sezione `<modules>`
5. Build: `mvn clean install`

---

## 6. Worker SDK

Il Worker SDK (`execution-plane/worker-sdk`) fornisce la classe base e l'infrastruttura
per tutti i worker.

### AbstractWorker — metodi da implementare

| Metodo | Tipo | Descrizione |
|--------|------|-------------|
| `workerType()` | abstract | Ritorna `"BE"`, `"FE"`, `"AI_TASK"`, etc. |
| `systemPromptFile()` | abstract | Path al prompt di sistema, es. `"skills/be.agent.md"` |
| `execute(AgentContext, ChatClient)` | abstract | Logica di esecuzione, ritorna JSON risultato |
| `toolAllowlist()` | override opzionale | Default: `ToolAllowlist.ALL`, override per whitelist |
| `skillPaths()` | override opzionale | Skill aggiuntive da comporre nel system prompt |

### Template method: `process(AgentTask)`

L'entry point chiamato dal consumer. Sequenza:

1. Start timer
2. Reset ThreadLocal (tool names, token usage)
3. Build context (system prompt + skill documents + dependency results)
4. Esegui interceptor `beforeExecute()` (in ordine)
5. Crea ChatClient con policy di tool filtering
6. Chiama `execute(context, chatClient)` → risultato JSON
7. Esegui interceptor `afterExecute()` (in ordine)
8. Raccogli tool usati e token consumati
9. Calcola hash SHA-256 di prompt e skill
10. Assembla `Provenance` (workerType, profile, model, tools, tokens, hashes, traceId)
11. Pubblica `AgentResult` (success o failure)
12. Cleanup ThreadLocal

### ToolAllowlist (sealed interface)

```java
// Tutti i tool disponibili
ToolAllowlist.ALL

// Solo tool specifici
new ToolAllowlist.Explicit(List.of("Read", "Write", "Glob"))
```

### WorkerInterceptor

Pipeline di cross-cutting concerns con priorita':

```java
public interface WorkerInterceptor extends Ordered {
    default AgentContext beforeExecute(AgentContext ctx, AgentTask task);
    default String afterExecute(AgentContext ctx, String result, AgentTask task);
    default void onError(AgentContext ctx, Exception e, AgentTask task);
}
```

### Configurazione

```yaml
agent:
  worker:
    task-topic: agent-tasks          # Topic da cui consumare
    task-subscription: be-java-worker-sub  # Subscription
    results-topic: agent-results     # Topic per pubblicare risultati
```

---

## 7. Messaging

### SPI (messaging-api)

Interfacce transport-agnostic:

| Interfaccia | Metodo | Scopo |
|-------------|--------|-------|
| `MessageSender` | `send(MessageEnvelope)` | Invia messaggio a destinazione (topic/stream) |
| `MessageListenerContainer` | `subscribe(dest, group, handler)` | Sottoscrive a topic/stream con consumer group |
| `MessageHandler` | `handle(body, ack)` | Callback per messaggi ricevuti |
| `MessageEnvelope` | record(messageId, destination, body, properties) | Carrier transport-agnostic |
| `MessageAcknowledgment` | `complete()` / `reject(reason)` | Conferma o rifiuta messaggio |

### Provider

| Provider | Attivazione | Dipendenza | Uso tipico |
|----------|------------|-----------|-----------|
| **JMS/Artemis** | `messaging.provider=jms` (o assente) | `messaging-jms` | Sviluppo locale |
| **Redis Streams** | `messaging.provider=redis` | `messaging-redis` | Staging leggero |
| **Azure Service Bus** | `messaging.provider=servicebus` | `messaging-servicebus` | Produzione |

### Cambiare provider

In `application.yml`:

```yaml
# JMS (default — nessuna configurazione aggiuntiva necessaria)
messaging:
  provider: jms

# Redis Streams
messaging:
  provider: redis
  redis:
    host: localhost
    port: 6379
    database: 3

# Azure Service Bus
messaging:
  provider: servicebus
azure:
  servicebus:
    connection-string: "Endpoint=sb://...;SharedAccessKey=..."
```

### Dead-letter handling

| Provider | Meccanismo DLQ |
|----------|---------------|
| JMS | Rollback della sessione transazionale → redeliver (max 5 volte) |
| Redis | `XACK` + `XADD` a stream `{key}:dlq` |
| Service Bus | DLQ nativa per subscription (max delivery count configurabile) |

---

## 8. Policy e Sicurezza

Il framework applica policy a piu' livelli per limitare cosa i worker possono fare.

### Tool Allowlist (compile-time)

Ogni manifest definisce i tool consentiti al worker:
```yaml
tools:
  allowlist: [Read, Write, Edit, Bash]   # domain workers: Glob/Grep delegated to CONTEXT_MANAGER
```

`WorkerChatClientFactory` filtra i tool prima di creare il ChatClient:
- `ToolAllowlist.ALL` → tutti i tool disponibili
- `ToolAllowlist.Explicit` → solo i tool nella lista

### Path Ownership (runtime)

`config/repo-layout.yml` definisce chi possiede quali path:

```yaml
paths:
  backend/:
    owner: be-workers
    allowed: [be-java-worker, be-go-worker, review-worker(RO)]
  frontend/:
    owner: fe-workers
    allowed: [fe-react-worker, review-worker(RO)]
  infra/:
    owner: humans-only
    allowed: []
```

`PathOwnershipEnforcer` verifica a runtime che ogni operazione di scrittura sia
all'interno dei path consentiti.

### MCP Allowlist (runtime)

File in `mcp/policies/allowlists/` definiscono per ogni MCP server quali operazioni
sono consentite a quali worker:

```yaml
# mcp/policies/allowlists/git.yml
operations:
  git_push:
    allowed: [be-worker, fe-worker, contract-worker]
    constraints:
      noForce: true
      protectedBranches: [main, develop]
```

### Sandbox Limits

`mcp/policies/sandbox/limits.yml`:

| Limite | Valore default |
|--------|---------------|
| maxFileSize | 100 KB |
| maxRecursionDepth | 10 |
| maxToolCallsPerTask | 50 |
| timeoutSeconds | 300 (5 min) |
| maxFilesPerGlob | 500 |
| maxGrepMatches | 200 |
| maxOutputBytes | 512 KB |
| maxConcurrentTools | 3 |

### Redaction

`mcp/policies/redaction/redaction-rules.yml` definisce pattern regex per mascherare
automaticamente segreti e PII dall'output dei tool:

- **Segreti**: API key, bearer token, connection string, password in URL, chiavi private
- **PII**: codice fiscale, IBAN, carte di credito, email, telefono, SSN

Applicato su: output tool, log, payload eventi.

### Quality Gates

`config/quality-gates.yml` definisce le soglie per il merge:

| Gate | Soglia |
|------|--------|
| Test coverage | >= 80% |
| Vulnerabilita' critiche/alte | 0 |
| Breaking changes (oasdiff) | 0 |
| Spectral lint errors | 0 |
| Build pass | Obbligatorio |
| Lint errors | 0 |

---

## 9. Code Generation

### Plugin Maven: `agent-compiler-maven-plugin`

| Goal | Fase Maven | Input | Output |
|------|-----------|-------|--------|
| `generate-workers` | GENERATE_SOURCES | `agents/manifests/*.agent.yml` | Moduli completi in `execution-plane/workers/` |
| `generate-registry` | GENERATE_RESOURCES | `agents/manifests/*.agent.yml` | `config/worker-profiles.yml`, `config/agent-registry.yml`, `config/generated/hooks-config.json` |
| `validate-manifests` | VALIDATE | `agents/manifests/*.agent.yml` | Errore se manifest invalidi |

### Output per ogni worker generato

```
execution-plane/workers/be-java-worker/
├── pom.xml                          # Dipendenze (worker-sdk, MCP tools, Spring AI)
├── Dockerfile                       # Immagine container
└── src/main/
    ├── java/.../BeJavaWorker.java           # Worker con execute(), allowlist, skills
    ├── java/.../BeJavaWorkerApplication.java # Spring Boot entry point
    └── resources/application.yml            # Config: topic, subscription, model
```

### Rigenerare

```bash
# Rigenera worker modules
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-workers

# Rigenera config files
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-registry

# Valida manifest senza generare
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:validate-manifests
```

---

## 10. Deployment

### Sviluppo locale

```bash
# Dipendenze
docker compose -f docker/docker-compose.dev.yml up -d

# Orchestrator
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run -pl control-plane/orchestrator

# Worker singolo (opzionale — utile per debug)
mvn spring-boot:run -pl execution-plane/workers/be-java-worker
```

### Variabili ambiente

| Variabile | Obbligatoria | Default | Descrizione |
|-----------|-------------|---------|-------------|
| `ANTHROPIC_API_KEY` | Si | — | API key Claude per planner e worker |
| `DB_HOST` | No | localhost | Host PostgreSQL |
| `DB_USER` | No | agentframework | Utente database |
| `DB_PASSWORD` | Si (prod) | (vuoto) | Password database |
| `ARTEMIS_HOST` | No | localhost | Host broker JMS |
| `ARTEMIS_USER` | No | admin | Utente Artemis |
| `ARTEMIS_PASSWORD` | No | admin | Password Artemis |
| `messaging.provider` | No | jms | Provider: `jms`, `redis`, `servicebus` |
| `azure.servicebus.connection-string` | Se servicebus | — | Connection string Azure |

### Azure (produzione)

L'infrastruttura Azure e' definita in `infra/azure/bicep/`:

| Risorsa | Modulo Bicep |
|---------|-------------|
| Container Apps Environment | `modules/container-apps-env.bicep` |
| Orchestrator (Container App) | `modules/aca-app.bicep` |
| Workers (Container Apps Jobs) | `modules/aca-jobs.bicep` |
| Azure Service Bus | `modules/servicebus.bicep` |
| PostgreSQL Flexible Server | `modules/postgres.bicep` |
| Key Vault | `modules/keyvault.bicep` |
| Application Insights | `modules/appinsights.bicep` |
| Container Registry | `modules/acr.bicep` |

Deploy:
```bash
cd infra/azure
./pipelines/deploy.sh --env develop
```

Parametri per ambiente in `infra/azure/bicep/env/{develop,test,collaudo,prod}.parameters.json`.

---

## 11. Troubleshooting

### Il piano rimane in stato RUNNING

**Causa**: item bloccati da dipendenze non soddisfatte.

```bash
# Verifica stato degli item
curl http://localhost:8080/api/v1/plans/{planId} | jq '.items[] | {taskKey, status, dependsOn}'
```

Se un item e' FAILED, i suoi dipendenti restano WAITING. Ritentare l'item fallito:
```bash
curl -X POST http://localhost:8080/api/v1/plans/{planId}/items/{itemId}/retry
```

### Il worker non riceve task

**Causa**: mismatch tra subscription name e SQL filter.

1. Verificare `application.yml` del worker:
   ```yaml
   agent.worker.task-subscription: be-java-worker-sub
   ```
2. Verificare che il messaggio abbia la property `workerProfile`:
   ```bash
   # Log orchestrator
   docker logs orchestrator | grep "Dispatched task"
   ```
3. Verificare che la subscription esista nel broker con il filtro corretto

### Profile validation fallisce allo startup

**Causa**: `worker-profiles.yml` inconsistente con i manifest.

```bash
# Verificare errore nei log
docker logs orchestrator | grep "WorkerProfileRegistry validation failed"

# Rigenerare config
mvn agent-compiler:generate-registry
```

### Worker fallisce con "Unknown worker profile"

**Causa**: il planner ha assegnato un profilo non registrato.

1. Verificare i profili disponibili in `config/worker-profiles.yml`
2. Se il profilo e' valido, verificare che l'orchestrator abbia la config aggiornata
3. Opzione: ritentare con il profilo di default (l'orchestrator lo risolvera' automaticamente)

### 502 dal broker / messaggi nella DLQ

**Causa**: il worker ha crashato durante l'esecuzione.

```bash
# Verifica dispatch attempts
curl http://localhost:8080/api/v1/plans/{planId}/items/{itemId}/attempts

# Log del worker specifico
docker logs be-java-worker --tail 50
```

### Build fallisce dopo modifica manifest

```bash
# Rigenerare tutto
mvn com.agentframework:agent-compiler-maven-plugin:generate-workers
mvn com.agentframework:agent-compiler-maven-plugin:generate-registry
mvn clean install
```

Se il nuovo worker non e' nel build, aggiungere il modulo al `pom.xml` root:
```xml
<module>execution-plane/workers/be-python-worker</module>
```
