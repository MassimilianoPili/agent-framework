#!/bin/bash
set -euo pipefail

# generate-fe-client.sh — Generate TypeScript API client from OpenAPI spec
# Usage: ./generate-fe-client.sh
# Requires: npx openapi-typescript or similar codegen tool

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SPEC="$PROJECT_ROOT/contracts/openapi/v1.yaml"
OUTPUT_DIR="$PROJECT_ROOT/frontend/src/api/generated"

# TODO: Validate spec exists
# TODO: Run OpenAPI codegen (openapi-typescript, orval, or openapi-generator)
# TODO: Write generated types to frontend/src/api/generated/
# TODO: Format generated code with prettier

echo "TODO: Generate TypeScript client from $SPEC to $OUTPUT_DIR"
