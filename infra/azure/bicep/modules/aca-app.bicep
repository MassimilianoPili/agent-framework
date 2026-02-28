// Azure Container Apps Environment
// Hosts the orchestrator as a long-running container app.

@description('Resource name')
param name string

@description('Azure region')
param location string

@description('Resource tags')
param tags object

@description('Application Insights connection string')
param appInsightsConnectionString string

resource containerAppsEnv 'Microsoft.App/managedEnvironments@2023-11-02-preview' = {
  name: name
  location: location
  tags: tags
  properties: {
    daprAIConnectionString: appInsightsConnectionString
    zoneRedundant: false
    // TODO: Configure VNet integration for production
    // vnetConfiguration: { ... }
  }
}

output name string = containerAppsEnv.name
output id string = containerAppsEnv.id
