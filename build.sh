#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# build.sh — Single-command build for the Agent Framework
#
# Solves the Maven chicken-and-egg problem: worker modules are code-generated
# from agents/manifests/*.agent.yml, but Maven resolves <modules> before
# running any plugin. This script generates first (-N = non-recursive),
# then triggers the full reactor build.
#
# Usage:
#   ./build.sh                  # full build
#   ./build.sh -DskipTests      # skip tests
#   ./build.sh -pl control-plane/orchestrator -am   # build specific module
#
# For subsequent builds (when worker dirs already exist on disk), you can also:
#   mvn install -DskipTests     # pom.xml plugin binding handles generation
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")"

echo "═══ Step 1/2: Generating worker modules + registry ═══"
mvn -N \
    com.agentframework:agent-compiler-maven-plugin:1.1.0-SNAPSHOT:validate-manifests \
    com.agentframework:agent-compiler-maven-plugin:1.1.0-SNAPSHOT:generate-workers \
    com.agentframework:agent-compiler-maven-plugin:1.1.0-SNAPSHOT:generate-registry \
    -q

echo "═══ Step 2/2: Building reactor ═══"
mvn install "$@"
