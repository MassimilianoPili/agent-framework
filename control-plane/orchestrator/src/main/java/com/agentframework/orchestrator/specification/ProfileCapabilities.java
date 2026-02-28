package com.agentframework.orchestrator.specification;

import java.util.List;

/**
 * Capabilities of a worker profile, derived from the agent manifest
 * and loaded via {@code WorkerProfileRegistry}.
 *
 * @param profileName  the profile identifier (e.g. "be-java")
 * @param workerType   the semantic type (e.g. "BE")
 * @param mcpServers   MCP server prefixes available (e.g. ["git", "repo-fs"])
 * @param ownsPaths    path prefixes the profile can write to
 */
public record ProfileCapabilities(
        String profileName,
        String workerType,
        List<String> mcpServers,
        List<String> ownsPaths) {

    public ProfileCapabilities {
        mcpServers = List.copyOf(mcpServers);
        ownsPaths = List.copyOf(ownsPaths);
    }
}
