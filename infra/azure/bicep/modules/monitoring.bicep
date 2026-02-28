// monitoring.bicep — Log Analytics Workspace + Application Insights
// Wraps appinsights.bicep with a Log Analytics workspace for Container Apps.

@description('Environment name')
param environment string

@description('Azure region')
param location string

var workspaceName = 'law-agentfw-${environment}'

resource logAnalytics 'Microsoft.OperationalInsights/workspaces@2022-10-01' = {
  name: workspaceName
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: 30
    // TODO: Increase retention for prod (90 days)
  }
}

output workspaceId string = logAnalytics.id
output workspaceName string = logAnalytics.name
