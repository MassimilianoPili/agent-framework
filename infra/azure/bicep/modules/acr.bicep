// acr.bicep — Azure Container Registry

@description('Environment name')
param environment string

@description('Azure region')
param location string

var acrName = 'acragentfw${environment}'

resource containerRegistry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: acrName
  location: location
  sku: {
    name: 'Basic'
    // TODO: Upgrade to Standard for prod (geo-replication, webhooks)
  }
  properties: {
    adminUserEnabled: false
    // TODO: Configure managed identity pull for Container Apps
  }
}

@description('ACR login server')
output loginServer string = containerRegistry.properties.loginServer

@description('ACR resource ID')
output acrId string = containerRegistry.id
