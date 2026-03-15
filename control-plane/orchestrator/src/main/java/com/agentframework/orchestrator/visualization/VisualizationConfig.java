package com.agentframework.orchestrator.visualization;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the Architectural Visualization Generator.
 *
 * @param enabled feature toggle (default false)
 * @param cache   in-memory cache settings
 * @param layout  graph layout parameters
 */
@ConfigurationProperties(prefix = "visualization")
public record VisualizationConfig(
        @DefaultValue("false") boolean enabled,
        CacheConfig cache,
        LayoutConfig layout
) {
    /**
     * @param ttlSeconds  cache TTL for rendered visualizations (default 300 = 5 min)
     * @param maxEntries  max cached entries before eviction (default 100)
     */
    public record CacheConfig(
            @DefaultValue("300") int ttlSeconds,
            @DefaultValue("100") int maxEntries
    ) {}

    /**
     * @param horizontalSpacing pixels between layers (default 200)
     * @param verticalSpacing   pixels between nodes in same layer (default 100)
     * @param maxNodes          max nodes before truncation (default 500)
     */
    public record LayoutConfig(
            @DefaultValue("200") int horizontalSpacing,
            @DefaultValue("100") int verticalSpacing,
            @DefaultValue("500") int maxNodes
    ) {}
}
