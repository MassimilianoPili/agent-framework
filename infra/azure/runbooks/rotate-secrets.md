# Runbook: Rotate Secrets

## When to use

Periodic rotation of API keys and credentials, or when a secret is suspected compromised.

## Secrets Inventory

| Secret | Key Vault Name | Rotation Frequency |
|--------|---------------|-------------------|
| Anthropic API Key | `anthropic-api-key` | 90 days |
| Service Bus Connection | `servicebus-connection` | Auto-managed |
| PostgreSQL Password | `db-password` | 90 days |

## Steps

### Rotate Anthropic API Key

1. Generate a new key at https://console.anthropic.com/settings/keys
2. Update Key Vault:
   ```bash
   az keyvault secret set \
     --vault-name agentfw-kv-{env} \
     --name anthropic-api-key \
     --value "NEW_KEY_VALUE"
   ```
3. Restart all worker jobs to pick up the new key:
   ```bash
   for worker in be-worker fe-worker ai-task-worker contract-worker review-worker; do
     az containerapp job start --name ${worker}-{env} --resource-group rg-agent-framework-{env}
   done
   ```
4. Verify a test plan completes successfully

### Rotate PostgreSQL Password

1. Generate a new password (min 16 chars, mixed case + numbers + symbols)
2. Update PostgreSQL:
   ```bash
   az postgres flexible-server update \
     --name agentfw-pg-{env} \
     --admin-password "NEW_PASSWORD"
   ```
3. Update Key Vault:
   ```bash
   az keyvault secret set --vault-name agentfw-kv-{env} --name db-password --value "NEW_PASSWORD"
   ```
4. Restart orchestrator to pick up new credentials
5. Verify orchestrator can create plans

## Post-Rotation Verification

- Submit a test plan: `curl -X POST .../api/v1/plans -d '{"spec":"test rotation"}'`
- Verify it reaches COMPLETED status within 5 minutes
- Check Application Insights for authentication errors
