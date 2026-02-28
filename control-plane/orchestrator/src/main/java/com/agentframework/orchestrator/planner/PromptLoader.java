package com.agentframework.orchestrator.planner;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads Markdown prompt and agent profile files from the classpath.
 * Files are expected at src/main/resources/prompts/.
 *
 * Results are cached in memory — prompts are immutable at runtime.
 */
@Component
public class PromptLoader {

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Loads a prompt file by classpath path.
     * Example: load("prompts/planner.agent.md")
     */
    public String load(String classpathPath) {
        return cache.computeIfAbsent(classpathPath, path -> {
            try {
                ClassPathResource resource = new ClassPathResource(path);
                return resource.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Cannot load prompt file: " + path, e);
            }
        });
    }

    /**
     * Renders the plan_tasks.prompt.md template, substituting {{SPEC}} with the actual spec.
     */
    public String renderPlanTasksPrompt(String spec) {
        String template = load("prompts/plan_tasks.prompt.md");
        return template.replace("{{SPEC}}", spec);
    }

    /**
     * Renders the quality_gate_report.prompt.md template.
     */
    public String renderQualityGatePrompt(String planId, String allResultsJson, String coverageThreshold) {
        String template = load("prompts/quality_gate_report.prompt.md");
        return template
            .replace("{{PLAN_ID}}", planId)
            .replace("{{ALL_RESULTS_JSON}}", allResultsJson)
            .replace("{{COVERAGE_THRESHOLD}}", coverageThreshold);
    }
}
