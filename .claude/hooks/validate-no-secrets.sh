#!/bin/bash
# ──────────────────────────────────────────────────────────────────────
# Stop hook: Verify no secrets are staged for commit.
#
# Runs at session end to check if any staged files contain patterns
# that look like secrets (API keys, credentials, certificates).
#
# Exit codes:
#   0 — no secrets detected
#   2 — potential secrets found (warns but does NOT block session end,
#       since Stop hooks serve as warnings, not hard blocks)
# ──────────────────────────────────────────────────────────────────────
set -uo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
ISSUES=()

# Check 1: Staged files with suspicious extensions
if command -v git &>/dev/null && git -C "$PROJECT_DIR" rev-parse --git-dir &>/dev/null; then
  STAGED_FILES=$(git -C "$PROJECT_DIR" diff --cached --name-only 2>/dev/null || true)

  if [[ -n "$STAGED_FILES" ]]; then
    # Check for files with secret-like extensions
    SUSPICIOUS_EXTS=$(echo "$STAGED_FILES" | grep -Ei '\.(env|key|pem|p12|pfx|credentials|secret)$' || true)
    if [[ -n "$SUSPICIOUS_EXTS" ]]; then
      ISSUES+=("Staged files with suspicious extensions: $SUSPICIOUS_EXTS")
    fi

    # Check staged content for high-entropy patterns (API keys, tokens)
    for file in $STAGED_FILES; do
      FULL_PATH="$PROJECT_DIR/$file"
      if [[ -f "$FULL_PATH" ]]; then
        # Check for common secret patterns in file content
        SECRETS_FOUND=$(grep -EnHi \
          '(api[_-]?key|secret[_-]?key|access[_-]?token|private[_-]?key|password)\s*[:=]\s*["\x27]?[A-Za-z0-9+/=_-]{20,}' \
          "$FULL_PATH" 2>/dev/null | head -5 || true)
        if [[ -n "$SECRETS_FOUND" ]]; then
          ISSUES+=("Potential secrets in $file: $(echo "$SECRETS_FOUND" | head -1)")
        fi

        # Check for AWS-style keys (AKIA...)
        AWS_KEYS=$(grep -Pn 'AKIA[0-9A-Z]{16}' "$FULL_PATH" 2>/dev/null || true)
        if [[ -n "$AWS_KEYS" ]]; then
          ISSUES+=("AWS access key found in $file")
        fi

        # Check for Azure connection strings
        AZURE_CONN=$(grep -ni 'Endpoint=sb://' "$FULL_PATH" 2>/dev/null || true)
        if [[ -n "$AZURE_CONN" ]]; then
          ISSUES+=("Azure Service Bus connection string found in $file")
        fi
      fi
    done
  fi
fi

# Report findings
if [[ ${#ISSUES[@]} -gt 0 ]]; then
  echo "WARNING: Potential secrets detected in staged files:" >&2
  for issue in "${ISSUES[@]}"; do
    echo "  - $issue" >&2
  done
  echo "" >&2
  echo "Review staged changes before committing. Use 'git diff --cached' to inspect." >&2
  exit 2
fi

exit 0
