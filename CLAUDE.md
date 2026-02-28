# Agent Framework

Multi-agent orchestration framework for AI-driven code generation from natural language specifications.

## Architecture

Three-plane architecture:
- **Control Plane** (`control-plane/orchestrator/`): Spring Boot 3.4.1 — REST API, planner (Claude), state DB, Service Bus messaging
- **Execution Plane** (`execution-plane/workers/`): Specialized AI workers (BE, FE, AI-task, contract, review) using worker-sdk
- **MCP Layer** (`mcp/`): Tool access control with deny-all baseline + per-workerType allowlist
- **Hooks** (`.claude/hooks/`): Deterministic enforcement — PreToolUse blocks, audit logging, secret validation

## Build

```bash
mvn clean install -DskipTests
```

## Run Orchestrator (dev)

```bash
cd control-plane/orchestrator
docker compose -f docker/docker-compose.dev.yml up -d postgres
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## Required Environment Variables

| Variable | Description |
|----------|-------------|
| `ANTHROPIC_API_KEY` | Anthropic API key for Claude |
| `AZURE_SERVICEBUS_CONNECTION_STRING` | Azure Service Bus connection string |
| `DB_USER` | PostgreSQL username |
| `DB_PASSWORD` | PostgreSQL password |
| `DB_HOST` | PostgreSQL host (default: localhost) |

## Test End-to-End

```bash
curl -X POST http://localhost:8080/api/v1/plans \
  -H "Content-Type: application/json" \
  -d '{"spec":"Build a REST API for user management with JWT authentication"}'
```

## Project Layout

| Directory | Purpose |
|-----------|---------|
| `agents/` | Agent profile definitions (*.agent.md) |
| `prompts/` | Core prompt templates (*.prompt.md) |
| `skills/` | Reusable skill documents |
| `templates/` | Code templates (BE/FE/infra) |
| `patterns/` | Architectural pattern docs |
| `contracts/` | JSON Schemas, OpenAPI, events |
| `config/` | Framework configuration (YAML) |
| `control-plane/` | Orchestrator (Spring Boot) |
| `execution-plane/` | Worker SDK + specialized workers |
| `mcp/` | MCP registry, policies, schemas |
| `.claude/hooks/` | Deterministic enforcement hooks |

## Key Conventions

- **Java 17**, Spring Boot 3.4.1, Spring AI 1.0.0
- **Package root**: `com.agentframework`
- **AutoConfiguration pattern**: `@AutoConfiguration` + `@Import` (like mcp-devops-tools)
- **Tool registration**: Automatic via `@ReactiveTool` scan by `ReactiveToolAutoConfiguration`
- **Service Bus**: One topic per worker type (`agent-tasks-{be,fe,ai-task,contract,review}`)
- **Hooks enforcement**: `AGENT_WORKER_TYPE` env var determines per-worker policy
