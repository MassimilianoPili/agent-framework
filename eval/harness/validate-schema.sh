#!/bin/bash
set -euo pipefail

# validate-schema.sh — Validate JSON files against JSON Schemas
# Usage: ./validate-schema.sh <json-file> <schema-file>
# Requires: npx ajv-cli

JSON_FILE="${1:?Usage: validate-schema.sh <json-file> <schema-file>}"
SCHEMA_FILE="${2:?Provide a JSON Schema file}"

# TODO: Validate inputs exist
# TODO: Run ajv validation
# TODO: Report validation errors
# TODO: Exit non-zero if validation fails

echo "TODO: Validate $JSON_FILE against $SCHEMA_FILE"
echo "npx ajv validate -s $SCHEMA_FILE -d $JSON_FILE --strict=false"
