#!/bin/bash
# Build all sandbox Docker images for the execution sandbox.
# These images contain build toolchains (compilers, package managers)
# but NO application code — the workspace is mounted at runtime.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Building sandbox images ==="

echo "[1/7] agent-sandbox-java:21 (Eclipse Temurin 21 + Maven 3.9)"
docker build -t agent-sandbox-java:21 -f Dockerfile.java21 .

echo "[2/7] agent-sandbox-go:1.22 (Go 1.22)"
docker build -t agent-sandbox-go:1.22 -f Dockerfile.go122 .

echo "[3/7] agent-sandbox-node:22 (Node.js 22)"
docker build -t agent-sandbox-node:22 -f Dockerfile.node22 .

echo "[4/7] agent-sandbox-python:3.12 (Python 3.12)"
docker build -t agent-sandbox-python:3.12 -f Dockerfile.python312 .

echo "[5/7] agent-sandbox-rust:latest (Rust + musl)"
docker build -t agent-sandbox-rust:latest -f Dockerfile.rust .

echo "[6/7] agent-sandbox-cpp:latest (GCC 14 + CMake)"
docker build -t agent-sandbox-cpp:latest -f Dockerfile.cpp .

echo "[7/7] agent-sandbox-dotnet:8.0 (.NET SDK 8.0)"
docker build -t agent-sandbox-dotnet:8.0 -f Dockerfile.dotnet8 .

echo ""
echo "=== All sandbox images built ==="
docker images --filter 'reference=agent-sandbox-*' --format 'table {{.Repository}}:{{.Tag}}\t{{.Size}}\t{{.CreatedSince}}'
