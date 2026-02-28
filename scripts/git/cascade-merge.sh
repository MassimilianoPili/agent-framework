#!/bin/bash
# ──────────────────────────────────────────────────────────────────────
# Cascade merge: main → develop → test
#
# Usage: ./cascade-merge.sh
# Merges the latest main into develop, then develop into test.
# Aborts on merge conflicts with instructions.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

echo "=== Cascading: main → develop → test ==="

git fetch origin

echo "--- Merging main → develop ---"
git checkout develop
git merge origin/main --no-edit || {
  echo "ERROR: Merge conflict merging main → develop. Resolve manually."
  exit 1
}
git push origin develop

echo "--- Merging develop → test ---"
git checkout test
git merge origin/develop --no-edit || {
  echo "ERROR: Merge conflict merging develop → test. Resolve manually."
  exit 1
}
git push origin test

echo "=== Cascade complete ==="
