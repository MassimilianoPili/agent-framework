#!/bin/bash
set -euo pipefail

# sca.sh — Software Composition Analysis (dependency vulnerability scan)
# Usage: ./sca.sh
# Runs OWASP dependency-check and trivy for container images.

# TODO: Run OWASP dependency-check for Maven dependencies
# TODO: Run trivy for Docker image scan
# TODO: Check against quality gates (0 critical, 0 high vulnerabilities)
# TODO: Check license compliance (config/security-policy.yml allowedLicenses)
# TODO: Exit non-zero if thresholds exceeded

echo "TODO: Run SCA scan"
echo "Backend: mvn org.owasp:dependency-check-maven:check"
echo "Docker: trivy image ghcr.io/agent-framework/be-worker:latest"
