# Contracts

Contratti machine-readable che definiscono le interfacce tra i componenti del framework:
schema JSON per i messaggi, OpenAPI per la REST API, AsyncAPI per il messaging asincrono,
e topologia degli eventi.

## Struttura

```
contracts/
├── agent-schemas/       # JSON Schema per messaggi e entita'
├── openapi/             # Specifica REST API (OpenAPI 3.1)
│   ├── v1.yaml          # Endpoint definitions
│   ├── v1.components.yaml # Shared schemas
│   └── rules/           # Spectral lint rules
├── asyncapi/            # Specifica messaging asincrono
│   └── rules/           # Async validation rules
└── events/              # Topologia eventi e envelope
    ├── topics.yml        # Mapping topic → subscription
    ├── envelope.schema.json
    └── event-types.yml
```

## Agent Schemas (`agent-schemas/`)

| Schema | Descrizione | Campi chiave |
|--------|-------------|-------------|
| `AgentTask.schema.json` | Messaggio orchestrator → worker | planId, itemId, taskKey, workerType, workerProfile, contextJson, traceId |
| `AgentResult.schema.json` | Messaggio worker → orchestrator | planId, itemId, success, resultJson, durationMs, provenance (required) |
| `AgentManifest.schema.json` | Definizione manifest YAML | metadata, spec (workerType, model, tools, ownership) |
| `AgentContext.schema.json` | Contesto passato al worker | taskKey, title, description, spec, systemPrompt, dependencyResults |
| `Plan.schema.json` | Piano completo | id, spec, status, items |
| `PlanItem.schema.json` | Singolo task nel piano | taskKey, workerType, workerProfile, dependsOn, status |
| `PlanRequest.schema.json` | Input per creare piano | spec, constraints, tags |
| `QualityGateReport.schema.json` | Report quality gate | gates, score, pass |
| `ExecutionTrace.schema.json` | Traccia esecuzione distribuita | traceId, spans |

### Provenance in AgentResult

Il campo `provenance` e' **required** e contiene metadati strutturati dell'esecuzione:

```json
{
  "provenance": {
    "workerType": "BE",
    "workerProfile": "be-java",
    "attemptNumber": 1,
    "traceId": "uuid",
    "model": null,
    "toolsUsed": ["Read", "Write", "Glob"],
    "promptHashValue": "sha256:...",
    "skillsHashValue": "sha256:...",
    "tokenUsage": { "input": 4500, "output": 2100 }
  }
}
```

I campi flat a root level (`workerType`, `modelId`, `promptHash`) sono marcati
`"deprecated": true` — usare sempre `provenance.*`.

## OpenAPI (`openapi/`)

### v1.yaml — Endpoint

| Method | Path | Descrizione |
|--------|------|-------------|
| POST | `/api/v1/plans` | Crea piano da specifica |
| GET | `/api/v1/plans/{planId}` | Stato piano con items |
| POST | `/api/v1/plans/{planId}/items/{itemId}/retry` | Ritenta item fallito |
| GET | `/api/v1/plans/{planId}/items/{itemId}/attempts` | Storico dispatch attempt |
| GET | `/api/v1/plans/{planId}/snapshots` | Lista snapshot |
| POST | `/api/v1/plans/{planId}/restore/{snapshotId}` | Ripristina da snapshot |
| GET | `/api/v1/plans/{planId}/quality-gate` | Report quality gate |

### v1.components.yaml — Shared Schemas

Definisce i DTO condivisi: `PlanResponse`, `PlanItemResponse`, `DispatchAttemptResponse`,
`PlanSnapshotResponse`, `QualityGateReportResponse`, `PlanRequest`, `ErrorResponse`.

### Spectral Rules (`rules/`)

Regole di linting per la specifica OpenAPI. Usate dal contract-worker per validare
modifiche all'API.

## Event Topology (`events/`)

### topics.yml

Definisce la topologia dei topic Service Bus:

| Topic | Subscription | SQL Filter | Scopo |
|-------|-------------|-----------|-------|
| `agent-tasks` | `be-java-worker-sub` | `workerProfile = 'be-java'` | Task backend Java |
| `agent-tasks` | `be-go-worker-sub` | `workerProfile = 'be-go'` | Task backend Go |
| `agent-tasks` | `be-rust-worker-sub` | `workerProfile = 'be-rust'` | Task backend Rust |
| `agent-tasks` | `be-node-worker-sub` | `workerProfile = 'be-node'` | Task backend Node.js |
| `agent-tasks` | `fe-react-worker-sub` | `workerProfile = 'fe-react'` | Task frontend React |
| `agent-tasks` | `ai-task-worker-sub` | `workerType = 'AI_TASK'` | Task AI generici |
| `agent-tasks` | `contract-worker-sub` | `workerType = 'CONTRACT'` | Task contratti |
| `agent-reviews` | `review-worker-sub` | `workerType = 'REVIEW'` | Code review |
| `agent-results` | `orchestrator` | (nessuno) | Risultati verso orchestrator |

### envelope.schema.json

Schema per l'envelope dei messaggi:
```json
{
  "messageId": "uuid",
  "source": "orchestrator|worker-name",
  "type": "agent-task|agent-result",
  "timestamp": "ISO-8601",
  "correlationId": "traceId",
  "payload": { ... }
}
```

## Relazione Schema → Java → Runtime

```
contracts/agent-schemas/AgentTask.schema.json
    ↕ (deve corrispondere)
orchestrator/.../dto/AgentTask.java
worker-sdk/.../dto/AgentTask.java
    ↕ (serializzato/deserializzato a runtime)
MessageEnvelope.body (JSON string)
```

Le modifiche agli schema devono essere riflesse nei record Java e vice versa.
`oasdiff` verifica le breaking changes nella REST API.
`validate-manifests` verifica la coerenza dei manifest.
