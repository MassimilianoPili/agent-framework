#!/bin/bash
set -euo pipefail

# deploy.sh — Deploy Agent Framework infrastructure to Azure
# Usage: ./deploy.sh <environment> [image-tag]
#
# Environments: develop, test, collaudo, prod

ENVIRONMENT="${1:?Usage: deploy.sh <environment> [image-tag]}"
IMAGE_TAG="${2:-latest}"
RESOURCE_GROUP="rg-agent-framework-${ENVIRONMENT}"
TEMPLATE_FILE="infra/azure/bicep/main.bicep"
PARAMS_FILE="infra/azure/bicep/env/${ENVIRONMENT}.parameters.json"

# TODO: Validate environment name
# TODO: Add confirmation prompt for prod deployments
# TODO: Check Azure CLI login status

echo "Deploying to ${ENVIRONMENT} (image: ${IMAGE_TAG})..."

# TODO: Create resource group if not exists
# az group create --name "$RESOURCE_GROUP" --location italynorth

# TODO: Run Bicep deployment
# az deployment group create \
#   --resource-group "$RESOURCE_GROUP" \
#   --template-file "$TEMPLATE_FILE" \
#   --parameters "$PARAMS_FILE" \
#   --parameters imageTag="$IMAGE_TAG"

echo "TODO: Implement deployment logic"
echo "Template: ${TEMPLATE_FILE}"
echo "Parameters: ${PARAMS_FILE}"
