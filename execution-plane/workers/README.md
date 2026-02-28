# Workers — Generated Worker Modules

Moduli Maven worker del framework. La maggior parte sono generati automaticamente dal
`agent-compiler-maven-plugin` a partire dai manifest in `agents/manifests/*.agent.yml`.
I worker di sistema (`COMPENSATOR_MANAGER`, `TASK_MANAGER`) sono scritti a mano e non generati.
`SUB_PLAN` non è un worker — è gestito inline dall'orchestrator.

Ogni modulo worker è un'applicazione Spring Boot autonoma che estende `AbstractWorker`.

> **NON MODIFICARE** questi file a mano. Sono rigenerabili e le modifiche verranno
> sovrascritte. Per cambiare comportamento, modificare il manifest e rigenerare.

## Worker List

| Modulo | Type | Profile | Topic | Subscription | Owns Paths | Generato |
|--------|------|---------|-------|-------------|-----------|---------|
| `context-manager-worker` | CONTEXT_MANAGER | — | agent-tasks | context-manager-worker-sub | `.` (read-only) | ✓ |
| `schema-manager-worker` | SCHEMA_MANAGER | — | agent-tasks | schema-manager-worker-sub | `.` (read-only) | ✓ |
| `hook-manager-worker` | HOOK_MANAGER | — | agent-tasks | hook-manager-worker-sub | `.` (read-only) | ✓ |
| `task-manager-worker` | TASK_MANAGER | — | agent-tasks | task-manager-worker-sub | `issues/` | ✗ |
| `be-java-worker` | BE | be-java | agent-tasks | be-java-worker-sub | `backend/` | ✓ |
| `be-go-worker` | BE | be-go | agent-tasks | be-go-worker-sub | `backend/` | ✓ |
| `be-rust-worker` | BE | be-rust | agent-tasks | be-rust-worker-sub | `backend/` | ✓ |
| `be-node-worker` | BE | be-node | agent-tasks | be-node-worker-sub | `backend/` | ✓ |
| `fe-react-worker` | FE | fe-react | agent-tasks | fe-react-worker-sub | `frontend/` | ✓ |
| `ai-task-worker` | AI_TASK | — | agent-tasks | ai-task-worker-sub | `eval/` | ✓ |
| `contract-worker` | CONTRACT | — | agent-tasks | contract-worker-sub | `contracts/` | ✓ |
| `review-worker` | REVIEW | — | agent-reviews | review-worker-sub | `docs/` | ✓ |
| `audit-manager-worker` | AUDIT_MANAGER | — | agent-tasks | audit-manager-worker-sub | `audit/` | ✓ |
| `event-manager-worker` | EVENT_MANAGER | — | agent-tasks | event-manager-worker-sub | `.` (read-only) | ✓ |
| `compensator-manager-worker` | COMPENSATOR_MANAGER | — | agent-tasks | compensator-manager-worker-sub | `.` | ✗ |
| _(inline orchestrator)_ | SUB_PLAN | — | — | — | — | — |

> **Colonna Generato**: ✓ = prodotto da `agent-compiler-maven-plugin` (non modificare a mano);
> ✗ = scritto manualmente (modificabile); — = non è un worker Spring Boot.

## Struttura generata

Ogni modulo segue la stessa struttura:

```
be-java-worker/
├── pom.xml                          # Dipendenze: worker-sdk, MCP tools, Spring AI
├── Dockerfile                       # FROM eclipse-temurin:17-jre + fat JAR
└── src/main/
    ├── java/com/agentframework/workers/generated/bejavaworker/
    │   ├── BeJavaWorker.java        # Estende AbstractWorker (@Component @Generated)
    │   └── BeJavaWorkerApplication.java  # @SpringBootApplication main class
    └── resources/
        └── application.yml          # Config: topic, subscription, model, policy
```

### BeJavaWorker.java (esempio)

Il worker generato implementa i metodi abstract di `AbstractWorker`:

- `workerType()` → `"BE"`
- `systemPromptFile()` → `"skills/be-worker.agent.md"`
- `toolAllowlist()` → `ToolAllowlist.Explicit(["Read", "Write", "Edit", "Bash"])`
- `execute(context, chatClient)` → chiama Claude con `buildStandardUserPrompt()` + istruzioni

### application.yml (esempio)

```yaml
spring:
  application:
    name: be-java-worker

agent:
  worker:
    task-topic: agent-tasks
    task-subscription: be-java-worker-sub

spring.ai.anthropic.chat.options:
  model: claude-sonnet-4-6
  max-tokens: 16384
  temperature: 0.2

policy:
  enabled: true
```

## Come rigenerare

```bash
# Rigenerare tutti i moduli worker
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-workers

# Rigenerare anche la registry
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-registry

# Build completo
mvn clean install
```

## Esecuzione locale

```bash
# Singolo worker
mvn spring-boot:run -pl execution-plane/workers/be-java-worker

# Con profilo locale
mvn spring-boot:run -pl execution-plane/workers/be-java-worker \
  -Dspring-boot.run.profiles=dev
```

Prerequisiti:
- Orchestrator in esecuzione (pubblica task sul topic)
- Broker messaggi attivo (Redis: `docker compose -f docker/docker-compose.dev.yml up -d redis`)
- `ANTHROPIC_API_KEY` nell'ambiente

## Personalizzazione

Per override locale **senza modificare file generati**:

1. Creare `src/main/resources/application-local.yml` nella directory del worker
2. Lanciare con `-Dspring-boot.run.profiles=local`

Esempio override:
```yaml
spring.ai.anthropic.chat.options:
  model: claude-haiku-4-5-20251001    # Modello piu' veloce per test
  max-tokens: 4096

agent.worker.policy:
  enabled: false                       # Disabilita policy per debug
```

## Aggiungere un nuovo worker

1. Creare manifest: `agents/manifests/be-python.agent.yml`
2. Rigenerare: `mvn agent-compiler:generate-workers agent-compiler:generate-registry`
3. Aggiungere al `pom.xml` root: `<module>execution-plane/workers/be-python-worker</module>`
4. Build: `mvn clean install`

Vedi [Agent Compiler Plugin](../agent-compiler-maven-plugin/README.md) per il formato manifest completo.

## Naming Conventions

| Manifest `name` | Modulo | Classe | Package |
|-----------------|--------|--------|---------|
| `be-java` | `be-java-worker/` | `BeJavaWorker` | `bejavaworker` |
| `fe-react` | `fe-react-worker/` | `FeReactWorker` | `fereactworker` |
| `ai-task` | `ai-task-worker/` | `AiTaskWorker` | `aitaskworker` |

Conversione: kebab-case → PascalCase (classe), lowercase senza trattini (package).
