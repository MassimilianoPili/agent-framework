// container-apps-job.bicep — Azure Container Apps Job (one per worker type)

@description('Worker name (e.g., be-worker)')
param workerName string

@description('Environment name')
param environment string

@description('Azure region')
param location string

@description('Docker image tag')
param imageTag string

@description('Container Apps Environment resource ID')
param containerAppsEnvId string

@description('Service Bus connection string')
@secure()
param serviceBusConnectionString string

resource job 'Microsoft.App/jobs@2023-05-01' = {
  name: '${workerName}-${environment}'
  location: location
  properties: {
    environmentId: containerAppsEnvId
    configuration: {
      triggerType: 'Event'
      replicaTimeout: 1800
      replicaRetryLimit: 1
      // TODO: Configure event trigger for Service Bus
      // See execution-plane/runtime/jobs/${workerName}.job.yml
    }
    template: {
      containers: [
        {
          name: workerName
          image: 'ghcr.io/agent-framework/${workerName}:${imageTag}'
          resources: {
            cpu: json('1.0')
            memory: '2Gi'
          }
          // TODO: Add environment variables and secret references
        }
      ]
    }
  }
}
