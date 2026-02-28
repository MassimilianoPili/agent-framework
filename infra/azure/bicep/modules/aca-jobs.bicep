// Azure Container Apps Jobs
// Each worker runs as an event-driven job triggered by Service Bus messages.

@description('Resource name prefix')
param namePrefix string

@description('Azure region')
param location string

@description('Resource tags')
param tags object

@description('Container Apps Environment ID')
param containerAppsEnvId string

@description('Service Bus connection string')
@secure()
param serviceBusConnectionString string

// Worker definitions — all task workers use the unified agent-tasks topic,
// routed by workerType SQL filter on the Azure Service Bus subscription.
var workers = [
  { name: 'be-worker',       topic: 'agent-tasks',   subscription: 'be-worker',       workerType: 'BE' }
  { name: 'fe-worker',       topic: 'agent-tasks',   subscription: 'fe-worker',       workerType: 'FE' }
  { name: 'ai-task-worker',  topic: 'agent-tasks',   subscription: 'ai-task-worker',  workerType: 'AI_TASK' }
  { name: 'contract-worker', topic: 'agent-tasks',   subscription: 'contract-worker', workerType: 'CONTRACT' }
  { name: 'review-worker',   topic: 'agent-reviews', subscription: 'review-worker',   workerType: 'REVIEW' }
]

// TODO: Create Container Apps Jobs for each worker
// resource workerJob 'Microsoft.App/jobs@2023-11-02-preview' = [for worker in workers: {
//   name: '${namePrefix}-${worker.name}'
//   location: location
//   tags: tags
//   properties: {
//     environmentId: containerAppsEnvId
//     configuration: {
//       triggerType: 'Event'
//       replicaTimeout: 600
//       eventTriggerConfig: {
//         scale: {
//           rules: [{
//             name: 'servicebus-trigger'
//             type: 'azure-servicebus'
//             metadata: {
//               topicName: worker.topic
//               subscriptionName: worker.subscription
//               messageCount: '1'
//             }
//             auth: [{
//               secretRef: 'sb-connection'
//               triggerParameter: 'connection'
//             }]
//           }]
//         }
//       }
//     }
//     template: {
//       containers: [{
//         name: worker.name
//         image: 'ghcr.io/agent-framework/${worker.name}:latest'
//         env: [
//           { name: 'AGENT_WORKER_TYPE', value: worker.workerType }
//         ]
//         resources: {
//           cpu: json('0.5')
//           memory: '1Gi'
//         }
//       }]
//     }
//   }
// }]
