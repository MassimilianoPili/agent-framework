package com.agentframework.compiler.registry;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads and parses the MCP server registry ({@code mcp-registry.yml}).
 *
 * <p>Provides lookup methods for the code generator to resolve MCP server connection
 * details and auto-configuration class names from logical server names declared
 * in agent manifests.</p>
 */
public class McpRegistryLoader {

    /**
     * Connection details for a specific transport (SSE or STDIO).
     */
    public record ConnectionConfig(
            String url,
            String command,
            List<String> args
    ) {
        public static ConnectionConfig fromMap(Map<String, Object> map) {
            if (map == null) return null;
            String url = (String) map.get("url");
            String command = (String) map.get("command");
            @SuppressWarnings("unchecked")
            List<String> args = map.containsKey("args")
                    ? ((List<?>) map.get("args")).stream().map(Object::toString).toList()
                    : List.of();
            return new ConnectionConfig(url, command, args);
        }
    }

    /**
     * A single MCP server entry from the registry.
     */
    public record McpServerEntry(
            String name,
            String transport,
            String packageId,
            String autoConfiguration,
            boolean enabled,
            Map<String, ConnectionConfig> connections
    ) {}

    private final Map<String, McpServerEntry> servers;

    private McpRegistryLoader(Map<String, McpServerEntry> servers) {
        this.servers = Collections.unmodifiableMap(servers);
    }

    /**
     * Loads the registry from the given YAML file.
     *
     * @param registryFile path to {@code mcp-registry.yml}
     * @return a loaded registry instance
     * @throws IOException if the file cannot be read or parsed
     */
    public static McpRegistryLoader load(Path registryFile) throws IOException {
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = yaml.load(Files.readString(registryFile));

        @SuppressWarnings("unchecked")
        Map<String, Object> serversMap = (Map<String, Object>) root.get("servers");
        if (serversMap == null) {
            return new McpRegistryLoader(Map.of());
        }

        Map<String, McpServerEntry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : serversMap.entrySet()) {
            String serverName = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> serverDef = (Map<String, Object>) entry.getValue();

            String transport = (String) serverDef.getOrDefault("transport", "sse");
            String packageId = (String) serverDef.get("package");
            String autoConfig = (String) serverDef.get("autoConfiguration");
            boolean enabled = (boolean) serverDef.getOrDefault("enabled", true);

            Map<String, ConnectionConfig> connections = new HashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> connectionsMap = (Map<String, Object>) serverDef.get("connections");
            if (connectionsMap != null) {
                for (Map.Entry<String, Object> connEntry : connectionsMap.entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> connDef = (Map<String, Object>) connEntry.getValue();
                    connections.put(connEntry.getKey(), ConnectionConfig.fromMap(connDef));
                }
            }

            entries.put(serverName, new McpServerEntry(
                    serverName, transport, packageId, autoConfig, enabled, connections));
        }

        return new McpRegistryLoader(entries);
    }

    /**
     * Returns the server entry for the given logical name, or {@code null} if not found.
     */
    public McpServerEntry get(String serverName) {
        return servers.get(serverName);
    }

    /**
     * Returns all logical server names that map to the given Maven package.
     *
     * <p>Used for dedup logic: an auto-configuration should be excluded only if
     * ALL servers sharing the same package are covered by MCP client connections.</p>
     */
    public List<String> getServersByPackage(String packageId) {
        return servers.values().stream()
                .filter(e -> packageId.equals(e.packageId()))
                .map(McpServerEntry::name)
                .toList();
    }

    /**
     * Returns the auto-configuration FQCN for the given package, or {@code null}.
     */
    public String getAutoConfiguration(String packageId) {
        return servers.values().stream()
                .filter(e -> packageId.equals(e.packageId()) && e.autoConfiguration() != null)
                .map(McpServerEntry::autoConfiguration)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns all registered server entries.
     */
    public Collection<McpServerEntry> all() {
        return servers.values();
    }
}
