#!/bin/bash
# ──────────────────────────────────────────────────────────────────────
# Create an agent branch following the naming convention.
#
# Usage: ./create-branch.sh <planId> <taskKey>
# Example: ./create-branch.sh abc-123 BE-001
# Creates: agent/abc-123/BE-001
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

PLAN_ID="${1:?Usage: $0 <planId> <taskKey>}"
TASK_KEY="${2:?Usage: $0 <planId> <taskKey>}"

BRANCH_NAME="agent/${PLAN_ID}/${TASK_KEY}"

echo "Creating branch: $BRANCH_NAME from develop"
git fetch origin develop
git checkout -b "$BRANCH_NAME" origin/develop
echo "Branch '$BRANCH_NAME' created. Ready to work."
