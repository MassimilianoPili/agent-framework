# Worker SDK — Execution Plane

Libreria base per tutti i worker del framework. Fornisce `AbstractWorker` (template method),
auto-configuration Spring Boot, pipeline di interceptor, policy enforcement per tool MCP,
e tracking della provenance.

## AbstractWorker API

### Metodi da implementare

| Metodo | Return | Descrizione |
|--------|--------|-------------|
| `workerType()` | `String` | Tipo semantico: `"BE"`, `"FE"`, `"AI_TASK"`, `"CONTRACT"`, `"REVIEW"` |
| `systemPromptFile()` | `String` | Path al prompt di sistema, es. `"skills/be.agent.md"` |
| `execute(AgentContext, ChatClient)` | `String` | Logica di esecuzione; ritorna JSON risultato |

### Metodi opzionali (override)

| Metodo | Default | Descrizione |
|--------|---------|-------------|
| `toolAllowlist()` | `ToolAllowlist.ALL` | Whitelist tool MCP consentiti |
| `skillPaths()` | `List.of()` | Skill aggiuntive da iniettare nel contesto |

### Utility

| Metodo | Descrizione |
|--------|-------------|
| `recordTokenUsage(Usage)` | Registra token consumati (ThreadLocal, cross-task safe) |
| `captureReasoning(String text)` | Cattura il primo blocco testo LLM prima del primo tool call (idempotente, truncate 2000 char, ThreadLocal) |
| `buildStandardUserPrompt(context, instructions)` | Assembla prompt utente standard: title, taskKey, description, spec, dependency results, instructions |

## Template Method: `process(AgentTask)`

Entry point invocato dal consumer. Sequenza garantita:

```
1.  Start timer
2.  Reset ThreadLocal (tool names, token usage)
3.  Build AgentContext (system prompt + skills + dep results)
3b. Set TASK_POLICY ThreadLocal from context.policy()
4.  Run interceptors: beforeExecute()  ← puo' modificare context
5.  Create ChatClient con tool policy
6.  Call execute(context, chatClient)  ← implementazione worker
7.  Run interceptors: afterExecute()   ← puo' trasformare result
8.  Collect tool names e token usage
9.  Compute SHA-256 hashes (prompt, skills)
10. Assemble Provenance record
11. Publish AgentResult (success/failure)
12. Cleanup ThreadLocal (tool names, token usage, REASONING, TASK_POLICY, context files)
```

In caso di eccezione al punto 6, viene eseguita la catena `onError()` degli interceptor
e pubblicato un AgentResult di fallimento.

## ToolAllowlist (sealed interface)

Tipo sicuro per la policy di tool access:

```java
// Tutti i tool disponibili (default)
ToolAllowlist.ALL

// Solo tool specifici
new ToolAllowlist.Explicit(List.of("Read", "Write", "Glob"))
```

Pattern matching:
```java
switch (allowlist) {
    case ToolAllowlist.All a -> includeAll();
    case ToolAllowlist.Explicit e -> filterBy(e.tools());
}
```

## WorkerInterceptor

Pipeline per cross-cutting concerns. Ogni interceptor implementa `Ordered` per
determinare la priorita'.

```java
public interface WorkerInterceptor extends Ordered {
    // Modifica contesto prima dell'esecuzione
    default AgentContext beforeExecute(AgentContext ctx, AgentTask task) { return ctx; }

    // Trasforma risultato dopo l'esecuzione
    default String afterExecute(AgentContext ctx, String result, AgentTask task) { return result; }

    // Gestisce errori (logging, alerting, etc.)
    default void onError(AgentContext ctx, Exception e, AgentTask task) {}
}
```

Catena di esecuzione: interceptor con `getOrder()` piu' basso viene eseguito per primo.

### Built-in interceptors

| Interceptor | Ordine | Hooks | Comportamento |
|---|---|---|---|
| `WorkerMetricsInterceptor` | `HIGHEST_PRECEDENCE` | before / after / onError | MDC fields (`task_key`, `worker_type`, `worker_profile`, `policy_active`); log `TASK_START` / `TASK_SUCCESS` / `TASK_FAILURE` |
| `ContextCacheInterceptor` | `HIGHEST_PRECEDENCE + 10` | beforeExecute | Controlla `ContextCacheStore` per un contesto pre-assemblato; se trovato, sostituisce il contesto (evita reload di skill e prompt identici) |
| `ResultSchemaValidationInterceptor` | `LOWEST_PRECEDENCE` | afterExecute | `objectMapper.readTree(result)`: warn se non JSON valido; **fail-open** — ritorna sempre il risultato originale invariato |

## Policy Layer

`WorkerChatClientFactory` assembla il ChatClient con tool filtering:

```
ToolCallbackProvider beans (auto-discovered)
         │
         ▼
    ToolAllowlist filter (All o Explicit)
         │
         ▼
    PolicyEnforcingToolCallback wrapper (se policy attiva)
    ├── [0] task-level tool allowlist   (HookPolicy.allowedTools, se presente)
    ├── [1] PathOwnershipEnforcer write (HookPolicy.ownedPaths → PolicyProperties fallback)
    ├── [2] Context-aware Read check    (relevantFiles da CONTEXT_MANAGER result)
    └── [3] ToolAuditLogger             (logga ogni invocazione + outcome)
         │
         ▼
    ChatClient configurato
```

Policy source priority: `HookPolicy` (task-level, da ThreadLocal TASK_POLICY) > `PolicyProperties` (static, application.yml).
Policy attiva quando tutti e 3 i bean sono presenti: `PathOwnershipEnforcer`,
`ToolAuditLogger`, `PolicyProperties(enabled=true)`.

## HookPolicy — campi task-level

`HookPolicy` (da `com.agentframework.common.policy`) è il record che l'orchestrator inietta
nell'`AgentTask` per ogni task gestito dal `HOOK_MANAGER`. Accessibile tramite
`AgentContext.policy()` o il ThreadLocal `TASK_POLICY`.

| Campo | Tipo | Semantica |
|-------|------|-----------|
| `allowedTools` | `List<String>` | Tool consentiti per questo task; vuoto = eredita config statica |
| `ownedPaths` | `List<String>` | Prefissi path scrivibili; vuoto = eredita `PolicyProperties.ownsPaths` |
| `allowedMcpServers` | `List<String>` | Server MCP consentiti; vuoto = nessuna restrizione |
| `auditEnabled` | `boolean` | Se true, ogni tool call viene loggata su `audit.tools` logger |
| `maxTokenBudget` | `Integer` | Tetto token per questo task; null = usa budget piano |
| `allowedNetworkHosts` | `List<String>` | Host di rete consentiti (es. `api.github.com`); vuoto = nessuna restrizione |
| `requiredHumanApproval` | `ApprovalMode` | `NONE` / `BLOCK` / `NOTIFY_TIMEOUT` — richiesta approvazione umana |
| `approvalTimeoutMinutes` | `int` | Minuti attesa quando mode è `NOTIFY_TIMEOUT`; 0 = fallisce subito |
| `riskLevel` | `RiskLevel` | `LOW` / `MEDIUM` / `HIGH` / `CRITICAL`; `CRITICAL` → orchestrator mette in `AWAITING_APPROVAL` |
| `estimatedTokens` | `Integer` | Stima token per check budget pre-dispatch; null = nessuna stima |
| `shouldSnapshot` | `boolean` | Se true, orchestrator cattura snapshot workspace prima dell'esecuzione |

Policy source priority: `HookPolicy` (task-level, ThreadLocal `TASK_POLICY`) > `PolicyProperties` (static, application.yml).

## Context Cache

`ContextCacheStore` è un'SPI per evitare di ricaricare system prompt e skill identici tra task
dello stesso tipo nello stesso piano:

```java
public interface ContextCacheStore {
    Optional<AgentContext> get(String cacheKey);
    void put(String cacheKey, AgentContext ctx);
    void evict(String cacheKey);
}
```

- **Default**: `NoOpContextCacheStore` — nessuna cache (zero side-effect, sempre safe).
- **Override Redis**: fornire un bean `@Primary ContextCacheStore` backed by Redis (serializza `AgentContext` come JSON).
- **Cache key formula**: `SHA-256(workerType + ":" + systemPromptFile + ":" + sorted(skillPaths))`.
- **`ContextCacheHolder`**: ThreadLocal che espone la store all'`ContextCacheInterceptor` durante `beforeExecute()`.

Il `ContextCacheInterceptor` prima chiama `get(cacheKey)`:
- **Hit** → sostituisce il contesto ricevuto con quello cachato (nessuna lettura da filesystem).
- **Miss** → lascia procedere `AgentContextBuilder`; al termine di `afterExecute()` salva il contesto.

## Auto-Configuration

`WorkerAutoConfiguration` si attiva quando e' presente la property `agent.worker.task-topic`:

```java
@AutoConfiguration
@ConditionalOnProperty(name = "agent.worker.task-topic")
@EnableConfigurationProperties(WorkerProperties.class)
```

Bean creati automaticamente:
- `WorkerTaskConsumer` — consuma task da topic/subscription
- `WorkerResultProducer` — pubblica risultati su `agent-results`
- `AgentContextBuilder` — assembla contesto con prompt e skills
- `SkillLoader` — carica skill da filesystem o classpath

Il worker app deve solo:
1. Dichiarare un bean `@Component` che estende `AbstractWorker`
2. Configurare `agent.worker.*` in application.yml
3. Aggiungere dipendenze MCP tool nel pom.xml

## Properties

| Property | Default | Descrizione |
|----------|---------|-------------|
| `agent.worker.task-topic` | — | Topic da cui consumare task (obbligatorio) |
| `agent.worker.task-subscription` | — | Subscription/queue per il consumer |
| `agent.worker.results-topic` | `agent-results` | Topic per pubblicare risultati |

## AgentContext

Record immutabile con tutto il contesto necessario all'esecuzione:

| Campo | Tipo | Descrizione |
|-------|------|-------------|
| `taskKey` | String | Identificativo task (es. `BE-001`) |
| `title` | String | Titolo leggibile |
| `description` | String | Descrizione dettagliata |
| `spec` | String | Specifica del piano |
| `systemPrompt` | String | Prompt di sistema assemblato (primario + skills) |
| `dependencyResults` | Map\<String, String\> | Risultati delle dipendenze: {taskKey → JSON} |
| `skillsContent` | String | Contenuto skill concatenato |
| `policy` | `HookPolicy?` | Policy task-level da HM worker (null = usa config statica); accessibile via `hasPolicy()` |
| `relevantFiles` | `List<String>?` | File autorizzati in lettura (da CONTEXT_MANAGER result); null = no restrizione |

## Provenance

Ogni `AgentResult` include un record `Provenance` con metadati di esecuzione:

| Campo | Descrizione |
|-------|-------------|
| workerType | Tipo semantico (BE, FE, etc.) |
| workerProfile | Profilo concreto (be-java, etc.) |
| attemptNumber | Numero tentativo (1, 2, ...) |
| traceId | UUID per correlazione distribuita |
| toolsUsed | Lista tool invocati durante l'esecuzione |
| promptHashValue | SHA-256 del system prompt |
| skillsHashValue | SHA-256 del contenuto skills |
| tokenUsage | Token consumati (input + output) |
| reasoning | Primo blocco testo LLM prima di qualsiasi tool call (max 2000 char; null se non catturato) |

## File chiave

| Path | Scopo |
|------|-------|
| `AbstractWorker.java` | Base class con template method |
| `ToolAllowlist.java` | Sealed interface per policy tool |
| `config/WorkerAutoConfiguration.java` | Auto-config Spring Boot |
| `config/WorkerProperties.java` | Properties `agent.worker.*` |
| `context/AgentContext.java` | Record contesto immutabile |
| `context/AgentContextBuilder.java` | Builder per contesto |
| `context/SkillLoader.java` | Caricamento skill (filesystem → classpath) |
| `messaging/WorkerTaskConsumer.java` | Consumer messaggi task |
| `messaging/WorkerResultProducer.java` | Producer risultati |
| `interceptor/WorkerInterceptor.java` | Interfaccia interceptor pipeline |
| `interceptor/WorkerMetricsInterceptor.java` | MDC structured logging; TASK_START / TASK_SUCCESS / TASK_FAILURE |
| `interceptor/ResultSchemaValidationInterceptor.java` | Validazione JSON output (fail-open) |
| `claude/WorkerChatClientFactory.java` | Factory ChatClient con policy |
| `policy/PolicyEnforcingToolCallback.java` | Wrapper per tool con enforcement (4 step: allowlist, write, read, audit) |
| `policy/PathOwnershipEnforcer.java` | Verifica path ownership (write + context-aware read) |
| `policy/HookPolicy.java` | **@Deprecated stub** — usare `com.agentframework.common.policy.HookPolicy` (agent-common) |
| `cache/ContextCacheStore.java` | SPI cache contesto; default `NoOpContextCacheStore` |
| `cache/ContextCacheHolder.java` | ThreadLocal che espone `ContextCacheStore` all'interceptor |
| `interceptor/ContextCacheInterceptor.java` | Interceptor hit/miss cache contesto (HIGHEST_PRECEDENCE + 10) |
| `dto/AgentResult.java` | DTO risultato con Provenance |
