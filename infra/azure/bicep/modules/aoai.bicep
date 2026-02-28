// Azure OpenAI Service (optional)
// For organizations using Azure OpenAI instead of direct Anthropic API.
// This module is a placeholder — the default setup uses Anthropic directly.

@description('Resource name')
param name string

@description('Azure region')
param location string

@description('Resource tags')
param tags object

// TODO: Uncomment when Azure OpenAI is needed
// resource openAI 'Microsoft.CognitiveServices/accounts@2023-10-01-preview' = {
//   name: name
//   location: location
//   tags: tags
//   kind: 'OpenAI'
//   sku: {
//     name: 'S0'
//   }
//   properties: {
//     customSubDomainName: name
//     publicNetworkAccess: 'Enabled'
//   }
// }

output name string = name
