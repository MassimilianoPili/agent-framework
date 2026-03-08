package com.agentframework.compiler.generator;

import com.agentframework.compiler.manifest.AgentManifest;
import com.agentframework.compiler.manifest.ManifestLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerGeneratorTest {

    private WorkerGenerator generator;
    private AgentManifest manifest;

    @BeforeEach
    void setUp() throws IOException {
        generator = new WorkerGenerator(null, "1.1.0-SNAPSHOT");
        manifest = new ManifestLoader().load(
            Path.of("src/test/resources/valid-manifest.agent.yml"));
    }

    // --- Naming tests ---

    @Test
    void shouldConvertKebabCaseToPascalCase() {
        assertThat(WorkerGenerator.toClassName("be-java-worker")).isEqualTo("BeJavaWorker");
        assertThat(WorkerGenerator.toClassName("fe-react-worker")).isEqualTo("FeReactWorker");
        assertThat(WorkerGenerator.toClassName("ai-task-worker")).isEqualTo("AiTaskWorker");
        assertThat(WorkerGenerator.toClassName("review-worker")).isEqualTo("ReviewWorker");
        assertThat(WorkerGenerator.toClassName("test-worker")).isEqualTo("TestWorker");
    }

    @Test
    void shouldConvertKebabCaseToPackageName() {
        assertThat(WorkerGenerator.toPackageName("be-java-worker")).isEqualTo("bejavaworker");
        assertThat(WorkerGenerator.toPackageName("test-worker")).isEqualTo("testworker");
    }

    // --- Template context tests ---

    @Test
    void shouldBuildCorrectTemplateContext() {
        Map<String, Object> ctx = generator.buildTemplateContext(
            manifest, "TestWorker", "testworker", "test.agent.yml");

        assertThat(ctx.get("className")).isEqualTo("TestWorker");
        assertThat(ctx.get("packageName")).isEqualTo("testworker");
        assertThat(ctx.get("workerType")).isEqualTo("BE");
        assertThat(ctx.get("topic")).isEqualTo("agent-tasks");
        assertThat(ctx.get("subscription")).isEqualTo("test-worker-sub");
        assertThat(ctx.get("systemPromptFile")).isEqualTo("skills/test-worker.agent.md");
        assertThat(ctx.get("manifestFile")).isEqualTo("test.agent.yml");
        assertThat(ctx.get("displayName")).isEqualTo("Test Worker");
        assertThat(ctx.get("logPrefix")).isEqualTo("TEST-Profile");
    }

    @Test
    void shouldBuildModelConfig() {
        Map<String, Object> ctx = generator.buildTemplateContext(
            manifest, "TestWorker", "testworker", "test.agent.yml");

        assertThat(ctx.get("modelName")).isEqualTo("claude-sonnet-4-6");
        assertThat(ctx.get("modelMaxTokens")).isEqualTo(8192);
        assertThat(ctx.get("modelTemperature")).isEqualTo(0.1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildAllowlistEntries() {
        Map<String, Object> ctx = generator.buildTemplateContext(
            manifest, "TestWorker", "testworker", "test.agent.yml");

        var entries = (java.util.List<Map<String, Object>>) ctx.get("allowlistEntries");
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).get("name")).isEqualTo("Read");
        assertThat(entries.get(0).get("hasNext")).isEqualTo(true);
        assertThat(entries.get(2).get("name")).isEqualTo("Glob");
        assertThat(entries.get(2).get("hasNext")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildToolDependencies() {
        Map<String, Object> ctx = generator.buildTemplateContext(
            manifest, "TestWorker", "testworker", "test.agent.yml");

        var deps = (java.util.List<Map<String, String>>) ctx.get("toolDependencies");
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).get("groupId")).isEqualTo("io.github.massimilianopili");
        assertThat(deps.get(0).get("artifactId")).isEqualTo("mcp-filesystem-tools");
    }

    // --- Full generation test ---

    @Test
    void shouldGenerateCompleteModule(@TempDir Path tempDir) throws IOException {
        Path moduleDir = generator.generate(manifest, tempDir, "test.agent.yml");

        // Verify directory structure
        assertThat(moduleDir).isDirectory();
        assertThat(moduleDir.resolve("pom.xml")).isRegularFile();

        Path javaDir = moduleDir.resolve(
            "src/main/java/com/agentframework/workers/generated/testworker");
        assertThat(javaDir.resolve("TestWorker.java")).isRegularFile();
        assertThat(javaDir.resolve("TestWorkerApplication.java")).isRegularFile();

        Path resourcesDir = moduleDir.resolve("src/main/resources");
        assertThat(resourcesDir.resolve("application.yml")).isRegularFile();
    }

    @Test
    void generatedWorkerShouldExtendAbstractWorker(@TempDir Path tempDir) throws IOException {
        generator.generate(manifest, tempDir, "test.agent.yml");

        Path workerFile = tempDir.resolve(
            "test-worker/src/main/java/com/agentframework/workers/generated/testworker/TestWorker.java");
        String content = Files.readString(workerFile);

        assertThat(content).contains("extends AbstractWorker");
        assertThat(content).contains("@Component");
        assertThat(content).contains("@Generated(\"agent-compiler-maven-plugin\")");
        assertThat(content).contains("return \"BE\"");
        assertThat(content).contains("return \"skills/test-worker.agent.md\"");
        assertThat(content).contains("TOOL_ALLOWLIST = List.of(");
        assertThat(content).contains("\"Read\"");
        assertThat(content).contains("\"Write\"");
        assertThat(content).contains("\"Glob\"");
        assertThat(content).contains("buildStandardUserPrompt(context, INSTRUCTIONS)");
    }

    @Test
    void generatedPomShouldContainCorrectDependencies(@TempDir Path tempDir) throws IOException {
        generator.generate(manifest, tempDir, "test.agent.yml");

        Path pomFile = tempDir.resolve("test-worker/pom.xml");
        String content = Files.readString(pomFile);

        assertThat(content).contains("<artifactId>test-worker</artifactId>");
        assertThat(content).contains("<artifactId>worker-sdk</artifactId>");
        assertThat(content).contains("<artifactId>mcp-filesystem-tools</artifactId>");
        assertThat(content).contains("<artifactId>spring-ai-starter-model-anthropic</artifactId>");
        assertThat(content).contains("<artifactId>spring-ai-reactive-tools</artifactId>");
    }

    @Test
    void generatedApplicationYmlShouldContainCorrectConfig(@TempDir Path tempDir) throws IOException {
        generator.generate(manifest, tempDir, "test.agent.yml");

        Path ymlFile = tempDir.resolve("test-worker/src/main/resources/application.yml");
        String content = Files.readString(ymlFile);

        assertThat(content).contains("name: test-worker");
        assertThat(content).contains("task-topic: agent-tasks");
        assertThat(content).contains("task-subscription: test-worker-sub");
        assertThat(content).contains("model: claude-sonnet-4-6");
        assertThat(content).contains("max-tokens: 8192");
    }

    // --- Hand-written source detection tests ---

    @Test
    void shouldSkipJavaGenerationWhenHandWrittenSourcesExist(@TempDir Path tempDir) throws IOException {
        // Pre-create a hand-written worker source (outside generated/ package)
        Path moduleDir = tempDir.resolve("test-worker");
        Path handWrittenDir = moduleDir.resolve(
            "src/main/java/com/agentframework/workers/testworker");
        Files.createDirectories(handWrittenDir);
        Files.writeString(handWrittenDir.resolve("TestWorkerApplication.java"),
            "package com.agentframework.workers.testworker;\n"
            + "@SpringBootApplication\npublic class TestWorkerApplication {}");

        generator.generate(manifest, tempDir, "test.agent.yml");

        // Generated Java files should NOT exist
        Path generatedDir = moduleDir.resolve(
            "src/main/java/com/agentframework/workers/generated/testworker");
        assertThat(generatedDir.resolve("TestWorker.java")).doesNotExist();
        assertThat(generatedDir.resolve("TestWorkerApplication.java")).doesNotExist();

        // Non-Java files should still be generated
        assertThat(moduleDir.resolve("pom.xml")).isRegularFile();
        assertThat(moduleDir.resolve("Dockerfile")).isRegularFile();
        assertThat(moduleDir.resolve("src/main/resources/application.yml")).isRegularFile();
    }

    @Test
    void shouldDetectHandWrittenSources(@TempDir Path tempDir) throws IOException {
        Path moduleDir = tempDir.resolve("test-worker");

        // No sources at all
        assertThat(generator.hasNonGeneratedJavaSources(moduleDir)).isFalse();

        // Only generated sources
        Path generatedDir = moduleDir.resolve(
            "src/main/java/com/agentframework/workers/generated/testworker");
        Files.createDirectories(generatedDir);
        Files.writeString(generatedDir.resolve("TestWorker.java"), "// generated");
        assertThat(generator.hasNonGeneratedJavaSources(moduleDir)).isFalse();

        // Hand-written source added
        Path handWrittenDir = moduleDir.resolve(
            "src/main/java/com/agentframework/workers/testworker");
        Files.createDirectories(handWrittenDir);
        Files.writeString(handWrittenDir.resolve("TestWorker.java"), "// hand-written");
        assertThat(generator.hasNonGeneratedJavaSources(moduleDir)).isTrue();
    }
}
