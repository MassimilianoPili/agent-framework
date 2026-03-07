package com.agentframework.orchestrator.hooks;

import com.agentframework.common.policy.ApprovalMode;
import com.agentframework.common.policy.HookPolicy;
import com.agentframework.common.policy.RiskLevel;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static fallback policy resolver.
 *
 * <p>Returns a {@link HookPolicy} derived from the {@link WorkerProfileRegistry}
 * (ownsPaths, mcpServers) combined with a hardcoded tool allowlist per worker type.
 * This is used when the {@code HOOK_MANAGER} worker has not produced a per-task policy.</p>
 *
 * <p>When the HM worker result is available, {@link HookManagerService} takes precedence
 * and this resolver is not invoked.</p>
 */
@Service
public class HookPolicyResolver {

    private static final Logger log = LoggerFactory.getLogger(HookPolicyResolver.class);

    /**
     * Default tool allowlist per worker type, using MCP tool bean names.
     * Manager types are read-only by default.
     */
    private static final Map<WorkerType, List<String>> DEFAULT_TOOL_ALLOWLISTS = Map.of(
        WorkerType.BE,              List.of("fs_list", "fs_read", "fs_write", "fs_search", "fs_grep"),
        WorkerType.FE,              List.of("fs_list", "fs_read", "fs_write", "fs_search", "fs_grep"),
        WorkerType.AI_TASK,         List.of("fs_list", "fs_read", "fs_write", "fs_search", "fs_grep"),
        WorkerType.CONTRACT,        List.of("fs_list", "fs_read", "fs_write", "fs_search", "fs_grep"),
        WorkerType.REVIEW,          List.of("fs_list", "fs_read", "fs_search", "fs_grep"),
        WorkerType.CONTEXT_MANAGER, List.of("fs_list", "fs_read", "fs_search", "fs_grep"),
        WorkerType.SCHEMA_MANAGER,  List.of("fs_list", "fs_read", "fs_search", "fs_grep"),
        WorkerType.HOOK_MANAGER,    List.of("fs_list", "fs_read", "fs_search", "fs_grep"),
        WorkerType.AUDIT_MANAGER,   List.of("fs_list", "fs_read", "fs_write", "fs_grep"),
        WorkerType.EVENT_MANAGER,   List.of("fs_list", "fs_read")
    );

    private final WorkerProfileRegistry profileRegistry;

    public HookPolicyResolver(WorkerProfileRegistry profileRegistry) {
        this.profileRegistry = profileRegistry;
    }

    /**
     * Resolves a static HookPolicy for a given worker type.
     * Uses ownsPaths and mcpServers from the default profile in WorkerProfileRegistry.
     *
     * @param workerType the worker type to resolve policy for
     * @return Optional with policy, or empty if the type is unknown
     */
    public Optional<HookPolicy> resolve(WorkerType workerType) {
        if (workerType == null) {
            return Optional.empty();
        }

        List<String> allowedTools = DEFAULT_TOOL_ALLOWLISTS.getOrDefault(workerType, List.of());

        // Get ownsPaths and mcpServers from the default profile for this worker type
        List<String> ownedPaths = List.of();
        List<String> allowedMcpServers = List.of();

        String defaultProfile = profileRegistry.resolveDefaultProfile(workerType);
        if (defaultProfile != null) {
            WorkerProfileRegistry.ProfileEntry entry = profileRegistry.getProfileEntry(defaultProfile);
            if (entry != null) {
                ownedPaths = entry.getOwnsPaths();
                allowedMcpServers = entry.getMcpServers();
            }
        }

        log.debug("Resolved static HookPolicy for {}: tools={}, paths={}", workerType, allowedTools, ownedPaths);
        return Optional.of(new HookPolicy(
                allowedTools, ownedPaths, allowedMcpServers, true,
                null, List.of(), ApprovalMode.NONE, 0, RiskLevel.LOW, null, false));
    }
}
