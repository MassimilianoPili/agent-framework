# MCP Layer — Tool Access Control

5 server MCP (Model Context Protocol) che forniscono tool ai worker, con un framework
di policy per controllare accesso, limiti, e redazione dell'output.

## Architettura

```
Worker Session (Claude AI)
    │
    ├── Mode A: In-process tools (default, classpath @ReactiveTool beans)
    │   └── ToolCallbackProvider → WorkerChatClientFactory
    │
    ├── Mode B/C: External MCP Server (profile mcp)
    │   └── spring-ai-mcp-client → SSE → MCP Server → tool results
    │
    ├── PreToolUse Hook: enforce-mcp-allowlist.sh
    │   └── Controlla AGENT_WORKER_TYPE vs allowlists/*.yml
    │
    ▼
WorkerChatClientFactory
    │
    ├── Allowlist filter (ToolAllowlist.Explicit)
    ├── PolicyEnforcingToolCallback (ownership, audit, tracking)
    ├── Sandbox: limits.yml (file size, timeout, recursion)
    ├── Redaction: redaction-rules.yml (secrets, PII)
    │
    ▼
ChatClient → Claude → tool calls → results (output pulito)
```

Modalita' di attivazione: vedi [MCP Usage Guide](docs/usage.md).

## MCP Servers

| Server | Package | Transport | Scopo |
|--------|---------|-----------|-------|
| `git` | `mcp-devops-tools` | sse / stdio | Operazioni Git (clone, commit, push, diff, log, merge) |
| `repo-fs` | `mcp-filesystem-tools` | sse / stdio | Filesystem (read, write, edit, glob, grep) con sandboxing |
| `openapi` | `mcp-devops-tools` | sse / stdio | Validazione OpenAPI (Spectral lint, oasdiff breaking, generate) |
| `azure` | `mcp-devops-tools` | sse / stdio | Risorse Azure (Service Bus, Container Apps, Key Vault) |
| `test` | `mcp-devops-tools` | sse / stdio | Esecuzione test (Maven, npm, coverage report) |

Registry: `mcp/registry/mcp-registry.yml`

## Allowlists (`policies/allowlists/`)

Ogni MCP server ha un file di allowlist che definisce quali operazioni sono consentite
a quali worker.

### Esempio: `git.yml`

```yaml
operations:
  git_clone:
    allowed: [be-worker, fe-worker, ai-task-worker, contract-worker]
  git_push:
    allowed: [be-worker, fe-worker, contract-worker]
    constraints:
      noForce: true
      protectedBranches: [main, develop, release/*]
  git_merge:
    allowed: [ai-task-worker]
    constraints:
      noConflicts: true
```

### Esempio: `repo-fs.yml`

```yaml
operations:
  read_file:
    allowed: [all]
  write_file:
    allowed: [be-worker, fe-worker, ai-task-worker, contract-worker]
    constraints:
      enforceOwnership: true     # Verifica config/repo-layout.yml
      maxFileSize: 102400        # 100 KB
  # review-worker: solo lettura
```

### File disponibili

| File | Operazioni chiave |
|------|------------------|
| `git.yml` | clone, checkout, commit, push, branch, diff, log, merge, status |
| `repo-fs.yml` | read_file, write_file, edit_file, glob_search, grep_search |
| `openapi.yml` | spectral_lint, oasdiff_breaking, generate_client, generate_server_stubs |
| `test.yml` | maven_test, npm_test, coverage_report, run_single_test |
| `azure.yml` | servicebus_send_message, servicebus_peek_dlq, container_apps_job_start |

## Sandbox Limits (`policies/sandbox/limits.yml`)

Limiti globali applicati a tutte le operazioni tool:

| Limite | Default | Override (test) | Override (azure) |
|--------|---------|-----------------|------------------|
| `maxFileSize` | 100 KB | — | — |
| `maxRecursionDepth` | 10 | — | — |
| `maxToolCallsPerTask` | 50 | — | 20 |
| `timeoutSeconds` | 300 (5 min) | 600 (10 min) | 120 (2 min) |
| `maxFilesPerGlob` | 500 | — | — |
| `maxGrepMatches` | 200 | — | — |
| `maxOutputBytes` | 512 KB | 1 MB | — |
| `maxConcurrentTools` | 3 | — | — |

## Redaction (`policies/redaction/redaction-rules.yml`)

Pattern regex per mascherare automaticamente dati sensibili dall'output dei tool.

### Segreti (critical severity)

| Pattern | Esempio |
|---------|---------|
| API key | `api_key=sk-ant-abc123...` → `***REDACTED_API_KEY***` |
| Bearer token | `Bearer eyJ...` → `***REDACTED_BEARER***` |
| Connection string | `Endpoint=sb://...;SharedAccessKey=abc` → `***REDACTED_CONN***` |
| Password in URL | `://user:p4ss@host` → `://user:***REDACTED***@host` |
| Private key | `-----BEGIN PRIVATE KEY-----` → `***REDACTED_KEY***` |

### PII (high/medium severity)

| Pattern | Tipo |
|---------|------|
| Codice Fiscale italiano | 6 lettere + 8 cifre |
| IBAN italiano | IT + 24 caratteri |
| Carta di credito | 4 gruppi di 4 cifre |
| Email | Escluso da file .yml/.yaml/.properties |
| Telefono italiano | +39 o formato locale |

### Scope

La redazione e' applicata su:
- Output dei tool (prima che arrivi al LLM)
- Log applicativi
- Payload degli eventi

## Tool I/O Schemas (`schemas/tool-io/`)

Contratti JSON Schema per input e output di ogni operazione tool:

| Schema | Operazioni definite |
|--------|-------------------|
| `git.schema.json` | GitCloneInput/Output, GitCommitInput/Output, GitDiffInput/Output, etc. |
| `repo-fs.schema.json` | ReadFileInput/Output, WriteFileInput/Output, EditFileInput/Output, GlobInput/Output, GrepInput/Output |
| `openapi.schema.json` | SpectralLintInput/Output, OasdiffInput/Output, GenerateInput/Output |
| `azure.schema.json` | ServiceBusSendInput/Output, ContainerAppsInput/Output |
| `test.schema.json` | MavenTestInput/Output, NpmTestInput/Output, CoverageInput/Output |

## Hook Integration

Il controllo avviene a livello Claude Code tramite hook `PreToolUse`:

1. `enforce-mcp-allowlist.sh` legge `AGENT_WORKER_TYPE` dalla env del worker
2. Cerca l'operazione richiesta in `config/generated/hooks-config.json`
3. Verifica che il worker type abbia accesso al server MCP e all'operazione
4. Se non autorizzato: blocca con messaggio di errore

`hooks-config.json` e' generato dal plugin `generate-registry` e aggrega i permessi
di tutti i manifest per worker type.

## Struttura directory

```
mcp/
├── registry/
│   └── mcp-registry.yml              # Catalogo server MCP
├── servers/
│   ├── git-mcp/                       # Server Git
│   ├── repo-fs-mcp/                   # Server filesystem
│   ├── openapi-mcp/                   # Server OpenAPI
│   ├── azure-mcp/                     # Server Azure
│   └── test-mcp/                      # Server test
├── policies/
│   ├── allowlists/                    # Per-server, per-worker allowlists
│   │   ├── git.yml
│   │   ├── repo-fs.yml
│   │   ├── openapi.yml
│   │   ├── test.yml
│   │   └── azure.yml
│   ├── sandbox/
│   │   └── limits.yml                 # Limiti globali
│   └── redaction/
│       └── redaction-rules.yml        # Pattern secrets/PII
├── schemas/
│   └── tool-io/                       # JSON Schema per I/O tool
│       ├── git.schema.json
│       ├── repo-fs.schema.json
│       ├── openapi.schema.json
│       ├── azure.schema.json
│       └── test.schema.json
└── docs/
    └── usage.md                       # Documentazione architetturale
```
