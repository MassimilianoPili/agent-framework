# MCP Usage Guide

## Overview

The Model Context Protocol (MCP) layer provides controlled tool access for AI workers in the
agent framework. Each worker type has a defined set of allowed tools, enforced by the
`enforce-mcp-allowlist.sh` hook at the Claude Code level and by the orchestrator at dispatch time.

## Architecture

Workers can consume tools in two modes, selectable at deploy time via Spring Boot profiles:

### Mode A: In-Process (default)

Tool libraries are embedded in the worker JVM as Maven dependencies.
This is the default behavior when no `mcp` profile is active.

```
Worker JVM
  |
  +-- @ReactiveTool beans (classpath scan)
  |       mcp-devops-tools, mcp-filesystem-tools, mcp-sql-tools, ...
  |
  +-- ReactiveToolAutoConfiguration -> ToolCallbackProvider beans
  |
  +-- WorkerChatClientFactory
  |       allowlist filter -> PolicyEnforcingToolCallback wrapper
  |
  v
ChatClient -> Claude -> tool calls -> in-process execution -> results
```

### Mode B: External MCP Server (profile `mcp`)

Workers connect to external MCP server(s) via SSE transport.
Activated with `SPRING_PROFILES_ACTIVE=mcp`.

```
Worker JVM                              External MCP Server
  |                                       |
  +-- spring-ai-starter-mcp-client        +-- @ReactiveTool beans
  |       SSE connection ----------------->      (all tools)
  |       SyncMcpToolCallbackProvider     |
  |                                       +-- /sse endpoint
  +-- WorkerChatClientFactory (unchanged)
  |       allowlist filter -> PolicyEnforcingToolCallback wrapper
  |
  v
ChatClient -> Claude -> tool calls -> SSE -> MCP server -> results
```

### Mode C: Hybrid (profile `mcp` + partial in-process)

Some tools come from external MCP servers, others remain in-process.
The compiler generates `application-mcp.yml` with `spring.autoconfigure.exclude`
entries only for packages whose ALL servers are covered by MCP connections.

```
Worker JVM                              External MCP Server
  |                                       |
  +-- In-process tools (e.g., sql)        +-- MCP tools (e.g., git, repo-fs)
  |       ToolCallbackProvider            |       via SSE
  |                                       |
  +-- SyncMcpToolCallbackProvider -------->
  |
  +-- WorkerChatClientFactory (both providers merged, zero duplicates)
  |
  v
ChatClient -> Claude -> tool calls -> in-process OR SSE -> results
```

### Hook enforcement (all modes)

```
Claude Code (with hooks)
  |-- PreToolUse: enforce-mcp-allowlist.sh
  |       checks AGENT_WORKER_TYPE against mcp/policies/allowlists/*.yml
  |
  v
Tool execution
  |-- Sandbox limits (mcp/policies/sandbox/limits.yml)
  |-- Output redaction (mcp/policies/redaction/redaction-rules.yml)
  |
  v
Tool Result (redacted) -> returned to LLM context
```

## Available MCP Servers

| Server | Package | Primary Users |
|--------|---------|---------------|
| `git` | `io.github.massimilianopili:mcp-devops-tools` | All workers except review |
| `repo-fs` | `io.github.massimilianopili:mcp-filesystem-tools` | All workers |
| `openapi` | `io.github.massimilianopili:mcp-devops-tools` | contract-worker, review-worker |
| `azure` | `io.github.massimilianopili:mcp-devops-tools` | ai-task-worker |
| `test` | `io.github.massimilianopili:mcp-devops-tools` | be-worker, fe-worker |

## Activating External MCP Mode

1. Deploy an MCP server with SSE transport (e.g., the existing `mcp-server` at `/data/massimiliano/Vari/mcp/`)
2. Set `SPRING_PROFILES_ACTIVE=mcp` on the worker container
3. Override MCP server URLs via environment variables:
   - `MCP_GIT_URL` (default: `http://mcp-server:8080`)
   - `MCP_REPO_FS_URL` (default: `http://mcp-server:8080`)
   - `MCP_OPENAPI_URL`, `MCP_AZURE_URL`, `MCP_TEST_URL`
4. The worker will connect to the MCP server(s) at startup and discover tools via the MCP protocol

### Dedup behavior

When the `mcp` profile is active, the generated `application-mcp.yml` excludes in-process
auto-configurations for packages fully covered by MCP connections. For example, if a worker
declares `mcpServers: [git, openapi, test]` and all three map to `mcp-devops-tools`, then
`DevOpsToolsAutoConfiguration` is excluded — those tools come from the MCP server instead.

Tools from packages NOT fully covered (e.g., `mcp-sql-tools` with no MCP server) remain in-process.

## Adding a New MCP Server

1. Add the server entry to `mcp/registry/mcp-registry.yml` (including `connections` and `autoConfiguration`)
2. Create an allowlist in `mcp/policies/allowlists/<server-name>.yml`
3. Create a JSON Schema for tool I/O in `mcp/schemas/tool-io/<server-name>.schema.json`
4. Update `config/agent-registry.yml` to add the server to the relevant workers' `mcpAllowlist`
5. Update `enforce-mcp-allowlist.sh` if the server requires special validation

## Adding a Tool to an Existing Server

1. Add the operation to `mcp/policies/allowlists/<server-name>.yml`
2. Add input/output definitions to `mcp/schemas/tool-io/<server-name>.schema.json`
3. Implement the tool in the corresponding Maven module

## Policy Enforcement

- **Deny-all baseline**: Any tool not explicitly listed in the allowlist is denied
- **Path ownership**: `repo-fs` write operations check `config/repo-layout.yml`
- **Redaction**: All tool outputs are scanned against `redaction-rules.yml` before returning to the LLM
- **Sandbox limits**: File sizes, recursion depth, and timeouts are enforced per `limits.yml`
- **Audit**: Every tool invocation is logged to `.claude/audit.jsonl`

## Troubleshooting

**Tool denied unexpectedly**: Check that the worker type in `AGENT_WORKER_TYPE` matches the
`allowedWorkers` list in the corresponding allowlist file.

**Output truncated**: The sandbox limit `maxOutputBytes` (default 512 KB) may be exceeded.
Check `mcp/policies/sandbox/limits.yml` for per-server overrides.

**Redaction too aggressive**: Review `mcp/policies/redaction/redaction-rules.yml` and check
the `excludeFiles` patterns for the relevant rule.
