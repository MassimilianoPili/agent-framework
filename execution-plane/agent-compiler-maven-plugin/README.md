# Agent Compiler Maven Plugin

Plugin Maven che genera moduli worker completi a partire da manifest YAML.
Trasforma definizioni dichiarative in codice Java compilabile, configurazione Spring Boot,
e Dockerfile pronti per il deploy.

## Goals

| Goal | Fase Maven | Input | Output |
|------|-----------|-------|--------|
| `generate-workers` | GENERATE_SOURCES | `agents/manifests/*.agent.yml` | Moduli Maven completi in `execution-plane/workers/` |
| `generate-registry` | GENERATE_RESOURCES | `agents/manifests/*.agent.yml` | `config/worker-profiles.yml`, `config/agent-registry.yml`, `config/generated/hooks-config.json` |
| `validate-manifests` | VALIDATE | `agents/manifests/*.agent.yml` | Errore se manifest invalidi (CI gate) |

## Manifest Schema

Ogni manifest segue `apiVersion: agent-framework/v1`:

```yaml
metadata:
  name: string          # Identificativo univoco (kebab-case)
  displayName: string   # Nome leggibile
  description: string   # Descrizione

spec:
  workerType: enum      # BE | FE | AI_TASK | CONTRACT | REVIEW
  workerProfile: string # Profilo concreto (be-java, be-go, etc.)
  topic: string         # Topic messaging (quasi sempre "agent-tasks")
  subscription: string  # Subscription univoca

  model:
    name: string        # Modello Claude (claude-sonnet-4-6)
    maxTokens: int      # Token massimi risposta
    temperature: float  # 0.0-1.0

  prompts:
    systemPromptFile: string   # Path prompt di sistema
    skills: [string]           # Path skill aggiuntive
    instructions: string       # Istruzioni task-specific
    resultSchema: string       # Schema JSON risultato

  tools:
    dependencies: [string]     # Coordinate Maven (groupId:artifactId)
    allowlist: [string]        # Tool consentiti
    mcpServers: [string]       # Server MCP da attivare

  ownership:
    ownsPaths: [string]        # Path scrivibili
    readOnlyPaths: [string]    # Path in sola lettura

  concurrency:
    maxConcurrentCalls: int    # Max esecuzioni parallele
```

## Output generato (per worker)

```
execution-plane/workers/{name}-worker/
├── pom.xml
│   ├── parent: agent-framework
│   ├── dependencies: worker-sdk, MCP tools, Spring AI
│   └── spring-boot-maven-plugin (repackage)
│
├── Dockerfile
│   └── FROM eclipse-temurin:17-jre + COPY + ENTRYPOINT
│
└── src/main/
    ├── java/com/agentframework/workers/generated/{package}/
    │   ├── {ClassName}Worker.java
    │   │   ├── extends AbstractWorker
    │   │   ├── @Component + @Generated
    │   │   ├── workerType(), systemPromptFile()
    │   │   ├── toolAllowlist() con ToolAllowlist.Explicit
    │   │   ├── execute() con buildStandardUserPrompt()
    │   │   └── INSTRUCTIONS constant
    │   │
    │   └── {ClassName}WorkerApplication.java
    │       └── @SpringBootApplication main class
    │
    └── resources/
        └── application.yml
            ├── spring.application.name
            ├── agent.worker.task-topic / task-subscription
            ├── spring.ai.anthropic.chat.options.model / max-tokens / temperature
            └── policy.enabled: true
```

## Configuration

```xml
<plugin>
    <groupId>com.agentframework</groupId>
    <artifactId>agent-compiler-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <configuration>
        <!-- Directory con i manifest YAML (default: ${basedir}/agents/manifests) -->
        <manifestDirectory>${project.basedir}/agents/manifests</manifestDirectory>
        <!-- Directory output worker modules (default: ${basedir}/execution-plane/workers) -->
        <outputDirectory>${project.basedir}/execution-plane/workers</outputDirectory>
        <!-- Directory output config (default: ${basedir}/config) -->
        <configDirectory>${project.basedir}/config</configDirectory>
    </configuration>
</plugin>
```

## Usage

### Generare worker modules

```bash
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-workers
```

Genera un modulo Maven completo per ogni `*.agent.yml`. I moduli esistenti vengono sovrascritti.

### Generare registry e config

```bash
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-registry
```

Output:
- `config/worker-profiles.yml` — profili per il `WorkerProfileRegistry` dell'orchestrator
- `config/agent-registry.yml` — metadata completo per ogni worker
- `config/generated/hooks-config.json` — config per hook Claude Code (enforce-mcp-allowlist, enforce-ownership)

### Validare manifest (CI)

```bash
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:validate-manifests
```

Verifica struttura e campi obbligatori. Fallisce il build se un manifest e' invalido.

## Come aggiungere un nuovo worker

1. Creare `agents/manifests/be-python.agent.yml`:
   ```yaml
   apiVersion: agent-framework/v1
   kind: AgentManifest
   metadata:
     name: be-python
     displayName: "Backend Python Worker"
   spec:
     workerType: BE
     workerProfile: be-python
     topic: agent-tasks
     subscription: be-python-worker-sub
     model: { name: claude-sonnet-4-6, maxTokens: 16384, temperature: 0.2 }
     prompts: { systemPromptFile: skills/be-python.agent.md }
     tools:
       dependencies: [io.github.massimilianopili:mcp-filesystem-tools]
       allowlist: [Read, Write, Edit, Bash]
     ownership: { ownsPaths: [backend/] }
     concurrency: { maxConcurrentCalls: 3 }
   ```
2. Generare:
   ```bash
   mvn agent-compiler:generate-workers agent-compiler:generate-registry
   ```
3. Aggiungere al `pom.xml` root:
   ```xml
   <module>execution-plane/workers/be-python-worker</module>
   ```
4. Build: `mvn clean install`

## Naming Conventions

| Input (manifest) | Output |
|-------------------|--------|
| `name: be-java` | Module: `be-java-worker/`, Class: `BeJavaWorker`, Package: `bejavaworker` |
| `name: fe-react` | Module: `fe-react-worker/`, Class: `FeReactWorker`, Package: `fereactworker` |
| `name: ai-task` | Module: `ai-task-worker/`, Class: `AiTaskWorker`, Package: `aitaskworker` |

Conversione: kebab-case → PascalCase (classe), lowercase senza trattini (package).

## File chiave

| Path | Scopo |
|------|-------|
| `AgentCompilerMojo.java` | Goal `generate-workers`: genera moduli da manifest |
| `GenerateRegistryMojo.java` | Goal `generate-registry`: genera config files |
| `ValidateManifestsMojo.java` | Goal `validate-manifests`: validazione CI |
| `manifest/ManifestLoader.java` | Parsing YAML → `AgentManifest` (SnakeYAML) |
| `manifest/AgentManifest.java` | POJO con metadata/spec/model/tools/ownership |
| `generator/WorkerGenerator.java` | Generazione codice (Mustache templates) |
| `src/main/resources/templates/` | Template Mustache (Worker.java, pom.xml, application.yml, Dockerfile) |
