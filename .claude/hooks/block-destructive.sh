#!/bin/bash
# ──────────────────────────────────────────────────────────────────────
# PreToolUse hook: Block destructive Bash commands.
#
# Inspects the command about to be executed and blocks patterns that
# are dangerous in an agent context: force-push, hard reset, rm -rf,
# DROP TABLE, TRUNCATE, etc.
#
# Exit codes:
#   0 — allowed
#   2 — blocked
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

INPUT=$(cat)
CMD=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

# If no command extracted, allow
if [[ -z "$CMD" ]]; then
  exit 0
fi

# Destructive patterns (case-insensitive)
DESTRUCTIVE_PATTERNS=(
  'rm\s+-rf\s+/'                   # rm -rf with absolute path
  'rm\s+-rf\s+\.'                  # rm -rf with relative path starting with .
  'git\s+push\s+.*--force'         # git push --force
  'git\s+push\s+.*-f\b'           # git push -f
  'git\s+reset\s+--hard'          # git reset --hard
  'git\s+clean\s+-fd'             # git clean -fd
  'DROP\s+TABLE'                   # SQL DROP TABLE
  'DROP\s+DATABASE'                # SQL DROP DATABASE
  'TRUNCATE\s+TABLE'               # SQL TRUNCATE
  'DROP\s+SCHEMA'                  # SQL DROP SCHEMA
  'mkfs\.'                         # Format filesystem
  'dd\s+if=.*of=/dev/'            # dd to device
  'chmod\s+-R\s+777'              # Insecure permissions
  ':(){.*};:'                      # Fork bomb
  'curl.*\|\s*bash'               # Pipe curl to bash
  'wget.*\|\s*bash'               # Pipe wget to bash
  'eval\s+.*\$\('                 # eval with command substitution
)

for pattern in "${DESTRUCTIVE_PATTERNS[@]}"; do
  if echo "$CMD" | grep -qEi "$pattern"; then
    echo "BLOCKED: Destructive command detected matching pattern '$pattern'" >&2
    echo "Command was: $CMD" >&2
    exit 2
  fi
done

exit 0