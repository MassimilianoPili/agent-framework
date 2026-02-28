#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# PreToolUse hook: Block Glob/Grep for domain workers (BE, FE, AI_TASK).
#
# Domain workers should receive their context from CONTEXT_MANAGER via
# the relevant_files allowlist. Direct filesystem exploration (Glob/Grep)
# is reserved for manager workers (CM, SM, HM) that produce structured
# context for downstream tasks.
#
# This is a defense-in-depth layer that mirrors the ToolAllowlist.Explicit
# policy enforced at the Java/Spring AI level. Both layers must be consistent.
#
# Exit codes:
#   0 — allowed (human dev mode, or manager/review worker type)
#   2 — blocked (domain worker attempting Glob or Grep)
# ──────────────────────────────────────────────────────────────────────
set -uo pipefail

INPUT=$(cat)
WORKER_TYPE="${AGENT_WORKER_TYPE:-}"

# If no worker type is set, this is a human developer session — allow everything
if [[ -z "$WORKER_TYPE" ]]; then
    exit 0
fi

# Domain workers: not allowed to explore the repo directly
DOMAIN_WORKERS=("BE" "FE" "AI_TASK")
for wt in "${DOMAIN_WORKERS[@]}"; do
    if [[ "$WORKER_TYPE" == "$wt" ]]; then
        TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // "unknown"')
        echo "Tool '${TOOL_NAME}' is not allowed for domain worker type '${WORKER_TYPE}'." \
             "Use the relevant_files context provided by CONTEXT_MANAGER instead of exploring the repository." >&2
        exit 2
    fi
done

exit 0
