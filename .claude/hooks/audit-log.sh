#!/bin/bash
# ──────────────────────────────────────────────────────────────────────
# PostToolUse hook: Append audit log entry for every tool call.
#
# Writes a JSON Lines (JSONL) entry to .claude/audit.jsonl with:
#   - timestamp (UTC ISO 8601)
#   - session ID
#   - tool name
#   - worker type (from AGENT_WORKER_TYPE env var)
#
# The audit log enables:
#   - Post-mortem analysis of agent sessions
#   - Security reviews of tool usage patterns
#   - Compliance tracking for production deployments
#
# Exit codes:
#   0 — always (audit logging should never block tool execution)
# ──────────────────────────────────────────────────────────────────────
set -uo pipefail

INPUT=$(cat)
TOOL=$(echo "$INPUT" | jq -r '.tool_name // "unknown"')
SESSION=$(echo "$INPUT" | jq -r '.session_id // "unknown"')
TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
TASK_KEY="${AGENT_TASK_KEY:-}"
WORKER_TYPE="${AGENT_WORKER_TYPE:-human}"

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
AUDIT_FILE="$PROJECT_DIR/.claude/audit.jsonl"

# Ensure parent directory exists
mkdir -p "$(dirname "$AUDIT_FILE")"

EVENT="{\"ts\":\"$TIMESTAMP\",\"session\":\"$SESSION\",\"tool\":\"$TOOL\",\"worker\":\"$WORKER_TYPE\",\"taskKey\":\"$TASK_KEY\"}"

# Append audit entry locally (JSON Lines format — one JSON object per line)
echo "$EVENT" >> "$AUDIT_FILE" 2>/dev/null || true

# Forward event to AuditManagerService if running (async, fire-and-forget)
AUDIT_MANAGER_PORT="${AUDIT_MANAGER_PORT:-8093}"
if command -v curl >/dev/null 2>&1; then
    curl -s -X POST "http://localhost:${AUDIT_MANAGER_PORT}/audit/events" \
         -H "Content-Type: application/json" \
         -d "$EVENT" \
         --max-time 2 \
         -o /dev/null 2>/dev/null &
fi

# Always exit 0 — audit logging must never block tool execution
exit 0