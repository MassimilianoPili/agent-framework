# Agent Framework — Indice README

Mappa rapida di tutti i README e delle sezioni chiave del progetto.
Usare questo file come punto di ingresso per navigare la documentazione senza
dover rileggere i README completi.

---

## README principali

| File | Righe (~) | Contenuto |
|------|-----------|-----------|
| [README.md](README.md) | ~1030 | Overview completo: architettura, pipeline, policy, test, tech stack |
| [control-plane/orchestrator/README.md](control-plane/orchestrator/README.md) | ~360 | REST API, domain model, state machine, Flyway, file chiave |
| [PIANO.md](PIANO.md) | ~1120 | Piano evoluzione: roadmap #1-#18, sessioni S1-S7, riepilogo file |
| [SETUP.md](SETUP.md) | - | Guida installazione e primo avvio |
| [docs/manual/user-guide.md](docs/manual/user-guide.md) | - | Manuale utente completo |

---

## Sezioni chiave per argomento

### Architettura e Pipeline

| Argomento | File | Sezione |
|-----------|------|---------|
| Mermaid sequence diagram | README.md | `## Architecture` |
| Pipeline overview (ASCII) | README.md | `### Pipeline Overview` |
| Worker profiles (41 manifest, 48 moduli) | README.md | `## Active Worker Profiles` |
| Agent manifest format | README.md | `## Agent Manifest Format` |
| Polyglot header (SKILL.md) | README.md | `## Claude Code Subagents — Polyglot Header` |
| Diagrammi dettagliati (8) | [docs/architecture/architecture-diagram.md](docs/architecture/architecture-diagram.md) | - |
| Panoramica architettura | [docs/architecture/overview.md](docs/architecture/overview.md) | - |

### REST API e Domain Model

| Argomento | File | Sezione |
|-----------|------|---------|
| Endpoint table (20+) | README.md | `### API Endpoints` |
| Endpoint dettagli | orchestrator/README.md | `## API Endpoints` |
| Plan entity | orchestrator/README.md | `### Plan` |
| PlanItem entity | orchestrator/README.md | `### PlanItem` |
| PlanEvent (event sourcing) | orchestrator/README.md | `### PlanEvent` |
| State machine diagram | orchestrator/README.md | `## State Machine` |
| OrchestrationService | orchestrator/README.md | `## Orchestration Service` |
| Spring Events | orchestrator/README.md | `## Spring Events` |

### Database (Flyway)

| Migrazione | Tabelle | Scopo | Sessione |
|-----------|---------|-------|----------|
| V1 | plans, plan_items, plan_item_deps, quality_gate_*, dispatch_attempts, plan_snapshots | Core orchestrator | S1 |
| V2 | (alter) plans + plan_items, plan_token_usage | Resilienza: retry, budget, issue snapshot | S2 |
| V3 | plan_event, (alter) plans + plan_items | Event sourcing + SUB_PLAN gerarchici | S3 |
| V4 | (alter) plans + plan_items, worker_elo_stats, preference_pairs | Council + reward (ELO, DPO) | S4 |
| V5 | vector_store + pgvector | RAG: embedding 1024 dim, HNSW, BM25 | S1 |
| V6 | Apache AGE + grafi | Graph RAG: knowledge_graph + code_graph | S2 |
| V7 | (alter) plan_items | Ralph-Loop: ralph_loop_count, quality_gate_feedback | S5 |
| V8 | task_outcomes | GP training: embedding pgvector(1024), ELO snapshot, GP mu/sigma2, actual_reward | S6 |
| V9 | (alter) preference_pairs | DPO GP Residual: gp_residual FLOAT + indice | S7 |

Dettagli: [orchestrator/README.md](control-plane/orchestrator/README.md) → `## Database (Flyway)`

### Reward System

| Argomento | File | Sezione |
|-----------|------|---------|
| Multi-source Bayesian scoring | README.md | `### Reward Signal System` |
| processScore formula | README.md | (dentro Reward Signal System) |
| ELO ratings | README.md | `#### ELO ratings` |
| DPO preference pairs (3 strategie) | README.md | `#### DPO preference pairs` |
| File chiave reward | orchestrator/README.md | `## File chiave` (sezione reward/) |

**3 strategie DPO** (S7):
1. `same_plan_cross_profile` — due profili stesso workerType, delta reward ≥ 0.3
2. `retry_comparison` — attempt fallito vs riuscito
3. `gp_residual_surprise` — cross-profile filtrato per sorpresa GP (residual ≥ 0.15)

### GP Engine (Gaussian Process)

| Argomento | File | Sezione |
|-----------|------|---------|
| Modulo `shared/gp-engine` | PIANO.md | `## Sessione 6 — GP Engine Module` → `### S6-A` |
| Integrazione orchestratore | PIANO.md | `### S6-B` |
| GpWorkerSelectionService (UCB) | orchestrator/README.md | `## File chiave` → `gp/GpWorkerSelectionService.java` |
| TaskOutcomeService | orchestrator/README.md | `## File chiave` → `gp/TaskOutcomeService.java` |
| DPO GP Residual | PIANO.md | `## Sessione 7 — DPO con GP Residual` |
| ADR motivazioni | [docs/adr/ADR-005-gp-serendipity-evolution.md](docs/adr/ADR-005-gp-serendipity-evolution.md) | - |
| Config YAML | orchestrator application.yml | sezione `gp:` (enabled, default-prior-mean, max-training-size, cache) |

### RAG Engine

| Argomento | File | Sezione |
|-----------|------|---------|
| Overview (ingestion + search) | README.md | Status Snapshot → `**RAG Engine**` |
| Piano 3 sessioni | PIANO.md | `# RAG Pipeline + Graph RAG` |
| Config YAML | PIANO.md | `## Configurazione YAML RAG` |

### Policy e Sicurezza

| Argomento | File | Sezione |
|-----------|------|---------|
| Policy enforcement layer | README.md | `## Policy Enforcement` |
| HookPolicy (11 campi) | README.md | `### Task-Level HookPolicy (Dynamic)` |
| Defense-in-depth | README.md | `### Defense-in-Depth` |
| Hook pattern workers | README.md | `### Hook pattern for write-capable workers` |

### Messaging

| Argomento | File | Sezione |
|-----------|------|---------|
| SPI overview | README.md | `## Messaging SPI` |
| Provider implementations | [messaging/README.md](messaging/README.md) | - |

### MCP Client Mode

| Argomento | File | Sezione |
|-----------|------|---------|
| Mode A/B/C | README.md | `## MCP Client Mode` |
| MCP tools e server | [mcp/README.md](mcp/README.md) | - |

### Feature avanzate

| Feature | File | Sezione |
|---------|------|---------|
| SSE Event Streaming | README.md | `### SSE Event Streaming` |
| Token Budget | README.md | `### Token Budget` |
| Missing-Context Feedback | README.md | `### Missing-Context Feedback Loop` |
| Auto-Retry | README.md | `### Auto-Retry with Exponential Backoff` |
| Human Approval | README.md | `### Human Approval (AWAITING_APPROVAL)` |
| COMPENSATOR_MANAGER | README.md | `### COMPENSATOR_MANAGER` |
| SUB_PLAN | README.md | `### SUB_PLAN (Hierarchical Plans)` |
| Council System | README.md | `### Council System (Advisory Pre-Planning)` |
| Ralph-Loop | PIANO.md | `## Sessione 5` |

### Compiler Plugin

| Argomento | File | Sezione |
|-----------|------|---------|
| 3 goal Maven | README.md | `## Agent Compiler Plugin` |
| Dettagli | [execution-plane/agent-compiler-maven-plugin/README.md](execution-plane/agent-compiler-maven-plugin/README.md) | - |

---

## Test Coverage (416 test)

| Modulo | Test | Classi |
|--------|------|--------|
| Orchestrator | 255 | 17 |
| RAG Engine | 113 | 21 |
| GP Engine | 30 | 5 |
| Compiler | 18 | 2 |
| **Totale** | **416** | **35** |

Dettagli per classe: README.md → `## Test Coverage`

---

## Roadmap (#1-#18)

| # | Feature | Stato |
|---|---------|-------|
| 1 | Event Sourcing puro | ✅ S3 |
| 2+3 | Missing-context + Auto-retry | ✅ S2 |
| 4 | COMPENSATOR_MANAGER | ✅ S4 |
| 5 | SSE + TrackerSyncService | ✅ S3 |
| 6 | Token budget per WorkerType | ✅ S2 |
| 8 | DAG endpoint Mermaid | ✅ S2 |
| 11 | GP Worker Selection | ✅ S6 |
| 14 | DPO con GP Residual | ✅ S7 |
| 12 | Serendipità Context Manager | Prossimo (dipende #11) |
| 13 | Council Taste Profile | Backlog (dipende #11) |
| 15 | Active Token Budget | Backlog (dipende #11) |
| 16 | Ralph-Loop (Quality Gate) | ✅ S5 |
| 17 | SDK Scaffold Worker | Backlog |
| 18 | ADR-005 (GP Motivazioni) | Backlog |

Dettagli: PIANO.md → `## Tabella priorità`

---

## Altri README

| File | Contenuto |
|------|-----------|
| [execution-plane/worker-sdk/README.md](execution-plane/worker-sdk/README.md) | AbstractWorker API, interceptor, policy |
| [execution-plane/workers/README.md](execution-plane/workers/README.md) | 48 moduli worker (41 generati + 7 manuali), build con `build.sh` |
| [contracts/README.md](contracts/README.md) | JSON Schema, OpenAPI, AsyncAPI |
| [config/README.md](config/README.md) | File YAML: profili, quality gate, policy |
| [mcp/README.md](mcp/README.md) | Server MCP, allowlist, sandbox |
| [messaging/README.md](messaging/README.md) | SPI, provider JMS/Redis/Service Bus |

### ADR (Architecture Decision Records)

| ADR | Decisione |
|-----|-----------|
| [ADR-001](docs/adr/ADR-001-service-bus-topology.md) | Topologia Service Bus |
| [ADR-002](docs/adr/ADR-002-structured-output.md) | Structured output dal modello |
| [ADR-003](docs/adr/ADR-003-legacy-deprecation-roadmap.md) | Roadmap deprecazione naming legacy |
| [ADR-005](docs/adr/ADR-005-gp-serendipity-evolution.md) | GP Serendipity Evolution |

### Eval Scenarios

| Scenario | Path |
|----------|------|
| Incomplete Requirements | [eval/scenarios/incomplete-requirements/](eval/scenarios/incomplete-requirements/README.md) |
| Partial Failure Recovery | [eval/scenarios/partial-failure-recovery/](eval/scenarios/partial-failure-recovery/README.md) |
| Contract Breaking Change | [eval/scenarios/contract-breaking-change/](eval/scenarios/contract-breaking-change/README.md) |
| Tracing End-to-End | [eval/scenarios/tracing-end-to-end/](eval/scenarios/tracing-end-to-end/README.md) |
