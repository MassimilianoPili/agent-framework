#!/bin/bash
set -euo pipefail

# sast.sh — Static Application Security Testing
# Usage: ./sast.sh
# Runs semgrep with project-specific rules.

# TODO: Install semgrep if not present
# TODO: Run semgrep with --config auto and project rules
# TODO: Parse results and check against quality gates
# TODO: Exit non-zero if critical/high findings

echo "TODO: Run SAST scan"
echo "semgrep scan --config auto --json --output build/sast-report.json"
