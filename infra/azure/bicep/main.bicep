// ──────────────────────────────────────────────────────────────────────
// Agent Framework — Main Bicep Deployment
//
// Deploys all Azure resources for the Agent Framework:
//   - Azure Container Apps Environment (orchestrator + workers)
//   - Azure Service Bus (topics + subscriptions)
//   - Azure Key Vault (secrets)
//   - PostgreSQL Flexible Server (plan storage)
//   - Application Insights (observability)
//   - Azure OpenAI (optional, for Claude proxy)
//
// Usage:
//   az deployment group create \
//     --resource-group rg-agent-framework-dev \
//     --template-file infra/azure/bicep/main.bicep \
//     --parameters infra/azure/bicep/env/develop.parameters.json
// ──────────────────────────────────────────────────────────────────────

targetScope = 'resourceGroup'

@description('Environment name (develop, test, collaudo, prod)')
param environment string

@description('Azure region for all resources')
param location string = resourceGroup().location

@description('Project name prefix for resource naming')
param projectName string = 'agentfw'

// ── Shared tags ──
var tags = {
  project: 'agent-framework'
  environment: environment
  managedBy: 'bicep'
}

// ── Modules ──

module appInsights 'modules/appinsights.bicep' = {
  name: 'appinsights-${environment}'
  params: {
    name: '${projectName}-ai-${environment}'
    location: location
    tags: tags
  }
}

module keyVault 'modules/keyvault.bicep' = {
  name: 'keyvault-${environment}'
  params: {
    name: '${projectName}-kv-${environment}'
    location: location
    tags: tags
  }
}

module postgres 'modules/postgres.bicep' = {
  name: 'postgres-${environment}'
  params: {
    name: '${projectName}-pg-${environment}'
    location: location
    tags: tags
  }
}

module serviceBus 'modules/servicebus.bicep' = {
  name: 'servicebus-${environment}'
  params: {
    name: '${projectName}-sb-${environment}'
    location: location
    tags: tags
  }
}

module containerAppsEnv 'modules/aca-app.bicep' = {
  name: 'aca-env-${environment}'
  params: {
    name: '${projectName}-aca-${environment}'
    location: location
    tags: tags
    appInsightsConnectionString: appInsights.outputs.connectionString
  }
}

// TODO: Add aca-jobs.bicep module for worker jobs
// module workerJobs 'modules/aca-jobs.bicep' = { ... }

// TODO: Add aoai.bicep module for Azure OpenAI (optional)
// module openAI 'modules/aoai.bicep' = { ... }

// ── Outputs ──
output appInsightsName string = appInsights.outputs.name
output keyVaultName string = keyVault.outputs.name
output postgresName string = postgres.outputs.name
output serviceBusName string = serviceBus.outputs.name
output containerAppsEnvName string = containerAppsEnv.outputs.name
