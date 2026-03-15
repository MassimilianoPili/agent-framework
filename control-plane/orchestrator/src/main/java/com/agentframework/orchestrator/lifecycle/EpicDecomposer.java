package com.agentframework.orchestrator.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hybrid HTN + LLM epic decomposition.
 *
 * <p>Decomposes a project-level specification into a list of epics,
 * each representing a coherent unit of work that maps to one or more plans.</p>
 *
 * <p>Strategy: first attempts template matching via known archetypes
 * (HTN-style), falls back to LLM decomposition for novel project types.
 * This hybrid approach (ChatHTN pattern) reduces LLM cost for common patterns
 * while preserving flexibility for novel requests.</p>
 *
 * <p>Principles (from HTN literature):</p>
 * <ul>
 *   <li><b>Solvability</b>: each epic must be decomposable into concrete plans</li>
 *   <li><b>Completeness</b>: epics cover the full project spec</li>
 *   <li><b>Non-redundancy</b>: no overlapping scope between epics</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "lifecycle", name = "enabled", havingValue = "true", matchIfMissing = false)
public class EpicDecomposer {

    private static final Logger log = LoggerFactory.getLogger(EpicDecomposer.class);

    private final LifecycleConfig config;

    public EpicDecomposer(LifecycleConfig config) {
        this.config = config;
    }

    /**
     * Decomposes a project specification into epics.
     *
     * @param projectSpec the project-level specification
     * @return ordered list of epics
     */
    public List<Epic> decompose(String projectSpec) {
        // Try template-based decomposition first
        List<Epic> templateResult = tryTemplateDecomposition(projectSpec);
        if (!templateResult.isEmpty()) {
            log.info("Template decomposition produced {} epics", templateResult.size());
            return limitEpics(templateResult);
        }

        // Fall back to heuristic decomposition
        // (Full LLM decomposition via ChatClient would be wired here)
        List<Epic> heuristicResult = heuristicDecomposition(projectSpec);
        log.info("Heuristic decomposition produced {} epics", heuristicResult.size());
        return limitEpics(heuristicResult);
    }

    /**
     * Attempts template-based decomposition using known project archetypes.
     *
     * <p>When PlanArchetypeRegistry is integrated, this will match against
     * known archetypes and return pre-defined epic structures. Currently
     * returns empty (no templates available).</p>
     */
    List<Epic> tryTemplateDecomposition(String projectSpec) {
        // Placeholder for PlanArchetypeRegistry integration
        // Returns empty to fall through to heuristic/LLM
        return List.of();
    }

    /**
     * Heuristic decomposition: splits spec into logical sections.
     *
     * <p>Identifies epic boundaries from:</p>
     * <ul>
     *   <li>Numbered lists in the spec</li>
     *   <li>Section headers (markdown-style)</li>
     *   <li>Paragraph boundaries with distinct topics</li>
     * </ul>
     */
    List<Epic> heuristicDecomposition(String projectSpec) {
        List<Epic> epics = new ArrayList<>();
        String[] sections = projectSpec.split("(?m)^#{1,3}\\s+|\\n\\d+\\.\\s+|\\n\\n+");

        int ordinal = 0;
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty() || trimmed.length() < 10) continue;

            // Extract a name from the first line
            String name = extractEpicName(trimmed);
            String spec = trimmed;

            epics.add(new Epic(
                    UUID.randomUUID(),
                    name,
                    spec,
                    ordinal++,
                    estimateComplexity(spec),
                    null));
        }

        // If no sections found, treat entire spec as single epic
        if (epics.isEmpty()) {
            epics.add(new Epic(
                    UUID.randomUUID(),
                    "Main Implementation",
                    projectSpec,
                    0,
                    estimateComplexity(projectSpec),
                    null));
        }

        return epics;
    }

    private String extractEpicName(String section) {
        String firstLine = section.split("\\n")[0].trim();
        if (firstLine.length() > 80) {
            firstLine = firstLine.substring(0, 77) + "...";
        }
        return firstLine;
    }

    private Epic.Complexity estimateComplexity(String spec) {
        int length = spec.length();
        if (length > 2000) return Epic.Complexity.HIGH;
        if (length > 500) return Epic.Complexity.MEDIUM;
        return Epic.Complexity.LOW;
    }

    private List<Epic> limitEpics(List<Epic> epics) {
        int max = config.decomposition().maxEpicsPerProject();
        if (epics.size() <= max) return epics;
        return epics.subList(0, max);
    }

    // --- Types ---

    /**
     * An epic within a project: a coherent unit of work decomposable into plans.
     *
     * @param id              epic UUID
     * @param name            short name
     * @param specification   detailed spec for plan generation
     * @param ordinal         execution order within project
     * @param complexity      estimated complexity
     * @param compensatingSpec natural language spec for saga compensation (nullable)
     */
    public record Epic(
            UUID id,
            String name,
            String specification,
            int ordinal,
            Complexity complexity,
            @Nullable String compensatingSpec
    ) {
        public enum Complexity { LOW, MEDIUM, HIGH }
    }
}
