package com.agentframework.orchestrator.orchestration;

import java.util.List;

/**
 * Deserialized representation of an {@code *.agent.yml} manifest file.
 *
 * <p>Agent manifests define per-profile capabilities: model configuration,
 * tool allowlists, path ownership, and MCP server access. They are loaded
 * by {@link AgentManifestLoader} at startup to enrich the
 * {@link WorkerProfileRegistry} with capability data.</p>
 *
 * <p>All nested records use nullable fields — manifests vary in completeness
 * (e.g., manager profiles may omit {@code ownsPaths}).</p>
 */
public record AgentManifest(
        String apiVersion,
        String kind,
        ManifestMetadata metadata,
        ManifestSpec spec
) {

    public record ManifestMetadata(
            String name,
            String displayName,
            String description
    ) {}

    public record ManifestSpec(
            String workerType,
            String workerProfile,
            String topic,
            String subscription,
            ManifestModel model,
            ManifestTools tools,
            ManifestOwnership ownership
    ) {}

    public record ManifestModel(
            String name,
            int maxTokens,
            double temperature
    ) {}

    public record ManifestTools(
            List<String> dependencies,
            List<String> allowlist,
            List<String> mcpServers
    ) {
        public ManifestTools {
            dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
            allowlist = allowlist != null ? List.copyOf(allowlist) : List.of();
            mcpServers = mcpServers != null ? List.copyOf(mcpServers) : List.of();
        }
    }

    public record ManifestOwnership(
            List<String> ownsPaths,
            List<String> readOnlyPaths
    ) {
        public ManifestOwnership {
            ownsPaths = ownsPaths != null ? List.copyOf(ownsPaths) : List.of();
            readOnlyPaths = readOnlyPaths != null ? List.copyOf(readOnlyPaths) : List.of();
        }
    }
}
