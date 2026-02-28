#!/bin/bash
set -euo pipefail

# compute-coverage.sh — Compute and report test coverage
# Usage: ./compute-coverage.sh
# Parses JaCoCo XML (BE) and c8 JSON (FE) coverage reports.

# TODO: Find JaCoCo report (backend/target/site/jacoco/jacoco.xml)
# TODO: Find c8 report (frontend/coverage/coverage-summary.json)
# TODO: Extract line coverage percentage
# TODO: Compare against threshold in config/quality-gates.yml (80%)
# TODO: Output coverage summary as JSON
# TODO: Exit non-zero if below threshold

echo "TODO: Implement coverage computation"
echo "Threshold: 80% (see config/quality-gates.yml)"
