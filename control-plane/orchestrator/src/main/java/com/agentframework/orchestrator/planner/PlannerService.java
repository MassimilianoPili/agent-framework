package com.agentframework.orchestrator.planner;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.PheromoneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls Claude (planner agent) to decompose a natural language specification
 * into an ordered list of PlanItems with dependencies.
 *
 * Flow:
 * 1. Load system prompt from planner.agent.md
 * 2. Render user prompt from plan_tasks.prompt.md with spec interpolation
 * 3. BeanOutputConverter<PlanSchema> generates JSON Schema for structured output
 * 4. Claude responds with valid JSON matching the schema
 * 5. Convert response to PlanItem JPA entities
 */
@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final Optional<PheromoneService> pheromoneService;

    public PlannerService(ChatClient chatClient, PromptLoader promptLoader,
                          Optional<PheromoneService> pheromoneService) {
        this.chatClient = chatClient;
        this.promptLoader = promptLoader;
        this.pheromoneService = pheromoneService;
    }

    /**
     * Decomposes the plan's spec into PlanItems by calling Claude.
     * Modifies the plan in-place: adds items to plan.getItems().
     */
    public Plan decompose(Plan plan) {
        log.info("Calling planner agent for plan {}", plan.getId());

        String systemPrompt = promptLoader.load("prompts/planner.agent.md");
        String userPrompt = promptLoader.renderPlanTasksPrompt(plan.getSpec(), plan.getCouncilReport());

        // Append pheromone-based workflow hints when ACO is enabled
        String pheromoneHints = pheromoneService
                .map(PheromoneService::formatHintsForPlanner)
                .orElse("");
        if (!pheromoneHints.isEmpty()) {
            userPrompt += "\n\n## Learned Workflow Patterns\n" + pheromoneHints;
        }

        BeanOutputConverter<PlanSchema> converter = new BeanOutputConverter<>(PlanSchema.class);

        String rawResponse = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt + "\n\n" + converter.getFormat())
            .call()
            .content();

        String rawJson = stripMarkdownFences(rawResponse);
        PlanSchema schema = converter.convert(rawJson);

        if (schema == null || schema.tasks() == null || schema.tasks().isEmpty()) {
            throw new RuntimeException("Planner returned empty plan for spec: " +
                                       plan.getSpec().substring(0, Math.min(100, plan.getSpec().length())));
        }

        List<PlanItem> items = mapSchemaToItems(plan, schema);
        for (PlanItem item : items) {
            plan.addItem(item);
        }

        log.info("Planner decomposed spec into {} items (plan={})", items.size(), plan.getId());
        return plan;
    }

    private static final Pattern FENCED_JSON = Pattern.compile(
            "```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);
    private static final Pattern OPEN_FENCE = Pattern.compile(
            "^```(?:json)?\\s*\\n?", Pattern.DOTALL);

    public static String stripMarkdownFences(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        // Try full fence (opening + closing ```)
        Matcher m = FENCED_JSON.matcher(trimmed);
        if (m.find()) {
            return m.group(1).strip();
        }
        // Fallback: opening fence only (truncated response — no closing ```)
        Matcher openM = OPEN_FENCE.matcher(trimmed);
        if (openM.find()) {
            return trimmed.substring(openM.end()).strip();
        }
        return trimmed;
    }

    private List<PlanItem> mapSchemaToItems(Plan plan, PlanSchema schema) {
        List<PlanItem> result = new ArrayList<>();
        int ordinal = 0;

        for (PlanItemSchema s : schema.tasks()) {
            WorkerType workerType;
            try {
                workerType = WorkerType.valueOf(s.workerType());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown worker type '{}' in planner output, skipping task {}",
                         s.workerType(), s.taskKey());
                continue;
            }

            PlanItem item = new PlanItem(
                UUID.randomUUID(),
                ordinal++,
                s.taskKey(),
                s.title(),
                s.description(),
                workerType,
                s.workerProfile(),
                s.dependsOn() != null ? s.dependsOn() : List.of(),
                s.toolHints()
            );
            item.setModelId(s.modelId());   // optional LLM model override (#20)
            result.add(item);
        }

        return result;
    }
}
