package com.agentframework.compiler.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestLoaderTest {

    private final ManifestLoader loader = new ManifestLoader();

    @Test
    void shouldLoadValidManifest() throws IOException {
        Path file = Path.of("src/test/resources/valid-manifest.agent.yml");

        AgentManifest manifest = loader.load(file);

        assertThat(manifest.getApiVersion()).isEqualTo("agent-framework/v1");
        assertThat(manifest.getKind()).isEqualTo("AgentManifest");
        assertThat(manifest.getMetadata().getName()).isEqualTo("test-worker");
        assertThat(manifest.getMetadata().getDisplayName()).isEqualTo("Test Worker");
        assertThat(manifest.getSpec().getWorkerType()).isEqualTo("BE");
        assertThat(manifest.getSpec().getWorkerProfile()).isEqualTo("test-profile");
        assertThat(manifest.getSpec().getTopic()).isEqualTo("agent-tasks");
        assertThat(manifest.getSpec().getSubscription()).isEqualTo("test-worker-sub");
    }

    @Test
    void shouldLoadModelDefaults() throws IOException {
        Path file = Path.of("src/test/resources/valid-manifest.agent.yml");

        AgentManifest manifest = loader.load(file);

        assertThat(manifest.getSpec().getModel()).isNotNull();
        assertThat(manifest.getSpec().getModel().getName()).isEqualTo("claude-sonnet-4-6");
        assertThat(manifest.getSpec().getModel().getMaxTokens()).isEqualTo(8192);
        assertThat(manifest.getSpec().getModel().getTemperature()).isEqualTo(0.1);
    }

    @Test
    void shouldLoadPromptConfig() throws IOException {
        Path file = Path.of("src/test/resources/valid-manifest.agent.yml");

        AgentManifest manifest = loader.load(file);
        var prompts = manifest.getSpec().getPrompts();

        assertThat(prompts.getSystemPromptFile()).isEqualTo("skills/test-worker.agent.md");
        assertThat(prompts.getSkills()).containsExactly("skills/test-skills/");
        assertThat(prompts.getInstructions()).contains("Implement the test task");
        assertThat(prompts.getResultSchema()).contains("files_created");
    }

    @Test
    void shouldLoadToolConfig() throws IOException {
        Path file = Path.of("src/test/resources/valid-manifest.agent.yml");

        AgentManifest manifest = loader.load(file);
        var tools = manifest.getSpec().getTools();

        assertThat(tools.getAllowlist()).containsExactly("Read", "Write", "Glob");
        assertThat(tools.getDependencies())
            .containsExactly("io.github.massimilianopili:mcp-filesystem-tools");
    }

    @Test
    void shouldRejectManifestWithMissingWorkerType() {
        Path file = Path.of("src/test/resources/invalid-manifest-no-type.agent.yml");

        assertThatThrownBy(() -> loader.load(file))
            .isInstanceOf(ManifestLoader.ManifestValidationException.class)
            .hasMessageContaining("workerType");
    }

    @Test
    void shouldRejectEmptyDirectory(@TempDir Path tempDir) {
        assertThatThrownBy(() -> loader.loadAll(tempDir))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("No *.agent.yml files found");
    }

    @Test
    void shouldLoadAllManifestsFromDirectory(@TempDir Path tempDir) throws IOException {
        // Copy the valid manifest twice with different names
        String content1 = Files.readString(Path.of("src/test/resources/valid-manifest.agent.yml"));
        String content2 = content1
            .replace("name: test-worker", "name: other-worker")
            .replace("workerProfile: test-profile", "workerProfile: other-profile");

        Files.writeString(tempDir.resolve("test.agent.yml"), content1);
        Files.writeString(tempDir.resolve("other.agent.yml"), content2);

        List<AgentManifest> manifests = loader.loadAll(tempDir);

        assertThat(manifests).hasSize(2);
        // Files are loaded in alphabetical order: other.agent.yml < test.agent.yml
        assertThat(manifests.stream().map(m -> m.getMetadata().getName()))
            .containsExactly("other-worker", "test-worker");
    }

    @Test
    void shouldRejectDuplicateNames(@TempDir Path tempDir) throws IOException {
        String content = Files.readString(Path.of("src/test/resources/valid-manifest.agent.yml"));

        Files.writeString(tempDir.resolve("test1.agent.yml"), content);
        Files.writeString(tempDir.resolve("test2.agent.yml"), content);

        assertThatThrownBy(() -> loader.loadAll(tempDir))
            .isInstanceOf(ManifestLoader.ManifestValidationException.class)
            .hasMessageContaining("Duplicate manifest names");
    }

    @Test
    void shouldAcceptProgrammaticWorkerWithEmptyAllowlist() throws IOException {
        Path file = Path.of("src/test/resources/programmatic-manifest.agent.yml");

        AgentManifest manifest = loader.load(file);

        assertThat(manifest.getSpec().isProgrammatic()).isTrue();
        assertThat(manifest.getSpec().getWorkerType()).isEqualTo("RAG_MANAGER");
        assertThat(manifest.getSpec().getTools().getAllowlist()).isEmpty();
    }

    @Test
    void shouldRejectNonProgrammaticWorkerWithEmptyAllowlist(@TempDir Path tempDir) throws IOException {
        // A non-programmatic worker with no allowlist must be rejected
        String yaml = """
                apiVersion: agent-framework/v1
                kind: AgentManifest
                metadata:
                  name: bad-worker
                  displayName: "Bad Worker"
                spec:
                  workerType: BE
                  topic: agent-tasks
                  subscription: bad-worker-sub
                  prompts:
                    systemPromptFile: prompts/bad.agent.md
                    instructions: "Some instructions"
                  tools:
                    allowlist: []
                """;
        Path file = tempDir.resolve("bad.agent.yml");
        Files.writeString(file, yaml);

        assertThatThrownBy(() -> loader.load(file))
            .isInstanceOf(ManifestLoader.ManifestValidationException.class)
            .hasMessageContaining("allowlist");
    }
}
