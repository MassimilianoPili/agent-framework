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

// Per-workerType subscriptions with SQL filters on the workerType message property
var taskSubscriptions = [
  { name: 'be-worker',       filter: "workerType = 'BE'",       maxDelivery: 3 }
  { name: 'fe-worker',       filter: "workerType = 'FE'",       maxDelivery: 3 }
  { name: 'ai-task-worker',  filter: "workerType = 'AI_TASK'",  maxDelivery: 5 }
  { name: 'contract-worker', filter: "workerType = 'CONTRACT'", maxDelivery: 3 }
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
  name: 'review-worker'
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
