# Runbook: Incident Triage

## When to use

When an alert fires or a user reports degraded service (failed plans, stuck tasks,
worker errors).

## Steps

1. **Check orchestrator health**: Verify the orchestrator pod is running
   ```bash
   az containerapp show --name orchestrator --resource-group rg-agent-framework-{env} --query "properties.runningStatus"
   ```

2. **Check recent plan statuses**: Look for FAILED plans in the database
   ```bash
   # Via orchestrator API
   curl -s https://agent-framework-{env}.azurecontainerapps.io/api/v1/plans?status=FAILED | jq '.[-5:]'
   ```

3. **Check worker job executions**: Look for failed executions
   ```bash
   az containerapp job execution list --name be-worker-{env} --resource-group rg-agent-framework-{env} --query "[?properties.status=='Failed']"
   ```

4. **Check Service Bus DLQ**: Look for stuck messages
   ```bash
   # Check all subscriptions for DLQ messages
   for sub in be-worker fe-worker ai-task-worker contract-worker review-worker orchestrator; do
     echo "$sub: $(az servicebus topic subscription show --namespace sb-agent-framework-{env} --topic agent-tasks --name $sub --query countDetails.deadLetterMessageCount -o tsv 2>/dev/null || echo N/A)"
   done
   ```

5. **Check Application Insights**: Look for exceptions and latency spikes
   ```bash
   az monitor app-insights query --app agentfw-ai-{env} --analytics-query "exceptions | where timestamp > ago(1h) | summarize count() by problemId"
   ```

6. **Escalate if needed**: Follow the DLQ replay or rotate-secrets runbook as appropriate.

## Common Issues

| Symptom | Likely Cause | Action |
|---------|-------------|--------|
| All tasks FAILED | Anthropic API key expired | rotate-secrets runbook |
| Tasks stuck DISPATCHED | Worker not starting | Check Container Apps job logs |
| DLQ filling up | Poison message | dlq-replay runbook |
| 500 on plan creation | Database down | Check PostgreSQL status |
