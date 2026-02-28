#!/bin/bash
set -euo pipefail

# sbom.sh — Generate Software Bill of Materials
# Usage: ./sbom.sh
# Generates SBOM in CycloneDX format for both BE and FE.

# TODO: Run cyclonedx-maven-plugin for backend
# TODO: Run cyclonedx-npm for frontend
# TODO: Merge SBOMs into a single report
# TODO: Output to build/sbom/ directory

echo "TODO: Generate SBOM"
echo "Backend: mvn org.cyclonedx:cyclonedx-maven-plugin:makeBom"
echo "Frontend: npx @cyclonedx/cyclonedx-npm --output-file build/sbom/fe-sbom.json"
