package com.agentframework.orchestrator.orchestration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link AgentManifestLoader} — YAML manifest parsing and registry enrichment.
 */
class AgentManifestLoaderTest {

    @TempDir
    Path tempDir;

    private WorkerProfileRegistry registry;
    private AgentManifestLoader loader;

    @BeforeEach
    void setUp() {
        registry = new WorkerProfileRegistry();
        registry.setProfiles(new java.util.LinkedHashMap<>());
        registry.setDefaults(new java.util.LinkedHashMap<>());

        // Register a be-java profile with empty capabilities (simulates application.yml)
        registry.getProfiles().put("be-java", new WorkerProfileRegistry.ProfileEntry(
                "BE", "agent-tasks", "be-java-worker-sub", "Backend Java"));
        registry.getDefaults().put("BE", "be-java");

        loader = new AgentManifestLoader(registry);
        loader.setManifestsPath(tempDir.toString());
    }

    @Test
    void loadManifests_enrichesExistingProfile() throws IOException {
        writeManifest("be-java.agent.yml", """
                apiVersion: agent-framework/v1
                kind: AgentManifest
                metadata:
                  name: be-java-worker
                spec:
                  workerType: BE
                  workerProfile: be-java
                  tools:
                    mcpServers:
                      - git
                      - repo-fs
                  ownership:
                    ownsPaths:
                      - backend/
                      - templates/be/
                """);

        loader.loadManifests();

        WorkerProfileRegistry.ProfileEntry entry = registry.getProfileEntry("be-java");
        assertThat(entry.getOwnsPaths()).containsExactly("backend/", "templates/be/");
    }

    @Test
    void loadManifests_enrichesMcpServers() throws IOException {
        writeManifest("be-java.agent.yml", """
                apiVersion: agent-framework/v1
                kind: AgentManifest
                metadata:
                  name: be-java-worker
                spec:
                  workerType: BE
                  workerProfile: be-java
                  tools:
                    mcpServers:
                      - git
                      - repo-fs
                      - openapi
                      - test
                      - bash
                  ownership:
                    ownsPaths:
                      - backend/
                """);

        loader.loadManifests();

        WorkerProfileRegistry.ProfileEntry entry = registry.getProfileEntry("be-java");
        assertThat(entry.getMcpServers()).containsExactly("git", "repo-fs", "openapi", "test", "bash");
    }

    @Test
    void loadManifests_skipsUnknownProfile() throws IOException {
        writeManifest("be-zig.agent.yml", """
                apiVersion: agent-framework/v1
                kind: AgentManifest
                metadata:
                  name: be-zig-worker
                spec:
                  workerType: BE
                  workerProfile: be-zig
                  tools:
                    mcpServers:
                      - git
                  ownership:
                    ownsPaths:
                      - backend/
                """);

        // Should not throw — just log a warning
        assertThatCode(() -> loader.loadManifests()).doesNotThrowAnyException();

        // be-java should remain unchanged
        WorkerProfileRegistry.ProfileEntry entry = registry.getProfileEntry("be-java");
        assertThat(entry.getOwnsPaths()).isEmpty();
        assertThat(entry.getMcpServers()).isEmpty();
    }

    @Test
    void loadManifests_doesNotOverrideExisting() throws IOException {
        // Pre-populate ownsPaths in the registry entry
        WorkerProfileRegistry.ProfileEntry entry = registry.getProfileEntry("be-java");
        entry.getOwnsPaths().addAll(List.of("src/main/java/"));

        writeManifest("be-java.agent.yml", """
                apiVersion: agent-framework/v1
                kind: AgentManifest
                metadata:
                  name: be-java-worker
                spec:
                  workerType: BE
                  workerProfile: be-java
                  ownership:
                    ownsPaths:
                      - backend/
                      - templates/be/
                """);

        loader.loadManifests();

        // Original value preserved — manifest data NOT merged
        assertThat(entry.getOwnsPaths()).containsExactly("src/main/java/");
    }

    @Test
    void loadManifests_emptyDirectory() {
        // tempDir exists but has no *.agent.yml files
        assertThatCode(() -> loader.loadManifests()).doesNotThrowAnyException();

        // Registry unchanged
        assertThat(registry.getProfileEntry("be-java").getOwnsPaths()).isEmpty();
    }

    private void writeManifest(String filename, String content) throws IOException {
        Files.writeString(tempDir.resolve(filename), content);
    }
}
