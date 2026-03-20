#!/bin/bash
# ──────────────────────────────────────────────────────────────────────
# PostToolUse hook: Unified audit log for ALL tool calls (Bash, MCP,
# Read, Edit, Write, Glob, Grep, etc.).
#
# Writes structured JSONL to .claude/audit.jsonl with:
#   - timestamp (UTC ISO 8601)
#   - session ID
#   - tool name and category (builtin/mcp/bash)
#   - worker type and task key
#   - tool input summary (truncated for safety)
#   - outcome (from tool_output if available)
#
# Forwards events async to AuditManagerService when running.
#
# Exit codes:
#   0 — always (audit logging must never block tool execution)
# ──────────────────────────────────────────────────────────────────────
set -uo pipefail

INPUT=$(cat)
TOOL=$(echo "$INPUT" | jq -r '.tool_name // "unknown"')
SESSION=$(echo "$INPUT" | jq -r '.session_id // "unknown"')
TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
TASK_KEY="${AGENT_TASK_KEY:-}"
WORKER_TYPE="${AGENT_WORKER_TYPE:-human}"

# Categorize the tool
if [[ "$TOOL" == mcp__* ]]; then
    CATEGORY="mcp"
elif [[ "$TOOL" == "Bash" ]]; then
    CATEGORY="bash"
else
    CATEGORY="builtin"
fi

# Extract a safe summary of tool input (truncated to 200 chars, no secrets)
INPUT_SUMMARY=$(echo "$INPUT" | jq -r '
    .tool_input |
    if .command then .command
    elif .file_path then .file_path
    elif .pattern then .pattern
    elif .query then .query
    else (tostring | .[0:200])
    end // ""' 2>/dev/null | head -c 200)

# Extract outcome if available (tool_output status or truncated result)
OUTCOME=$(echo "$INPUT" | jq -r '.tool_output.status // "completed"' 2>/dev/null)

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
AUDIT_FILE="$PROJECT_DIR/.claude/audit.jsonl"

mkdir -p "$(dirname "$AUDIT_FILE")"

# Build JSON event with jq for proper escaping
EVENT=$(jq -n \
    --arg ts "$TIMESTAMP" \
    --arg session "$SESSION" \
    --arg tool "$TOOL" \
    --arg category "$CATEGORY" \
    --arg worker "$WORKER_TYPE" \
    --arg taskKey "$TASK_KEY" \
    --arg input "$INPUT_SUMMARY" \
    --arg outcome "$OUTCOME" \
    '{ts: $ts, session: $session, tool: $tool, category: $category, worker: $worker, taskKey: $taskKey, input: $input, outcome: $outcome}')

echo "$EVENT" >> "$AUDIT_FILE" 2>/dev/null || true

# Forward to AuditManagerService (async, fire-and-forget)
AUDIT_MANAGER_PORT="${AUDIT_MANAGER_PORT:-8093}"
if command -v curl >/dev/null 2>&1; then
    curl -s -X POST "http://localhost:${AUDIT_MANAGER_PORT}/audit/events" \
         -H "Content-Type: application/json" \
         -d "$EVENT" \
         --max-time 2 \
         -o /dev/null 2>/dev/null &
fi

exit 0