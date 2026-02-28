# Setup Guide

Guida passo-passo per installare, configurare, e avviare il framework in locale.
Per la documentazione completa, vedi il [Manuale Utente](docs/manual/user-guide.md).

## Prerequisiti

| Requisito | Versione minima | Verifica |
|-----------|----------------|----------|
| Java | 17 (Eclipse Temurin consigliato) | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker | 24+ con Compose V2 | `docker compose version` |
| API Key | Anthropic (Claude) | `echo $ANTHROPIC_API_KEY` |

Ottenere una API key: [console.anthropic.com](https://console.anthropic.com/)

## 1. Clone e Build

```bash
git clone <repository-url>
cd agent-framework
```

Build completo (16 moduli Maven):

```bash
mvn clean install
```

Output atteso:

```
[INFO] Reactor Summary:
[INFO]   messaging-api ................................. SUCCESS
[INFO]   messaging-jms ................................. SUCCESS
[INFO]   messaging-redis ............................... SUCCESS
[INFO]   messaging-servicebus .......................... SUCCESS
[INFO]   orchestrator .................................. SUCCESS
[INFO]   agent-compiler-maven-plugin ................... SUCCESS
[INFO]   worker-sdk .................................... SUCCESS
[INFO]   be-java-worker ................................ SUCCESS
[INFO]   ...
[INFO] BUILD SUCCESS
[INFO] 16 modules
```

Il primo build scarica ~500 MB di dipendenze Maven. I build successivi sono incrementali.

## 2. Ambiente Locale (Docker)

Avviare PostgreSQL e Apache Artemis (broker JMS):

```bash
docker compose -f docker/docker-compose.dev.yml up -d
```

Servizi avviati:

| Servizio | Porta | Credenziali | Scopo |
|----------|-------|-------------|-------|
| PostgreSQL 16 | 5432 | `agentframework` / `agentframework` | Database piani e stato |
| Artemis | 61616 | `admin` / `admin` | Broker messaggi JMS |
| Artemis Console | 8161 | `admin` / `admin` | UI web gestione code |

Verifica:

```bash
docker compose -f docker/docker-compose.dev.yml ps
```

Entrambi i container devono risultare `running (healthy)`.

**Nota**: Flyway crea automaticamente le tabelle del database al primo avvio
dell'orchestrator. Non serve inizializzazione manuale.

## 3. Variabili d'Ambiente

| Variabile | Default | Obbligatoria | Descrizione |
|-----------|---------|:------------:|-------------|
| `ANTHROPIC_API_KEY` | — | Si | API key Anthropic per il modello Claude |
| `DB_HOST` | `localhost` | No | Host PostgreSQL |
| `DB_USER` | `agentframework` | No | Utente database |
| `DB_PASSWORD` | (vuoto) | No | Password database |
| `ARTEMIS_HOST` | `localhost` | No | Host broker Artemis |
| `ARTEMIS_USER` | `admin` | No | Utente broker |
| `ARTEMIS_PASSWORD` | `admin` | No | Password broker |

Configurazione minima per ambiente locale:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

Tutti gli altri valori hanno default adeguati per il docker-compose di sviluppo.
In produzione, impostare sempre `DB_PASSWORD` e le credenziali del broker.

## 4. Avvio Orchestrator

```bash
mvn spring-boot:run -pl control-plane/orchestrator -Dspring-boot.run.profiles=dev
```

Verifica health check:

```bash
curl http://localhost:8080/management/health
```

Output atteso:

```json
{"status":"UP"}
```

Nel log di startup, verificare:

```
WorkerProfileRegistry validated: 5 profiles, 2 defaults
Started AgentFrameworkOrchestratorApplication in X.XXs
```

L'orchestrator ascolta sulla porta **8080** e accetta richieste REST.

## 5. Avvio Worker (opzionale)

I worker sono necessari solo per **eseguire** i task. L'orchestrator puo' creare
piani anche senza worker attivi (i task restano in stato DISPATCHED).

Per lo sviluppo, avviare un solo worker in un terminale separato:

```bash
mvn spring-boot:run -pl execution-plane/workers/be-java-worker
```

Ogni worker richiede la propria `ANTHROPIC_API_KEY` nell'ambiente.

Worker disponibili:

| Worker | Comando |
|--------|---------|
| Backend Java (default) | `mvn spring-boot:run -pl execution-plane/workers/be-java-worker` |
| Backend Go | `mvn spring-boot:run -pl execution-plane/workers/be-go-worker` |
| Backend Rust | `mvn spring-boot:run -pl execution-plane/workers/be-rust-worker` |
| Backend Node.js | `mvn spring-boot:run -pl execution-plane/workers/be-node-worker` |
| Frontend React | `mvn spring-boot:run -pl execution-plane/workers/fe-react-worker` |
| AI Task | `mvn spring-boot:run -pl execution-plane/workers/ai-task-worker` |
| Contract | `mvn spring-boot:run -pl execution-plane/workers/contract-worker` |
| Review | `mvn spring-boot:run -pl execution-plane/workers/review-worker` |

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

### Verificare stato

```bash
curl -s http://localhost:8080/api/v1/plans/{planId} | python3 -m json.tool
```

Flusso atteso:

```
PENDING  →  Planner (Claude) decompone la spec in task
RUNNING  →  PlanItems creati, orchestrator dispatcha verso i worker
         →  Worker eseguono e ritornano AgentResult
COMPLETED/FAILED  →  Tutti gli items terminati
```

### Endpoint utili

| Azione | Comando |
|--------|---------|
| Quality gate report | `curl http://localhost:8080/api/v1/plans/{planId}/quality-gate` |
| Retry task fallito | `curl -X POST http://localhost:8080/api/v1/plans/{planId}/items/{itemId}/retry` |
| Storico dispatch | `curl http://localhost:8080/api/v1/plans/{planId}/items/{itemId}/attempts` |
| Snapshot piano | `curl http://localhost:8080/api/v1/plans/{planId}/snapshots` |
| Ripristina snapshot | `curl -X POST http://localhost:8080/api/v1/plans/{planId}/restore/{snapshotId}` |

## 7. Provider Messaging Alternativi

JMS/Artemis e' il provider di default per sviluppo locale. Per ambienti diversi,
switchare tramite property in `application.yml`:

### Redis Streams

```yaml
messaging:
  provider: redis
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    database: 3
    poll-timeout-ms: 2000
    batch-size: 10
```

### Azure Service Bus

```yaml
messaging:
  provider: servicebus

azure:
  servicebus:
    connection-string: "Endpoint=sb://...;SharedAccessKeyName=...;SharedAccessKey=..."
    max-concurrent-calls: 1
```

Per dettagli: [Messaging README](messaging/README.md).

## 8. Deploy Azure

L'infrastruttura e' definita in Azure Bicep con 4 ambienti parametrizzati.

### Risorse create

- **Container Apps Environment** — orchestrator + worker containers
- **Service Bus** — topic/subscription con SQL filter per routing
- **PostgreSQL Flexible Server** — storage piani e stato
- **Key Vault** — segreti (API key, connection string)
- **Application Insights** — monitoring e tracing

### Deploy

```bash
# Login Azure
az login

# Deploy infrastruttura (esempio: develop)
az deployment group create \
  --resource-group rg-agent-framework-dev \
  --template-file infra/azure/bicep/main.bicep \
  --parameters infra/azure/bicep/env/develop.parameters.json
```

Ambienti disponibili: `develop`, `test`, `collaudo`, `prod`.
Parametri per ambiente in `infra/azure/bicep/env/{ambiente}.parameters.json`.

Per dettagli sugli ambienti: [Configuration README](config/README.md).

### Docker Images

Ogni worker ha un Dockerfile generato automaticamente dal compiler plugin:

```bash
# Build immagine singolo worker
cd execution-plane/workers/be-java-worker
docker build -t agent-framework/be-java-worker:latest .
```

## 9. IDE Setup

### IntelliJ IDEA

1. **File → Open** → selezionare la directory `agent-framework`
2. IntelliJ rileva automaticamente il progetto Maven multi-modulo
3. Impostare **Project SDK** su Java 17 (Temurin)
4. **Build → Build Project** per validare la configurazione

### VS Code

1. Installare **Java Extension Pack** + **Spring Boot Extension Pack**
2. Aprire la directory `agent-framework`
3. Il Language Server Java indicizza automaticamente i 16 moduli

### Rigenerazione worker

Dopo aver modificato un manifest in `agents/manifests/`:

```bash
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-workers
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-registry
mvn clean install
```

I file in `execution-plane/workers/` sono **generati** — non modificarli a mano.

## 10. Troubleshooting

| Problema | Causa probabile | Soluzione |
|----------|----------------|-----------|
| `BUILD FAILURE` al primo build | Java non e' 17 | `java -version` — installare Temurin 17 |
| `Connection refused` su porta 5432 | PostgreSQL non avviato | `docker compose -f docker/docker-compose.dev.yml up -d postgres` |
| `Connection refused` su porta 6379 | Redis non avviato | `docker compose -f docker/docker-compose.dev.yml up -d redis` |
| `WorkerProfileRegistry validation failed` | Config profili inconsistente | Controllare `config/worker-profiles.yml` |
| Plan rimane in `PENDING` | API key mancante o invalida | Verificare `echo $ANTHROPIC_API_KEY` |
| Worker non riceve task | Topic/subscription mismatch | Verificare `agent.worker.task-topic` e `task-subscription` nel worker `application.yml` |
| `409 Conflict` su retry | Item non in stato FAILED | Solo items FAILED possono essere riprovati |
| `Provenance.model` e' null | Limitazione Spring AI | Noto — il modello non viene esposto uniformemente da Spring AI |

### Verificare lo stato dei container

```bash
docker compose -f docker/docker-compose.dev.yml ps
docker compose -f docker/docker-compose.dev.yml logs postgres --tail 20
docker compose -f docker/docker-compose.dev.yml logs redis --tail 20
```

### Reset completo ambiente locale

```bash
docker compose -f docker/docker-compose.dev.yml down -v   # Cancella volumi (DB, broker)
docker compose -f docker/docker-compose.dev.yml up -d      # Ricrea da zero
mvn clean install                                           # Rebuild
```
