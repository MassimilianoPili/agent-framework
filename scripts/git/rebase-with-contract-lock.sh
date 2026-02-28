#!/bin/bash
# ──────────────────────────────────────────────────────────────────────
# Rebase current branch on develop, with contract file conflict detection.
#
# If contracts/ files conflict, abort rebase and notify — contract
# changes require human review (breaking change risk).
#
# Usage: ./rebase-with-contract-lock.sh
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

git fetch origin develop

echo "Rebasing on origin/develop..."
if ! git rebase origin/develop; then
  # Check if contract files are in conflict
  CONFLICTS=$(git diff --name-only --diff-filter=U 2>/dev/null || true)
  CONTRACT_CONFLICTS=$(echo "$CONFLICTS" | grep '^contracts/' || true)

  if [[ -n "$CONTRACT_CONFLICTS" ]]; then
    echo "ERROR: Contract files in conflict — requires human review:"
    echo "$CONTRACT_CONFLICTS"
    echo ""
    echo "Aborting rebase. Resolve contract conflicts manually."
    git rebase --abort
    exit 2
  fi

  echo "Non-contract merge conflicts detected. Resolve and continue:"
  echo "$CONFLICTS"
  exit 1
fi

echo "Rebase complete."
