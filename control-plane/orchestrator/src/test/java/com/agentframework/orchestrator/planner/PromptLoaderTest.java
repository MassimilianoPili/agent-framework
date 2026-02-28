package com.agentframework.orchestrator.planner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PromptLoader}.
 * Verifies classpath loading, caching, and error handling.
 */
class PromptLoaderTest {

    private final PromptLoader loader = new PromptLoader();

    @Test
    void load_existingResource_returnsContent() {
        // plan_tasks.prompt.md exists in src/main/resources/prompts/
        String content = loader.load("prompts/plan_tasks.prompt.md");
        assertThat(content).isNotBlank();
    }

    @Test
    void load_missingResource_throwsWithClearMessage() {
        assertThatThrownBy(() -> loader.load("prompts/nonexistent-file.md"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("not found on classpath")
            .hasMessageContaining("nonexistent-file.md");
    }

    @Test
    void load_cachedOnSecondCall_returnsSameInstance() {
        String first = loader.load("prompts/plan_tasks.prompt.md");
        String second = loader.load("prompts/plan_tasks.prompt.md");
        // ConcurrentHashMap.computeIfAbsent guarantees same reference
        assertThat(first).isSameAs(second);
    }

    @Test
    void validateRequiredPrompts_doesNotThrow() {
        // @PostConstruct logs warnings for missing files but does not throw
        loader.validateRequiredPrompts();
        // If we reach here, validation did not throw — which is correct:
        // some prompt files may be missing during development
    }

    @Test
    void renderPlanTasksPrompt_substitutesSpec() {
        String rendered = loader.renderPlanTasksPrompt("Build a REST API");
        assertThat(rendered).contains("Build a REST API");
        assertThat(rendered).doesNotContain("{{SPEC}}");
    }
}
