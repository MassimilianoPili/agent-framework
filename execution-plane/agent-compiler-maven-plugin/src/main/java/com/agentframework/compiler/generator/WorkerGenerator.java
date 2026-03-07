package com.agentframework.compiler.generator;

import com.agentframework.compiler.manifest.AgentManifest;
import com.agentframework.compiler.registry.McpRegistryLoader;
import com.agentframework.compiler.registry.McpRegistryLoader.McpServerEntry;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Generates a complete worker Maven module from an AgentManifest.
 *
 * <p>Produces:</p>
 * <ul>
 *   <li>{@code XxxWorker.java} — AbstractWorker subclass</li>
 *   <li>{@code XxxWorkerApplication.java} — SpringBootApplication</li>
 *   <li>{@code application.yml} — Spring Boot configuration</li>
 *   <li>{@code pom.xml} — Maven project descriptor</li>
 *   <li>{@code Dockerfile} — Container build descriptor</li>
 * </ul>
 */
public class WorkerGenerator {

    private final MustacheFactory mustacheFactory;
    private final McpRegistryLoader mcpRegistry;

    public WorkerGenerator(McpRegistryLoader mcpRegistry) {
        this.mustacheFactory = new DefaultMustacheFactory("templates");
        this.mcpRegistry = mcpRegistry;
    }

    /**
     * Generates all files for a worker module.
     *
     * @param manifest the parsed agent manifest
     * @param outputDir base output directory (e.g., execution-plane/workers)
     * @param manifestFileName original manifest file name (for source comment)
     * @return the generated module directory path
     */
    public Path generate(AgentManifest manifest, Path outputDir, String manifestFileName)
            throws IOException {

        String name = manifest.getMetadata().getName();
        String className = toClassName(name);
        String packageName = toPackageName(name);

        Path moduleDir = outputDir.resolve(name);
        Path javaDir = moduleDir.resolve("src/main/java/com/agentframework/workers/generated/" + packageName);
        Path resourcesDir = moduleDir.resolve("src/main/resources");

        Files.createDirectories(javaDir);
        Files.createDirectories(resourcesDir);

        Map<String, Object> context = buildTemplateContext(manifest, className, packageName, manifestFileName);

        // Generate Worker.java
        writeTemplate("Worker.java.mustache", context, javaDir.resolve(className + ".java"));

        // Generate WorkerApplication.java
        writeTemplate("WorkerApplication.java.mustache", context, javaDir.resolve(className + "Application.java"));

        // Generate application.yml
        writeTemplate("application.yml.mustache", context, resourcesDir.resolve("application.yml"));

        // Generate pom.xml
        writeTemplate("pom.xml.mustache", context, moduleDir.resolve("pom.xml"));

        // Generate Dockerfile
        writeTemplate("Dockerfile.mustache", context, moduleDir.resolve("Dockerfile"));

        // Generate application-mcp.yml (MCP client profile, activated via SPRING_PROFILES_ACTIVE=mcp)
        if (Boolean.TRUE.equals(context.get("hasMcpServers"))) {
            writeTemplate("application-mcp.yml.mustache", context,
                    resourcesDir.resolve("application-mcp.yml"));
        }

        return moduleDir;
    }

    Map<String, Object> buildTemplateContext(AgentManifest manifest, String className,
                                              String packageName, String manifestFileName) {
        Map<String, Object> ctx = new HashMap<>();

        // Identity
        ctx.put("className", className);
        ctx.put("packageName", packageName);
        ctx.put("manifestFile", manifestFileName);
        ctx.put("metadataName", manifest.getMetadata().getName());
        ctx.put("displayName", manifest.getMetadata().getDisplayName() != null
                ? manifest.getMetadata().getDisplayName()
                : manifest.getMetadata().getName());
        ctx.put("description", manifest.getMetadata().getDescription() != null
                ? manifest.getMetadata().getDescription().trim()
                : "");

        // Spec
        var spec = manifest.getSpec();
        ctx.put("workerType", spec.getWorkerType());
        ctx.put("topic", spec.getTopic());
        ctx.put("subscription", spec.getSubscription());
        ctx.put("artifactId", manifest.getMetadata().getName());

        // Log prefix (e.g., "BE-Java" from "be-java-worker")
        ctx.put("logPrefix", buildLogPrefix(manifest));

        // Constructor padding for alignment
        ctx.put("constructorPad", " ".repeat(className.length() + "public (".length()));

        // Model
        var model = spec.getModel() != null ? spec.getModel() : new AgentManifest.Model();
        ctx.put("modelName", model.getName());
        ctx.put("modelMaxTokens", model.getMaxTokens());
        ctx.put("modelTemperature", model.getTemperature());

        // Prompts
        var prompts = spec.getPrompts();
        ctx.put("systemPromptFile", prompts.getSystemPromptFile());
        ctx.put("instructions", indentMultiline(prompts.getInstructions().trim(), 12));
        ctx.put("resultSchema", indentMultiline(
                prompts.getResultSchema() != null ? prompts.getResultSchema().trim() : "{}",
                12));

        // Tool allowlist
        List<String> allowlist = spec.getTools().getAllowlist();
        List<Map<String, Object>> allowlistEntries = new ArrayList<>();
        for (int i = 0; i < allowlist.size(); i++) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", allowlist.get(i));
            entry.put("hasNext", i < allowlist.size() - 1);
            allowlistEntries.add(entry);
        }
        ctx.put("allowlistEntries", allowlistEntries);

        // Skill paths
        List<String> skills = prompts.getSkills() != null ? prompts.getSkills() : List.of();
        List<Map<String, Object>> skillEntries = new ArrayList<>();
        for (int i = 0; i < skills.size(); i++) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", skills.get(i));
            entry.put("hasNext", i < skills.size() - 1);
            skillEntries.add(entry);
        }
        ctx.put("skillEntries", skillEntries);

        // Tool dependencies for pom.xml
        List<String> deps = spec.getTools().getDependencies() != null
                ? spec.getTools().getDependencies() : List.of();
        List<Map<String, String>> toolDependencies = new ArrayList<>();
        for (String dep : deps) {
            String[] parts = dep.split(":");
            if (parts.length == 2) {
                Map<String, String> d = new HashMap<>();
                d.put("groupId", parts[0]);
                d.put("artifactId", parts[1]);
                toolDependencies.add(d);
            }
        }
        ctx.put("toolDependencies", toolDependencies);

        // Policy / ownership — flows into application.yml for runtime enforcement
        // workerProfile: null → omitted from context (Mustache {{#workerProfile}} won't render),
        // non-null → generates workerProfile() override in Worker.java for message filtering
        if (spec.getWorkerProfile() != null && !spec.getWorkerProfile().isBlank()) {
            ctx.put("workerProfile", spec.getWorkerProfile());
        }

        var ownership = spec.getOwnership();
        List<String> ownsPaths = ownership != null && ownership.getOwnsPaths() != null
                ? ownership.getOwnsPaths() : List.of();
        ctx.put("hasOwnsPaths", !ownsPaths.isEmpty());

        List<Map<String, Object>> ownsPathEntries = new ArrayList<>();
        for (int i = 0; i < ownsPaths.size(); i++) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", ownsPaths.get(i));
            entry.put("hasNext", i < ownsPaths.size() - 1);
            ownsPathEntries.add(entry);
        }
        ctx.put("ownsPathEntries", ownsPathEntries);

        // --- MCP client configuration (for application-mcp.yml profile) ---
        buildMcpContext(spec, ctx);

        return ctx;
    }

    private void writeTemplate(String templateName, Map<String, Object> context, Path outputFile)
            throws IOException {
        Mustache mustache = mustacheFactory.compile(templateName);
        StringWriter writer = new StringWriter();
        mustache.execute(writer, context).flush();
        Files.writeString(outputFile, writer.toString());
    }

    /**
     * Builds MCP client context variables for Mustache templates.
     *
     * <p>Resolves each declared {@code mcpServers} entry against the registry,
     * produces SSE connection entries for {@code application-mcp.yml}, and computes
     * auto-configuration exclusions for hybrid dedup.</p>
     *
     * <p>Dedup rule: exclude an in-process auto-configuration only if ALL servers
     * that share the same Maven package are covered by MCP client connections.
     * This prevents removing in-process tools that are still needed.</p>
     */
    private void buildMcpContext(AgentManifest.Spec spec, Map<String, Object> ctx) {
        List<String> mcpServerNames = spec.getTools().getMcpServers();

        if (mcpRegistry == null || mcpServerNames.isEmpty()) {
            ctx.put("hasMcpServers", false);
            ctx.put("mcpConnectionEntries", List.of());
            ctx.put("mcpExcludedAutoConfigs", List.of());
            return;
        }

        // Resolve each declared server against the registry
        List<Map<String, Object>> mcpConnectionEntries = new ArrayList<>();
        Set<String> mcpCoveredPackages = new LinkedHashSet<>();

        for (String serverName : mcpServerNames) {
            McpServerEntry entry = mcpRegistry.get(serverName);
            if (entry == null || !entry.enabled()) {
                continue;
            }

            Map<String, Object> conn = new HashMap<>();
            conn.put("name", serverName);
            conn.put("nameUpper", serverName.replace("-", "_").toUpperCase());

            // SSE connection URL (default transport for Docker deployments)
            var sseConn = entry.connections().get("sse");
            conn.put("sseUrl", sseConn != null ? sseConn.url() : "http://mcp-server:8080");

            mcpConnectionEntries.add(conn);
            if (entry.packageId() != null) {
                mcpCoveredPackages.add(entry.packageId());
            }
        }

        ctx.put("hasMcpServers", !mcpConnectionEntries.isEmpty());
        ctx.put("mcpConnectionEntries", mcpConnectionEntries);

        // Dedup: compute auto-configurations to exclude when the mcp profile is active.
        // Exclude only if ALL servers of a package are covered by MCP connections.
        List<Map<String, String>> excludedAutoConfigs = new ArrayList<>();
        for (String pkg : mcpCoveredPackages) {
            List<String> serversForPackage = mcpRegistry.getServersByPackage(pkg);
            if (mcpServerNames.containsAll(serversForPackage)) {
                String autoConfig = mcpRegistry.getAutoConfiguration(pkg);
                if (autoConfig != null) {
                    Map<String, String> entry = new HashMap<>();
                    entry.put("fqcn", autoConfig);
                    excludedAutoConfigs.add(entry);
                }
            }
        }
        ctx.put("mcpExcludedAutoConfigs", excludedAutoConfigs);
        ctx.put("hasMcpExcludes", !excludedAutoConfigs.isEmpty());
    }

    /**
     * Converts a kebab-case name like "be-java-worker" to a PascalCase class name like "BeJavaWorker".
     */
    static String toClassName(String kebabName) {
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : kebabName.toCharArray()) {
            if (c == '-') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Converts a kebab-case name like "be-java-worker" to a flat package segment like "bejavaworker".
     */
    static String toPackageName(String kebabName) {
        return kebabName.replace("-", "");
    }

    private String buildLogPrefix(AgentManifest manifest) {
        String profile = manifest.getSpec().getWorkerProfile();
        if (profile != null) {
            // "be-java" -> "BE-Java"
            String[] parts = profile.split("-");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i == 0) {
                    sb.append(parts[i].toUpperCase());
                } else {
                    sb.append("-");
                    sb.append(parts[i].substring(0, 1).toUpperCase());
                    sb.append(parts[i].substring(1));
                }
            }
            return sb.toString();
        }
        return manifest.getSpec().getWorkerType();
    }

    /**
     * Indents each line of a multi-line string by the given number of spaces,
     * except the first line (which is already positioned by the template).
     */
    private String indentMultiline(String text, int spaces) {
        String indent = " ".repeat(spaces);
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            sb.append("\n").append(indent).append(lines[i]);
        }
        return sb.toString();
    }
}
