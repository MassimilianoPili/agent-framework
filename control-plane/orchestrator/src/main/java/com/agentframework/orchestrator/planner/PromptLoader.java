package com.agentframework.orchestrator.planner;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads Markdown prompt and agent profile files from the classpath.
 * Files are expected at src/main/resources/prompts/.
 *
 * Results are cached in memory — prompts are immutable at runtime.
 */
@Component
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    /** Prompt files that are expected to exist for the orchestrator to function fully. */
    private static final List<String> REQUIRED_PROMPTS = List.of(
        "prompts/planner.agent.md",
        "prompts/review.agent.md",
        "prompts/plan_tasks.prompt.md",
        "prompts/quality_gate_report.prompt.md"
    );

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void validateRequiredPrompts() {
        int missing = 0;
        for (String path : REQUIRED_PROMPTS) {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.warn("Required prompt file missing from classpath: {} — "
                         + "feature will fail at first use", path);
                missing++;
            }
        }
        if (missing > 0) {
            log.warn("{} of {} required prompt files are missing from classpath",
                     missing, REQUIRED_PROMPTS.size());
        } else {
            log.info("All {} required prompt files validated on classpath", REQUIRED_PROMPTS.size());
        }
    }

    /**
     * Loads a prompt file by classpath path.
     * Example: load("prompts/planner.agent.md")
     */
    public String load(String classpathPath) {
        return cache.computeIfAbsent(classpathPath, path -> {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                throw new RuntimeException(
                    "Prompt file not found on classpath: '" + path + "'. "
                    + "Ensure the file exists in src/main/resources/" + path);
            }
            try {
                return resource.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read prompt file: " + path, e);
            }
        });
    }

    /**
     * Renders the plan_tasks.prompt.md template, substituting {{SPEC}} with the actual spec.
     * Council guidance section is omitted when councilReport is null or blank.
     */
    public String renderPlanTasksPrompt(String spec, String councilReport) {
        String template = load("prompts/plan_tasks.prompt.md");
        String rendered = template.replace("{{SPEC}}", spec);
        if (councilReport != null && !councilReport.isBlank()) {
            rendered = rendered.replace("{{COUNCIL_GUIDANCE}}", councilReport);
        } else {
            // Remove the entire Council Guidance section so the planner receives a clean prompt
            rendered = rendered.replace("\n## Council Guidance\n\n{{COUNCIL_GUIDANCE}}\n", "");
        }
        return rendered;
    }

    /**
     * Renders the plan_tasks.prompt.md template without council guidance (backward compat).
     */
    public String renderPlanTasksPrompt(String spec) {
        return renderPlanTasksPrompt(spec, null);
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
