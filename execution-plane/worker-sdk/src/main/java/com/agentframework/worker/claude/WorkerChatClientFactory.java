package com.agentframework.worker.claude;

import com.agentframework.worker.ToolAllowlist;
import com.agentframework.worker.policy.PathOwnershipEnforcer;
import com.agentframework.worker.policy.PolicyEnforcingToolCallback;
import com.agentframework.worker.policy.PolicyProperties;
import com.agentframework.worker.policy.ToolAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Creates a fully configured ChatClient per worker execution.
 *
 * Tool registration is automatic: the worker application declares MCP tool starters
 * (mcp-devops-tools, mcp-filesystem-tools, etc.) in its pom.xml.
 * ReactiveToolAutoConfiguration scans all beans for @ReactiveTool methods and
 * registers them as ToolCallbackProvider beans.
 *
 * This factory receives all auto-configured ToolCallbackProvider instances via
 * Spring injection and attaches them to the ChatClient.
 */
@Component
public class WorkerChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(WorkerChatClientFactory.class);

    private final ChatModel chatModel;
    private final List<ToolCallbackProvider> allProviders;
    private final PathOwnershipEnforcer enforcer;
    private final ToolAuditLogger auditLogger;
    private final PolicyProperties policyProperties;

    public WorkerChatClientFactory(ChatModel chatModel,
                                   List<ToolCallbackProvider> allProviders,
                                   ObjectProvider<PathOwnershipEnforcer> enforcerProvider,
                                   ObjectProvider<ToolAuditLogger> auditLoggerProvider,
                                   ObjectProvider<PolicyProperties> policyPropertiesProvider) {
        this.chatModel = chatModel;
        this.allProviders = allProviders;
        this.enforcer = enforcerProvider.getIfAvailable();
        this.auditLogger = auditLoggerProvider.getIfAvailable();
        this.policyProperties = policyPropertiesProvider.getIfAvailable();
    }

    /**
     * Creates a ChatClient with all tool providers auto-configured in this worker's context.
     * The worker application controls which tools are available by its classpath dependencies.
     */
    public ChatClient create(String workerType) {
        return create(workerType, ToolAllowlist.ALL);
    }

    /**
     * Creates a ChatClient with the given tool policy.
     *
     * <p>When {@code policy} is {@link ToolAllowlist.All}, all auto-discovered tools are attached.
     * When it is {@link ToolAllowlist.Explicit}, only the named tools are included — used by
     * generated workers to enforce their manifest's tool policy at the SDK level.</p>
     *
     * @param workerType the worker type identifier (for logging)
     * @param policy     the tool selection policy
     */
    public ChatClient create(String workerType, ToolAllowlist policy) {
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        boolean policyActive = isPolicyActive();
        String profile = policyProperties != null ? policyProperties.getWorkerProfile() : null;

        int totalTools = 0;
        int includedTools = 0;

        for (ToolCallbackProvider provider : allProviders) {
            ToolCallback[] callbacks = provider.getToolCallbacks();
            totalTools += callbacks.length;

            // Step 1: Allowlist filter (remove unauthorized tools — LLM never sees them)
            if (policy instanceof ToolAllowlist.Explicit explicit) {
                List<String> allowed = explicit.tools();
                callbacks = Arrays.stream(callbacks)
                    .filter(cb -> allowed.contains(cb.getToolDefinition().name()))
                    .toArray(ToolCallback[]::new);
            }

            // Step 2: Policy decorator (wrap surviving tools with ownership + audit)
            if (policyActive) {
                callbacks = Arrays.stream(callbacks)
                    .map(cb -> (ToolCallback) new PolicyEnforcingToolCallback(
                            cb, enforcer, auditLogger, workerType, profile))
                    .toArray(ToolCallback[]::new);
            }

            includedTools += callbacks.length;
            if (callbacks.length > 0) {
                builder.defaultTools((Object[]) callbacks);
            }
        }

        if (policyActive) {
            log.info("Created ChatClient for worker {} (profile={}) with {}/{} tools (policy enforcement active)",
                     workerType, profile, includedTools, totalTools);
        } else if (policy instanceof ToolAllowlist.Explicit explicit) {
            log.debug("Created ChatClient for worker {} with {}/{} tools (filtered by allowlist of {})",
                      workerType, includedTools, totalTools, explicit.tools().size());
        } else {
            log.debug("Created ChatClient for worker {} with {} tools from {} providers",
                      workerType, totalTools, allProviders.size());
        }

        return builder.build();
    }

    private boolean isPolicyActive() {
        return enforcer != null && auditLogger != null
                && policyProperties != null && policyProperties.isEnabled();
    }
}
