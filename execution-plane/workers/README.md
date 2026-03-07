# Workers — Generated Worker Modules

Moduli Maven worker del framework. La maggior parte sono generati automaticamente dal
`agent-compiler-maven-plugin` a partire dai manifest in `agents/manifests/*.agent.yml`.
I worker di sistema (`COMPENSATOR_MANAGER`, `TASK_MANAGER`) sono scritti a mano e non generati.
`SUB_PLAN` non e' un worker — e' gestito inline dall'orchestrator.

Ogni modulo worker e' un'applicazione Spring Boot autonoma che estende `AbstractWorker`.

> **NON MODIFICARE** questi file a mano. Sono rigenerabili e le modifiche verranno
> sovrascritte. Per cambiare comportamento, modificare il manifest e rigenerare.

## Worker List

41 manifest, 53 moduli Maven (43 worker + 10 shared/infra). 18 Docker images pubblicate su `ghcr.io`.

### Infrastructure Workers

| Modulo | Type | Profile | Topic | Subscription | Owns Paths | Generato |
|--------|------|---------|-------|-------------|-----------|---------|
| `context-manager-worker` | CONTEXT_MANAGER | — | agent-tasks | context-manager-worker-sub | `.` (read-only) | ✓ |
| `schema-manager-worker` | SCHEMA_MANAGER | — | agent-tasks | schema-manager-worker-sub | `.` (read-only) | ✓ |
| `hook-manager-worker` | HOOK_MANAGER | — | agent-tasks | hook-manager-worker-sub | `.` (read-only) | ✓ |
| `task-manager-worker` | TASK_MANAGER | — | agent-tasks | task-manager-worker-sub | `issues/` | ✗ |
| `audit-manager-worker` | AUDIT_MANAGER | — | agent-tasks | audit-manager-worker-sub | `audit/` | ✓ |
| `event-manager-worker` | EVENT_MANAGER | — | agent-tasks | event-manager-worker-sub | `.` (read-only) | ✓ |
| `compensator-manager-worker` | COMPENSATOR_MANAGER | — | agent-tasks | compensator-manager-worker-sub | `.` | ✗ |
| `advisory-worker` | MANAGER/SPECIALIST | — | agent-advisory | advisory-worker-sub | `.` (read-only) | ✗ |
| _(inline orchestrator)_ | SUB_PLAN | — | — | — | — | — |

### Backend Workers (BE × 14)

| Modulo | Profile | Subscription | Owns Paths | Generato |
|--------|---------|-------------|-----------|---------|
| `be-java-worker` | be-java | be-java-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-go-worker` | be-go | be-go-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-rust-worker` | be-rust | be-rust-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-node-worker` | be-node | be-node-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-python-worker` | be-python | be-python-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-dotnet-worker` | be-dotnet | be-dotnet-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-kotlin-worker` | be-kotlin | be-kotlin-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-elixir-worker` | be-elixir | be-elixir-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-laravel-worker` | be-laravel | be-laravel-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-cpp-worker` | be-cpp | be-cpp-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-quarkus-worker` | be-quarkus | be-quarkus-worker-sub | `backend/`, `templates/be/` | ✓ |
| `be-ocaml-worker` | be-ocaml | be-ocaml-worker-sub | `backend/`, `templates/be/` | ✓ |

Tutti su topic `agent-tasks`, tipo `BE`.

### Frontend Workers (FE × 6)

| Modulo | Profile | Subscription | Owns Paths | Generato |
|--------|---------|-------------|-----------|---------|
| `fe-react-worker` | fe-react | fe-react-worker-sub | `frontend/`, `templates/fe/` | ✓ |
| `fe-angular-worker` | fe-angular | fe-angular-worker-sub | `frontend/`, `templates/fe/` | ✓ |
| `fe-vue-worker` | fe-vue | fe-vue-worker-sub | `frontend/`, `templates/fe/` | ✓ |
| `fe-svelte-worker` | fe-svelte | fe-svelte-worker-sub | `frontend/`, `templates/fe/` | ✓ |
| `fe-nextjs-worker` | fe-nextjs | fe-nextjs-worker-sub | `frontend/`, `templates/fe/` | ✓ |
| `fe-vanillajs-worker` | fe-vanillajs | fe-vanillajs-worker-sub | `frontend/`, `templates/fe/` | ✓ |

Tutti su topic `agent-tasks`, tipo `FE`.

### DBA Workers (DBA × 10)

| Modulo | Profile | Subscription | Owns Paths | Generato |
|--------|---------|-------------|-----------|---------|
| `dba-postgres-worker` | dba-postgres | dba-postgres-worker-sub | `database/`, `templates/dba/` | ✓ |
| `dba-mysql-worker` | dba-mysql | dba-mysql-worker-sub | `database/`, `templates/dba/` | ✓ |
| `dba-mssql-worker` | dba-mssql | dba-mssql-worker-sub | `database/`, `templates/dba/` | ✓ |
| `dba-oracle-worker` | dba-oracle | dba-oracle-worker-sub | `database/`, `templates/dba/` | ✓ |
| `dba-mongo-worker` | dba-mongo | dba-mongo-worker-sub | `database/`, `templates/dba/` | ✓ |
| `dba-redis-worker` | dba-redis | dba-redis-worker-sub | `database/`, `templates/dba/` | ✓ |
| `dba-sqlite-worker` | dba-sqlite | dba-sqlite-worker-sub | `database/`, `templates/dba/` | ✓ |
| `dba-cassandra-worker` | dba-cassandra | dba-cassandra-worker-sub | `database/`, `templates/dba/` | ✓ |
| `dba-graphdb-worker` | dba-graphdb | dba-graphdb-worker-sub | `database/`, `templates/dba/` | ✓ |
| `dba-vectordb-worker` | dba-vectordb | dba-vectordb-worker-sub | `database/`, `templates/dba/` | ✓ |

Tutti su topic `agent-tasks`, tipo `DBA`.

### Mobile Workers (MOBILE × 2)

| Modulo | Profile | Subscription | Owns Paths | Generato |
|--------|---------|-------------|-----------|---------|
| `mobile-swift-worker` | mobile-swift | mobile-swift-worker-sub | `ios/`, `mobile/`, `templates/mobile/` | ✓ |
| `mobile-kotlin-worker` | mobile-kotlin | mobile-kotlin-worker-sub | `android/`, `mobile/`, `templates/mobile/` | ✓ |

Tutti su topic `agent-tasks`, tipo `MOBILE`.

### Other Domain Workers

| Modulo | Type | Profile | Topic | Subscription | Owns Paths | Generato |
|--------|------|---------|-------|-------------|-----------|---------|
| `ai-task-worker` | AI_TASK | — | agent-tasks | ai-task-worker-sub | `cps4/`, `cps3/` | ✓ |
| `sdk-scaffold-worker` | AI_TASK | sdk-scaffold | agent-tasks | sdk-scaffold-worker-sub | `generated/`, `skills/sdkscaffold/` | ✓ |
| `contract-worker` | CONTRACT | — | agent-tasks | contract-worker-sub | `contracts/` | ✓ |
| `review-worker` | REVIEW | — | agent-reviews | review-worker-sub | `docs/` | ✓ |

> **Colonna Generato**: ✓ = prodotto da `agent-compiler-maven-plugin` (non modificare a mano);
> ✗ = scritto manualmente (modificabile); — = non e' un worker Spring Boot.

## Struttura generata

Ogni modulo segue la stessa struttura:

```
be-java-worker/
├── pom.xml                          # Dipendenze: worker-sdk, MCP tools, Spring AI
├── Dockerfile                       # FROM eclipse-temurin:21-jre + fat JAR
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
# Build completo (single command: genera moduli + compila tutto il reactor)
./build.sh -DskipTests

# Solo rigenerare moduli + registry (senza compilare)
mvn -N \
    com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:validate-manifests \
    com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-workers \
    com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-registry

# Build reactor senza rigenerare (i moduli devono gia' esistere)
mvn clean install -DskipTests
```

`build.sh` risolve il chicken-and-egg di Maven: prima genera i moduli con `-N` (non-recursive),
poi compila il reactor completo con i moduli appena generati.

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

1. Creare manifest: `agents/manifests/<name>.agent.yml`
2. Creare SKILL.md: `.claude/agents/<name>/SKILL.md` (polyglot header)
3. Build: `./build.sh -DskipTests` (genera il modulo + compila)

Il plugin aggiunge automaticamente il modulo al `pom.xml` root durante la generazione.

Vedi [Agent Compiler Plugin](../agent-compiler-maven-plugin/README.md) per il formato manifest completo.

## Naming Conventions

| Manifest `name` | Modulo | Classe | Package |
|-----------------|--------|--------|---------|
| `be-java` | `be-java-worker/` | `BeJavaWorker` | `bejavaworker` |
| `fe-react` | `fe-react-worker/` | `FeReactWorker` | `fereactworker` |
| `dba-postgres` | `dba-postgres-worker/` | `DbaPostgresWorker` | `dbapostgresworker` |
| `mobile-swift` | `mobile-swift-worker/` | `MobileSwiftWorker` | `mobileswiftworker` |
| `ai-task` | `ai-task-worker/` | `AiTaskWorker` | `aitaskworker` |

Conversione: kebab-case → PascalCase (classe), lowercase senza trattini (package).
