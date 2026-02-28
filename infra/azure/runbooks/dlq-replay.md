# Runbook: Dead Letter Queue Replay

## When to use

When messages accumulate in a Service Bus subscription's dead letter queue (DLQ),
typically after a worker failure or poison message scenario.

## Prerequisites

- Azure CLI authenticated (`az login`)
- Service Bus connection string or managed identity access
- Approval from platform team (see `execution-plane/runtime/servicebus/dlq.policy.yml`)

## Steps

1. **Identify the DLQ**: Check which subscription has DLQ messages
   ```bash
   az servicebus topic subscription show \
     --namespace-name sb-agent-framework-{env} \
     --topic-name agent-tasks \
     --name be-worker \
     --query "countDetails.deadLetterMessageCount"
   ```

2. **Peek at DLQ messages**: Inspect without consuming
   ```bash
   # TODO: Use Service Bus Explorer or az servicebus message peek
   ```

3. **Assess root cause**: Check worker logs for the failure reason
   ```bash
   az monitor log-analytics query \
     --workspace law-agentfw-{env} \
     --analytics-query "ContainerAppConsoleLogs | where ContainerName == 'be-worker' | order by TimeGenerated desc | take 50"
   ```

4. **Fix the root cause**: Deploy a fix if needed before replaying

5. **Replay messages**: Move DLQ messages back to the active queue
   ```bash
   # TODO: Implement replay script using Service Bus SDK
   ```

6. **Verify**: Monitor worker logs to confirm successful processing

## Escalation

If DLQ count exceeds the alert threshold (5), the platform team is notified
automatically via the configured alert channel.
