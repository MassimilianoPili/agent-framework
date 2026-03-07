package com.agentframework.worker.context;

import com.agentframework.worker.dto.AgentTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds an AgentContext from an incoming AgentTask.
 *
 * Responsibilities:
 * 1. Loads system prompt from the worker's skill Markdown file
 * 2. Parses the contextJson from AgentTask into a dependency results map
 * 3. Assembles all fields into an immutable AgentContext record
 */
@Component
public class AgentContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(AgentContextBuilder.class);

    private final SkillLoader skillLoader;
    private final ObjectMapper objectMapper;

    public AgentContextBuilder(SkillLoader skillLoader, ObjectMapper objectMapper) {
        this.skillLoader = skillLoader;
        this.objectMapper = objectMapper;
    }

    public AgentContext build(AgentTask task, String systemPromptFile) {
        return build(task, systemPromptFile, null);
    }

    /**
     * Builds an AgentContext with optional additional skill composition.
     *
     * <p>When {@code additionalSkillPaths} is provided, each skill file is loaded
     * and appended to the system prompt (separated by horizontal rules). This enables
     * generated workers to compose their system prompt from a primary agent profile
     * plus additional skill documents, as declared in their manifest.</p>
     *
     * @param task                 the incoming agent task
     * @param systemPromptFile     primary system prompt file path
     * @param additionalSkillPaths optional list of extra skill file paths to compose
     */
    public AgentContext build(AgentTask task, String systemPromptFile,
                              List<String> additionalSkillPaths) {
        String systemPrompt = skillLoader.load(systemPromptFile);

        if (additionalSkillPaths != null && !additionalSkillPaths.isEmpty()) {
            StringBuilder composed = new StringBuilder(systemPrompt);
            for (String path : additionalSkillPaths) {
                composed.append("\n\n---\n\n").append(skillLoader.load(path));
            }
            systemPrompt = composed.toString();
            log.debug("Composed system prompt from {} + {} additional skill(s) for task {}",
                       systemPromptFile, additionalSkillPaths.size(), task.taskKey());
        }

        Map<String, String> depResults;
        try {
            if (task.contextJson() != null && !task.contextJson().isBlank()) {
                depResults = objectMapper.readValue(
                    task.contextJson(),
                    new TypeReference<Map<String, String>>() {}
                );
            } else {
                depResults = Map.of();
            }
        } catch (Exception e) {
            log.warn("Failed to parse contextJson for task {}: {}", task.taskKey(), e.getMessage());
            depResults = Map.of();
        }

        return new AgentContext(
            task.planId(),
            task.itemId(),
            task.taskKey(),
            task.title(),
            task.description(),
            task.specSnippet(),
            systemPrompt,
            depResults,
            "",
            extractRelevantFiles(depResults),
            task.policy(),
            task.councilContext()
        );
    }

    /**
     * Extracts the list of relevant file paths from CONTEXT_MANAGER dependency results.
     *
     * <p>CONTEXT_MANAGER tasks produce results with key ending in "-ctx" (e.g. "CM-001-ctx"
     * is not the convention — the key IS the taskKey "CM-001" and its result JSON contains
     * a {@code relevant_files} array). This method scans all dependency results for entries
     * whose JSON contains a {@code relevant_files} array and merges them.</p>
     *
     * @param depResults map of taskKey → result JSON from dependency tasks
     * @return deduplicated list of relevant file paths, empty if none found
     */
    private List<String> extractRelevantFiles(Map<String, String> depResults) {
        if (depResults == null || depResults.isEmpty()) {
            return List.of();
        }
        return depResults.values().stream()
            .flatMap(resultJson -> {
                try {
                    JsonNode root = objectMapper.readTree(resultJson);
                    JsonNode files = root.get("relevant_files");
                    if (files == null || !files.isArray()) return Stream.empty();
                    List<String> paths = new ArrayList<>();
                    files.forEach(f -> {
                        if (f.isTextual() && !f.asText().isBlank()) {
                            paths.add(f.asText());
                        }
                    });
                    return paths.stream();
                } catch (Exception e) {
                    log.debug("Could not parse relevant_files from dependency result: {}", e.getMessage());
                    return Stream.empty();
                }
            })
            .distinct()
            .toList();
    }
}
