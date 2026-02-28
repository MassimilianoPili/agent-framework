// Azure Key Vault
// Stores secrets: API keys, connection strings, certificates.

@description('Resource name')
param name string

@description('Azure region')
param location string

@description('Resource tags')
param tags object

resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 30
    // TODO: Add access policies or RBAC assignments for orchestrator + workers
  }
}

output name string = keyVault.name
output id string = keyVault.id
output uri string = keyVault.properties.vaultUri
