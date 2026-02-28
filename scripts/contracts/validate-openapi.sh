#!/bin/bash
set -euo pipefail

# validate-openapi.sh — Run Spectral lint on all OpenAPI specs
# Usage: ./validate-openapi.sh
# Requires: npx spectral (installed via npm)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RULESET="$PROJECT_ROOT/contracts/openapi/rules/openapi-style.yml"

# TODO: Find all OpenAPI spec files
# TODO: Run spectral lint with project ruleset
# TODO: Report errors and warnings
# TODO: Exit non-zero if errors found

echo "TODO: Run spectral lint"
echo "npx @stoplight/spectral-cli lint contracts/openapi/v1.yaml --ruleset $RULESET"
