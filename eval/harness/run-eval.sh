#!/bin/bash
set -euo pipefail

# run-eval.sh — Run evaluation scenarios against golden datasets
# Usage: ./run-eval.sh [scenario-name]
# If no scenario specified, runs all scenarios.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCENARIOS_DIR="$PROJECT_ROOT/eval/scenarios"
GOLDEN_DIR="$PROJECT_ROOT/eval/golden"

SCENARIO="${1:-all}"

# TODO: Start local orchestrator if not running
# TODO: For each scenario:
#   1. Read scenario README.md for expected behavior
#   2. Submit the plan spec to the orchestrator API
#   3. Wait for plan completion (poll /api/v1/plans/{id})
#   4. Compare results against golden dataset
#   5. Validate schemas with validate-schema.sh
#   6. Report pass/fail

if [[ "$SCENARIO" == "all" ]]; then
  echo "Running all scenarios..."
  for dir in "$SCENARIOS_DIR"/*/; do
    name=$(basename "$dir")
    echo "=== Scenario: $name ==="
    echo "TODO: Execute scenario from $dir"
  done
else
  echo "Running scenario: $SCENARIO"
  echo "TODO: Execute scenario from $SCENARIOS_DIR/$SCENARIO/"
fi

echo ""
echo "TODO: Implement eval harness"
echo "See eval/scenarios/ for scenario definitions"
echo "See eval/golden/ for golden datasets"
