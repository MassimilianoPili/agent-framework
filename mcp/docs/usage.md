# MCP Usage Guide

## Overview

The Model Context Protocol (MCP) layer provides controlled tool access for AI workers in the
agent framework. Each worker type has a defined set of allowed tools, enforced by the
`enforce-mcp-allowlist.sh` hook at the Claude Code level and by the orchestrator at dispatch time.

## Architecture

```
Worker Session
  |
  v
Claude Code (with hooks)
  |-- PreToolUse: enforce-mcp-allowlist.sh
  |       checks AGENT_WORKER_TYPE against mcp/policies/allowlists/*.yml
  |
  v
MCP Server (stdio transport)
  |-- Tool execution with sandbox limits (mcp/policies/sandbox/limits.yml)
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

## Adding a New MCP Server

1. Add the server entry to `mcp/registry/mcp-registry.yml`
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
