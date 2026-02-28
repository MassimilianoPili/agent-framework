// container-apps-env.bicep — Azure Container Apps Managed Environment

@description('Environment name')
param environment string

@description('Azure region')
param location string

@description('Log Analytics workspace resource ID')
param logAnalyticsWorkspaceId string

var envName = 'cae-agent-framework-${environment}'

resource containerAppsEnv 'Microsoft.App/managedEnvironments@2023-05-01' = {
  name: envName
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: reference(logAnalyticsWorkspaceId, '2022-10-01').customerId
        // TODO: Add shared key from Log Analytics workspace
      }
    }
  }
  // TODO: Configure VNet integration, zone redundancy for prod
}

@description('Container Apps Environment resource ID')
output envId string = containerAppsEnv.id
