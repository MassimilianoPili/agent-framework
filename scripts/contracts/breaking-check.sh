#!/bin/bash
set -euo pipefail

# breaking-check.sh — Detect breaking API changes using oasdiff
# Usage: ./breaking-check.sh [base-ref]
# Requires: oasdiff binary on PATH

BASE_REF="${1:-origin/develop}"

# TODO: Extract base spec from base ref (git show)
# TODO: Compare with current spec using oasdiff
# TODO: Apply rules from contracts/openapi/rules/breaking-change.yml
# TODO: Report breaking changes
# TODO: Exit non-zero if breaking changes found

echo "TODO: Run oasdiff breaking change detection"
echo "oasdiff breaking --base <(git show ${BASE_REF}:contracts/openapi/v1.yaml) --revision contracts/openapi/v1.yaml"
