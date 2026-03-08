package com.agentframework.compiler.manifest;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads and validates agent manifest YAML files from a directory.
 */
public class ManifestLoader {

    private static final String MANIFEST_GLOB = "*.agent.yml";
    private static final String EXPECTED_API_VERSION = "agent-framework/v1";
    private static final String EXPECTED_KIND = "AgentManifest";

    /**
     * Loads all {@code *.agent.yml} files from the given directory.
     *
     * @param manifestDir directory containing manifest files
     * @return list of parsed and validated manifests
     * @throws IOException if a file cannot be read
     * @throws ManifestValidationException if a manifest is invalid
     */
    public List<AgentManifest> loadAll(Path manifestDir) throws IOException {
        if (!Files.isDirectory(manifestDir)) {
            throw new IOException("Manifest directory does not exist: " + manifestDir);
        }

        List<AgentManifest> manifests = new ArrayList<>();
        try (Stream<Path> stream = Files.list(manifestDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".agent.yml"))
                  .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                  .forEach(file -> {
                      try {
                          manifests.add(load(file));
                      } catch (IOException e) {
                          throw new java.io.UncheckedIOException(e);
                      }
                  });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }

        if (manifests.isEmpty()) {
            throw new IOException("No *.agent.yml files found in " + manifestDir);
        }

        validateUniqueness(manifests);
        return manifests;
    }

    /**
     * Loads a single manifest file.
     */
    public AgentManifest load(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        Yaml yaml = new Yaml(new Constructor(AgentManifest.class, options));

        try (InputStream is = Files.newInputStream(file)) {
            AgentManifest manifest = yaml.load(is);
            validate(manifest, file.getFileName().toString());
            return manifest;
        }
    }

    private void validate(AgentManifest m, String fileName) {
        if (m == null) {
            throw new ManifestValidationException(fileName, "File is empty or invalid YAML");
        }
        if (!EXPECTED_API_VERSION.equals(m.getApiVersion())) {
            throw new ManifestValidationException(fileName,
                "Expected apiVersion '" + EXPECTED_API_VERSION + "', got '" + m.getApiVersion() + "'");
        }
        if (!EXPECTED_KIND.equals(m.getKind())) {
            throw new ManifestValidationException(fileName,
                "Expected kind '" + EXPECTED_KIND + "', got '" + m.getKind() + "'");
        }
        if (m.getMetadata() == null || m.getMetadata().getName() == null || m.getMetadata().getName().isBlank()) {
            throw new ManifestValidationException(fileName, "metadata.name is required");
        }
        if (m.getSpec() == null) {
            throw new ManifestValidationException(fileName, "spec is required");
        }
        var spec = m.getSpec();
        if (spec.getWorkerType() == null || spec.getWorkerType().isBlank()) {
            throw new ManifestValidationException(fileName, "spec.workerType is required");
        }
        if (spec.getTopic() == null || spec.getTopic().isBlank()) {
            throw new ManifestValidationException(fileName, "spec.topic is required");
        }
        if (spec.getSubscription() == null || spec.getSubscription().isBlank()) {
            throw new ManifestValidationException(fileName, "spec.subscription is required");
        }
        if (spec.getPrompts() == null) {
            throw new ManifestValidationException(fileName, "spec.prompts is required");
        }
        if (spec.getPrompts().getSystemPromptFile() == null || spec.getPrompts().getSystemPromptFile().isBlank()) {
            throw new ManifestValidationException(fileName, "spec.prompts.systemPromptFile is required");
        }
        if (spec.getPrompts().getInstructions() == null || spec.getPrompts().getInstructions().isBlank()) {
            throw new ManifestValidationException(fileName, "spec.prompts.instructions is required");
        }
        if (!spec.isProgrammatic()
                && (spec.getTools() == null
                    || spec.getTools().getAllowlist() == null
                    || spec.getTools().getAllowlist().isEmpty())) {
            throw new ManifestValidationException(fileName,
                "spec.tools.allowlist is required and must not be empty " +
                "(set programmatic: true to skip for programmatic workers with no MCP tools)");
        }
    }

    private void validateUniqueness(List<AgentManifest> manifests) {
        var names = manifests.stream()
            .map(m -> m.getMetadata().getName())
            .toList();
        var uniqueNames = names.stream().distinct().toList();
        if (names.size() != uniqueNames.size()) {
            var duplicates = names.stream()
                .filter(n -> names.indexOf(n) != names.lastIndexOf(n))
                .distinct()
                .toList();
            throw new ManifestValidationException("<multiple>",
                "Duplicate manifest names found: " + duplicates);
        }

        var profiles = manifests.stream()
            .map(m -> m.getSpec().getWorkerProfile())
            .filter(p -> p != null)
            .toList();
        var uniqueProfiles = profiles.stream().distinct().toList();
        if (profiles.size() != uniqueProfiles.size()) {
            var duplicates = profiles.stream()
                .filter(p -> profiles.indexOf(p) != profiles.lastIndexOf(p))
                .distinct()
                .toList();
            throw new ManifestValidationException("<multiple>",
                "Duplicate worker profiles found: " + duplicates);
        }
    }

    public static class ManifestValidationException extends RuntimeException {
        public ManifestValidationException(String fileName, String message) {
            super("Invalid manifest '" + fileName + "': " + message);
        }
    }
}
