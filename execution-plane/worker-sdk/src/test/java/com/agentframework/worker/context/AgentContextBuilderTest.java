package com.agentframework.worker.context;

import com.agentframework.worker.dto.AgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentContextBuilder}.
 *
 * <p>Verifies that dependency results are correctly parsed from contextJson
 * and that skill composition (primary + additional skills) works as expected.</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentContextBuilderTest {

    @Mock
    private SkillLoader skillLoader;

    private AgentContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AgentContextBuilder(skillLoader, new ObjectMapper());
    }

    @Test
    @DisplayName("build() parses contextJson into depResults map")
    void build_parsesContextJsonIntoDependencyResults() throws Exception {
        Map<String, String> deps = Map.of(
                "CM-001", "{\"relevant_files\":[\"src/Foo.java\"]}",
                "BE-001", "{\"summary\":\"done\"}"
        );
        String contextJson = new ObjectMapper().writeValueAsString(deps);

        when(skillLoader.load("agents/be-java/SKILL.md")).thenReturn("You are a Java expert.");

        AgentTask task = makeTask(contextJson);
        AgentContext ctx = builder.build(task, "agents/be-java/SKILL.md");

        assertThat(ctx.dependencyResults())
                .containsKey("CM-001")
                .containsKey("BE-001");
        assertThat(ctx.dependencyResults().get("CM-001"))
                .contains("relevant_files");
    }

    @Test
    @DisplayName("build() extracts relevant_files from CONTEXT_MANAGER dependency result")
    void build_extractsRelevantFilesFromContextManagerResult() throws Exception {
        Map<String, String> deps = Map.of(
                "CM-001", "{\"relevant_files\":[\"src/Foo.java\",\"src/Bar.java\"]}"
        );
        String contextJson = new ObjectMapper().writeValueAsString(deps);

        when(skillLoader.load(anyString())).thenReturn("system prompt");

        AgentTask task = makeTask(contextJson);
        AgentContext ctx = builder.build(task, "any/SKILL.md");

        assertThat(ctx.relevantFiles())
                .containsExactlyInAnyOrder("src/Foo.java", "src/Bar.java");
    }

    @Test
    @DisplayName("build() returns empty depResults when contextJson is null")
    void build_returnsEmptyDepResultsWhenContextJsonIsNull() {
        when(skillLoader.load(anyString())).thenReturn("system prompt");

        AgentTask task = makeTask(null);
        AgentContext ctx = builder.build(task, "any/SKILL.md");

        assertThat(ctx.dependencyResults()).isEmpty();
        assertThat(ctx.relevantFiles()).isEmpty();
    }

    @Test
    @DisplayName("build() returns empty depResults when contextJson is malformed JSON")
    void build_returnsEmptyDepResultsWhenContextJsonIsMalformed() {
        when(skillLoader.load(anyString())).thenReturn("system prompt");

        AgentTask task = makeTask("not-valid-json{{");
        AgentContext ctx = builder.build(task, "any/SKILL.md");

        // Fail-safe: malformed JSON should not crash, return empty map
        assertThat(ctx.dependencyResults()).isEmpty();
    }

    @Test
    @DisplayName("build() composes system prompt with additional skill files")
    void build_composesSystemPromptWithAdditionalSkills() {
        when(skillLoader.load("agents/be-java/SKILL.md")).thenReturn("Primary skill.");
        when(skillLoader.load("skills/crosscutting/git.md")).thenReturn("Git skill.");
        when(skillLoader.load("skills/crosscutting/testing.md")).thenReturn("Testing skill.");

        AgentTask task = makeTask(null);
        AgentContext ctx = builder.build(task, "agents/be-java/SKILL.md",
                List.of("skills/crosscutting/git.md", "skills/crosscutting/testing.md"));

        assertThat(ctx.systemPrompt())
                .contains("Primary skill.")
                .contains("Git skill.")
                .contains("Testing skill.")
                .contains("---"); // separator between skills
    }

    @Test
    @DisplayName("build() deduplicates relevant_files across multiple CONTEXT_MANAGER results")
    void build_deduplicatesRelevantFilesAcrossMultipleDependencies() throws Exception {
        Map<String, String> deps = Map.of(
                "CM-001", "{\"relevant_files\":[\"src/Foo.java\",\"src/Bar.java\"]}",
                "CM-002", "{\"relevant_files\":[\"src/Bar.java\",\"src/Baz.java\"]}"  // Bar duplicated
        );
        String contextJson = new ObjectMapper().writeValueAsString(deps);

        when(skillLoader.load(anyString())).thenReturn("system prompt");

        AgentTask task = makeTask(contextJson);
        AgentContext ctx = builder.build(task, "any/SKILL.md");

        // src/Bar.java appears in both CM-001 and CM-002 — should appear only once
        assertThat(ctx.relevantFiles())
                .containsExactlyInAnyOrder("src/Foo.java", "src/Bar.java", "src/Baz.java")
                .hasSize(3);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private AgentTask makeTask(String contextJson) {
        return new AgentTask(
                UUID.randomUUID(),   // planId
                UUID.randomUUID(),   // itemId
                "BE-002",            // taskKey
                "Implement feature", // title
                "Description",       // description
                "BE_JAVA",           // workerType
                "be-java",           // workerProfile
                "spec snippet",      // specSnippet
                contextJson,         // contextJson
                1,                   // attemptNumber
                null,                // dispatchAttemptId
                null,                // traceId
                null,                // dispatchedAt
                null,                // policy
                null,                // policyHash
                null,                // councilContext
                null,                // dynamicOwnsPaths
                null,                // toolHints
                null,                // workspacePath
                null                 // modelId
        );
    }
}
