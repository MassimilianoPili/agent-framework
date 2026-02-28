// Azure Service Bus Namespace + Topics + Subscriptions
// Topology matches contracts/events/topics.yml

@description('Resource name')
param name string

@description('Azure region')
param location string

@description('Resource tags')
param tags object

resource serviceBusNamespace 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' = {
  name: name
  location: location
  tags: tags
  sku: {
    name: 'Standard'
    tier: 'Standard'
  }
}

// Unified task topic — all worker types share this topic, routed by SQL filter on workerType property
resource agentTasksTopic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' = {
  parent: serviceBusNamespace
  name: 'agent-tasks'
  properties: {
    maxSizeInMegabytes: 1024
    defaultMessageTimeToLive: 'PT1H'
  }
}

// Per-profile subscriptions with SQL filters.
// Multi-profile types (BE, FE) filter on workerProfile; single-profile types filter on workerType.
// Source of truth: config/worker-profiles.yml + config/agent-registry.yml
var taskSubscriptions = [
  // ── BE profiles (one subscription per stack) ──
  { name: 'be-java-worker-sub',   filter: "workerProfile = 'be-java'",  maxDelivery: 3 }
  { name: 'be-go-worker-sub',     filter: "workerProfile = 'be-go'",    maxDelivery: 3 }
  { name: 'be-rust-worker-sub',   filter: "workerProfile = 'be-rust'",  maxDelivery: 3 }
  { name: 'be-node-worker-sub',   filter: "workerProfile = 'be-node'",  maxDelivery: 3 }
  // ── FE profiles ──
  { name: 'fe-react-worker-sub',  filter: "workerProfile = 'fe-react'", maxDelivery: 3 }
  // ── Single-profile types (filter by workerType) ──
  { name: 'ai-task-worker-sub',   filter: "workerType = 'AI_TASK'",     maxDelivery: 5 }
  { name: 'contract-worker-sub',  filter: "workerType = 'CONTRACT'",    maxDelivery: 3 }
  // ── Manager workers ──
  { name: 'task-manager-worker-sub',        filter: "workerType = 'TASK_MANAGER'",        maxDelivery: 3 }
  { name: 'compensator-manager-worker-sub', filter: "workerType = 'COMPENSATOR_MANAGER'", maxDelivery: 3 }
  { name: 'hook-manager-worker-sub',        filter: "workerType = 'HOOK_MANAGER'",        maxDelivery: 3 }
  { name: 'audit-manager-worker-sub',       filter: "workerType = 'AUDIT_MANAGER'",       maxDelivery: 3 }
  { name: 'event-manager-worker-sub',       filter: "workerType = 'EVENT_MANAGER'",       maxDelivery: 3 }
]

resource taskSubscriptionResources 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = [for sub in taskSubscriptions: {
  parent: agentTasksTopic
  name: sub.name
  properties: {
    lockDuration: 'PT5M'
    maxDeliveryCount: sub.maxDelivery
    deadLetteringOnMessageExpiration: true
    defaultMessageTimeToLive: 'PT1H'
  }
}]

resource taskSqlFilters 'Microsoft.ServiceBus/namespaces/topics/subscriptions/rules@2022-10-01-preview' = [for (sub, i) in taskSubscriptions: {
  parent: taskSubscriptionResources[i]
  name: 'workerTypeFilter'
  properties: {
    filterType: 'SqlFilter'
    sqlFilter: {
      sqlExpression: sub.filter
    }
  }
}]

// Dedicated review topic — separate from agent-tasks for QoS isolation
resource agentReviewsTopic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' = {
  parent: serviceBusNamespace
  name: 'agent-reviews'
  properties: {
    maxSizeInMegabytes: 256
    defaultMessageTimeToLive: 'PT1H'
  }
}

resource reviewSubscription 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = {
  parent: agentReviewsTopic
  name: 'review-worker-sub'
  properties: {
    lockDuration: 'PT5M'
    maxDeliveryCount: 3
    deadLetteringOnMessageExpiration: true
    defaultMessageTimeToLive: 'PT1H'
  }
}

// Results topic (workers → orchestrator)
resource resultsTopic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' = {
  parent: serviceBusNamespace
  name: 'agent-results'
  properties: {
    maxSizeInMegabytes: 1024
    defaultMessageTimeToLive: 'PT2H'
  }
}

resource resultsSubscription 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = {
  parent: resultsTopic
  name: 'orchestrator'
  properties: {
    lockDuration: 'PT2M'
    maxDeliveryCount: 5
    deadLetteringOnMessageExpiration: true
    defaultMessageTimeToLive: 'PT2H'
  }
}

output name string = serviceBusNamespace.name
output id string = serviceBusNamespace.id
