package com.agentframework.orchestrator.orchestration;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads agent manifest files ({@code *.agent.yml}) at startup and enriches the
 * {@link WorkerProfileRegistry} with capability data (mcpServers, ownsPaths).
 *
 * <p>Manifests are the authoritative source for per-profile capabilities.
 * The registry (populated from {@code application.yml}) handles routing
 * (topic, subscription); this loader adds the security and ownership data
 * consumed by {@link com.agentframework.orchestrator.specification.PathOwnershipSpec}
 * and {@link com.agentframework.orchestrator.hooks.HookPolicyResolver}.</p>
 *
 * <p>Enrichment is additive: existing non-empty values in the registry are
 * never overwritten. Manifests without a matching profile in the registry are
 * logged as warnings.</p>
 */
@Component
public class AgentManifestLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentManifestLoader.class);

    private final WorkerProfileRegistry profileRegistry;

    @Value("${agent.manifests.path:agents/manifests}")
    private String manifestsPath;

    public AgentManifestLoader(WorkerProfileRegistry profileRegistry) {
        this.profileRegistry = profileRegistry;
    }

    /**
     * Scans the manifests directory and enriches registry profiles.
     * Runs after {@link WorkerProfileRegistry#validate()} (Spring guarantees
     * {@code @ConfigurationProperties} beans are initialized before {@code @Component} beans).
     */
    @PostConstruct
    void loadManifests() {
        Path dir = Path.of(manifestsPath);
        if (!Files.isDirectory(dir)) {
            log.info("Agent manifests directory not found: {} — skipping enrichment", dir);
            return;
        }

        int loaded = 0;
        int enriched = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.agent.yml")) {
            Yaml yaml = new Yaml();

            for (Path file : stream) {
                try {
                    AgentManifest manifest = parseManifest(yaml, file);
                    if (manifest == null || manifest.spec() == null) {
                        log.warn("Skipping malformed manifest: {}", file.getFileName());
                        continue;
                    }
                    loaded++;

                    String profileName = manifest.spec().workerProfile();
                    if (profileName == null || profileName.isBlank()) {
                        log.debug("Manifest {} has no workerProfile — skipping", file.getFileName());
                        continue;
                    }

                    WorkerProfileRegistry.ProfileEntry entry = profileRegistry.getProfileEntry(profileName);
                    if (entry == null) {
                        log.warn("Manifest '{}' references unknown profile '{}' — not in registry",
                                 file.getFileName(), profileName);
                        continue;
                    }

                    boolean changed = enrichProfile(entry, manifest);
                    if (changed) {
                        enriched++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse manifest {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan manifests directory {}: {}", dir, e.getMessage());
            return;
        }

        log.info("AgentManifestLoader: {} manifests loaded, {} profiles enriched", loaded, enriched);
    }

    @SuppressWarnings("unchecked")
    AgentManifest parseManifest(Yaml yaml, Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            Map<String, Object> raw = yaml.load(is);
            if (raw == null) return null;

            String apiVersion = (String) raw.get("apiVersion");
            String kind = (String) raw.get("kind");

            // metadata
            Map<String, Object> metaMap = (Map<String, Object>) raw.get("metadata");
            AgentManifest.ManifestMetadata metadata = null;
            if (metaMap != null) {
                metadata = new AgentManifest.ManifestMetadata(
                        (String) metaMap.get("name"),
                        (String) metaMap.get("displayName"),
                        (String) metaMap.get("description"));
            }

            // spec
            Map<String, Object> specMap = (Map<String, Object>) raw.get("spec");
            AgentManifest.ManifestSpec spec = null;
            if (specMap != null) {
                spec = parseSpec(specMap);
            }

            return new AgentManifest(apiVersion, kind, metadata, spec);
        }
    }

    @SuppressWarnings("unchecked")
    private AgentManifest.ManifestSpec parseSpec(Map<String, Object> specMap) {
        // model
        Map<String, Object> modelMap = (Map<String, Object>) specMap.get("model");
        AgentManifest.ManifestModel model = null;
        if (modelMap != null) {
            model = new AgentManifest.ManifestModel(
                    (String) modelMap.get("name"),
                    modelMap.get("maxTokens") instanceof Number n ? n.intValue() : 0,
                    modelMap.get("temperature") instanceof Number n ? n.doubleValue() : 0.0);
        }

        // tools
        Map<String, Object> toolsMap = (Map<String, Object>) specMap.get("tools");
        AgentManifest.ManifestTools tools = null;
        if (toolsMap != null) {
            tools = new AgentManifest.ManifestTools(
                    toStringList(toolsMap.get("dependencies")),
                    toStringList(toolsMap.get("allowlist")),
                    toStringList(toolsMap.get("mcpServers")));
        }

        // ownership
        Map<String, Object> ownerMap = (Map<String, Object>) specMap.get("ownership");
        AgentManifest.ManifestOwnership ownership = null;
        if (ownerMap != null) {
            ownership = new AgentManifest.ManifestOwnership(
                    toStringList(ownerMap.get("ownsPaths")),
                    toStringList(ownerMap.get("readOnlyPaths")));
        }

        return new AgentManifest.ManifestSpec(
                (String) specMap.get("workerType"),
                (String) specMap.get("workerProfile"),
                (String) specMap.get("topic"),
                (String) specMap.get("subscription"),
                model, tools, ownership);
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(e -> e instanceof String)
                    .map(e -> (String) e)
                    .toList();
        }
        return List.of();
    }

    /**
     * Enriches a ProfileEntry with manifest data. Only populates empty fields.
     *
     * @return true if any field was enriched
     */
    private boolean enrichProfile(WorkerProfileRegistry.ProfileEntry entry, AgentManifest manifest) {
        boolean changed = false;

        // ownsPaths: enrich from manifest.spec.ownership.ownsPaths
        if (entry.getOwnsPaths().isEmpty()
                && manifest.spec().ownership() != null
                && !manifest.spec().ownership().ownsPaths().isEmpty()) {
            entry.getOwnsPaths().addAll(manifest.spec().ownership().ownsPaths());
            changed = true;
        }

        // mcpServers: enrich from manifest.spec.tools.mcpServers
        if (entry.getMcpServers().isEmpty()
                && manifest.spec().tools() != null
                && !manifest.spec().tools().mcpServers().isEmpty()) {
            entry.getMcpServers().addAll(manifest.spec().tools().mcpServers());
            changed = true;
        }

        if (changed) {
            log.debug("Enriched profile '{}': ownsPaths={}, mcpServers={}",
                      manifest.spec().workerProfile(),
                      entry.getOwnsPaths(), entry.getMcpServers());
        }

        return changed;
    }

    // Visible for testing
    void setManifestsPath(String path) {
        this.manifestsPath = path;
    }
}
