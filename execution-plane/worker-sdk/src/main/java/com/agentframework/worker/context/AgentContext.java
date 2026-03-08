package com.agentframework.worker.context;

import com.agentframework.common.policy.HookPolicy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fully assembled context for one agent execution.
 * Built by AgentContextBuilder from an incoming AgentTask.
 * Passed to AbstractWorker.execute() as the primary input.
 *
 * <p>{@code relevantFiles} is populated from the CONTEXT_MANAGER dependency result
 * ({@code relevant_files} JSON array) and used by the policy layer to restrict
 * which files domain workers (BE/FE/AI_TASK) may Read at runtime.</p>
 *
 * <p>{@code policy} is populated from the HOOK_MANAGER dependency result
 * and used by {@link com.agentframework.worker.policy.PolicyEnforcingToolCallback}
 * to override the static {@code PolicyProperties} configuration for this specific task.</p>
 */
public record AgentContext(
    UUID planId,
    UUID itemId,
    String taskKey,
    String title,
    String description,
    String spec,
    String systemPrompt,
    Map<String, String> dependencyResults,
    String skillsContent,
    List<String> relevantFiles,
    HookPolicy policy,
    String councilGuidance,        // JSON CouncilReport from pre-planning or task-level council session (nullable)
    String workspacePath           // plan-scoped workspace directory path (nullable, e.g. "/workspace/a1b2c3d4")
) {
    /** Returns true if this context carries an explicit file allowlist from CONTEXT_MANAGER. */
    public boolean hasRelevantFiles() {
        return relevantFiles != null && !relevantFiles.isEmpty();
    }

    /** Returns true if this context carries a task-level HookPolicy from HOOK_MANAGER. */
    public boolean hasPolicy() {
        return policy != null;
    }

    /** Returns true if this context carries council guidance from a council session. */
    public boolean hasCouncilGuidance() {
        return councilGuidance != null && !councilGuidance.isBlank();
    }
}
