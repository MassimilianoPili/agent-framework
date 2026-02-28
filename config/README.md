# Config — Configuration Files Reference

File di configurazione YAML che governano comportamento, policy, e deploy del framework.
Alcuni sono **generati** dal compiler plugin (non modificare a mano), altri sono **manuali**
e rappresentano decisioni architetturali deliberate.

## File

| File | Generato | Scopo |
|------|----------|-------|
| `worker-profiles.yml` | Si | Profili worker: topic, subscription, MCP servers, ownsPaths, defaults |
| `agent-registry.yml` | Si | Metadata completo per ogni worker (immagine Docker, skill, concurrency) |
| `generated/hooks-config.json` | Si | Config per hook Claude Code (enforce-mcp-allowlist, enforce-ownership) |
| `quality-gates.yml` | No | Soglie quality gate: coverage, sicurezza, contratti, build, lint |
| `repo-layout.yml` | No | Path ownership per worker type (chi puo' scrivere dove) |
| `security-policy.yml` | No | PII, secrets, network, dipendenze — regole hard-block |
| `environments.yml` | No | Target di deploy: develop, test, collaudo, prod (risorse Azure) |
| `branching-policy.yml` | No | Branch protection, merge strategy, cascade merge |

## File generati

Rigenerabili con:

```bash
mvn com.agentframework:agent-compiler-maven-plugin:1.0.0-SNAPSHOT:generate-registry
```

Input: `agents/manifests/*.agent.yml`

### worker-profiles.yml

Consumato dall'orchestrator (`WorkerProfileRegistry`) per routing dei task:

```yaml
worker-profiles:
  be-java:
    workerType: BE
    topic: agent-tasks
    subscription: be-java-worker-sub
    displayName: "Backend Java Worker (Spring Boot)"
    mcpServers: [git, repo-fs, openapi, test]
    ownsPaths: [backend/, templates/be/]

defaults:
  BE: be-java      # Profilo default per tipo BE
  FE: fe-react     # Profilo default per tipo FE
  AI_TASK: ~       # Nessun default (profilo unico)
```

L'orchestrator usa `defaults` per risolvere il profilo quando il planner non ne assegna uno.

### agent-registry.yml

Catalogo completo di tutti i worker con metadata operativi:

- `displayName`, `description` — per dashboard e monitoring
- `image` — tag Docker per il deploy
- `mcpAllowlist` — tool consentiti a runtime
- `maxConcurrentCalls` — limita esecuzioni parallele
- `skillPaths` — skill iniettate nel contesto del worker
- `ownsPaths` — path di scrittura consentiti

### generated/hooks-config.json

Aggregazione per `workerType` di MCP server e path consentiti.
Usato dall'hook `enforce-mcp-allowlist.sh` per decisioni rapide senza parsing YAML.

## File manuali

### quality-gates.yml

Soglie per il quality gate report dell'orchestrator:

| Gate | Regola | Valore |
|------|--------|--------|
| `test.coverageMinPercent` | Coverage minima (JaCoCo / c8) | 80% |
| `security.criticalVulnerabilities` | Vulnerabilita' CVSS >= 9.0 | 0 (hard block) |
| `security.highVulnerabilities` | Vulnerabilita' CVSS >= 7.0 | 0 (hard block) |
| `contracts.breakingChanges` | Breaking change API (oasdiff) | 0 |
| `contracts.spectralErrors` | Errori lint OpenAPI (Spectral) | 0 |
| `build.mustPass` | Build deve passare | true |
| `lint.errors` | Errori Checkstyle/ESLint | 0 |
| `reviewApprovals.agentReviewRequired` | Review-worker deve approvare | true |

### repo-layout.yml

Mappa directory → owner con lista worker autorizzati:

| Path | Owner | Workers autorizzati | Human-only |
|------|-------|-------------------|-----------|
| `backend/` | be-workers | be-java, be-go, be-rust, be-node, review (RO) | No |
| `frontend/` | fe-workers | fe-react, review (RO) | No |
| `contracts/` | contract-worker | contract, review (RO) | No |
| `infra/` | humans-only | (nessuno) | Si |
| `control-plane/` | humans-only | (nessuno) | Si |
| `config/` | humans-only | (nessuno) | Si |
| `skills/` | humans-only | (nessuno) | Si |

Path non listati: default `humanOnly: true`.

### security-policy.yml

Regole hard-block applicate da tutti i worker:

- **PII**: campi sensibili (codiceFiscale, IBAN, email, etc.) — redazione in log/errori
- **Secrets**: pattern `.env`, `.pem`, `.key`, etc. — blocco commit + scan pre-commit
- **Network**: outbound limitato a `*.azure.com`, `registry.npmjs.org`, etc.
- **Dipendenze**: licenze permesse (MIT, Apache-2.0, BSD), bloccate (GPL, AGPL)

### environments.yml

4 ambienti di deploy con mapping Azure:

| Ambiente | Branch | Auto-deploy | Quality gate |
|----------|--------|------------|-------------|
| develop | develop | Si | standard |
| test | test | Si | standard |
| collaudo | test | No (sign-off manuale) | strict |
| prod | main | No (release pipeline) | strict |

### branching-policy.yml

Strategia `vertical-horizontal`:

- **Vertical**: feature branch `agent/{planId}/{taskKey}`, max 7 giorni
- **Horizontal**: `main` (prod), `develop` (integration), `test` (QA)
- **Cascade merge**: `main → develop → test` automatico, halt su conflitto
- **PR format**: `[{taskKey}] {summary}`, label `agent-generated`, auto-assign review-worker

## Relazione tra file

```
agents/manifests/*.agent.yml
         │
         │  mvn agent-compiler:generate-registry
         ▼
config/worker-profiles.yml  ──→  WorkerProfileRegistry (orchestrator)
config/agent-registry.yml   ──→  Dashboard / monitoring
config/generated/hooks-config.json ──→ enforce-mcp-allowlist.sh (Claude Code hook)

config/repo-layout.yml      ──→  PathOwnershipEnforcer (worker-sdk)
config/quality-gates.yml    ──→  QualityGateService (orchestrator)
config/security-policy.yml  ──→  Worker system prompts (injected as context)
config/environments.yml     ──→  CI/CD pipeline
config/branching-policy.yml ──→  CI/CD pipeline + orchestrator PR creation
```
