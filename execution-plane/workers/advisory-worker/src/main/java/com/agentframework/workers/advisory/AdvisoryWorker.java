package com.agentframework.workers.advisory;

import com.agentframework.worker.AbstractWorker;
import com.agentframework.worker.ToolAllowlist;
import com.agentframework.worker.WorkerExecutionException;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.dto.AgentTask;
import com.agentframework.worker.interceptor.WorkerInterceptor;
import com.agentframework.worker.messaging.WorkerResultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Advisory Worker — handles {@code MANAGER} and {@code SPECIALIST} task types.
 *
 * <p>Advisory workers are <strong>read-only</strong>: they may only use {@code Glob},
 * {@code Grep}, and {@code Read} tools. They never write, edit, or execute shell commands.
 * Their purpose is to produce structured domain knowledge (architectural decisions, security
 * constraints, data-modeling guidelines, test strategies) that flows to downstream
 * implementation workers via dependency results.</p>
 *
 * <h3>Profile routing</h3>
 * <p>The system prompt is selected from {@code task.workerProfile()}:</p>
 * <ul>
 *   <li>{@code be-manager}          → {@code prompts/council/managers/be-manager.agent.md}</li>
 *   <li>{@code fe-manager}          → {@code prompts/council/managers/fe-manager.agent.md}</li>
 *   <li>{@code security-manager}    → {@code prompts/council/managers/security-manager.agent.md}</li>
 *   <li>{@code data-manager}        → {@code prompts/council/managers/data-manager.agent.md}</li>
 *   <li>{@code database-specialist} → {@code prompts/council/specialists/database-specialist.agent.md}</li>
 *   <li>{@code auth-specialist}     → {@code prompts/council/specialists/auth-specialist.agent.md}</li>
 *   <li>{@code api-specialist}      → {@code prompts/council/specialists/api-specialist.agent.md}</li>
 *   <li>{@code testing-specialist}  → {@code prompts/council/specialists/testing-specialist.agent.md}</li>
 * </ul>
 *
 * <p>Both MANAGER and SPECIALIST task types are routed to the same {@code agent-advisory}
 * topic/subscription, so a single worker application handles all advisory roles.</p>
 */
@Component
public class AdvisoryWorker extends AbstractWorker {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryWorker.class);

    /**
     * Strict read-only tool allowlist. Advisory workers MUST NOT write or execute.
     */
    private static final List<String> READ_ONLY_TOOLS = List.of("Glob", "Grep", "Read");

    private static final String INSTRUCTIONS = """
            You are a domain advisory expert. Your role is to provide structured, actionable
            guidance based on your area of expertise and a thorough read-only exploration of
            the codebase.

            Use Glob, Grep, and Read tools to understand the existing codebase, architecture,
            conventions, and patterns. Then produce a structured JSON response containing your
            domain insights, decisions, and recommendations.

            Your output will flow directly to downstream implementation workers as dependency
            context. Be specific, concrete, and reference actual files and patterns you found.

            Return your result as a JSON object with fields relevant to your domain (e.g.
            architectureDecisions, securityGuidelines, dataModelingGuidelines, testingStrategy,
            apiDesignGuidelines, insights). Always include a "summary" field with a one-sentence
            overview of your most critical recommendation.""";

    public AdvisoryWorker(AgentContextBuilder contextBuilder,
                          WorkerChatClientFactory chatClientFactory,
                          WorkerResultProducer resultProducer,
                          List<WorkerInterceptor> interceptors) {
        super(contextBuilder, chatClientFactory, resultProducer, interceptors);
    }

    /**
     * Advisory workers handle both MANAGER and SPECIALIST types.
     * The messaging subscription for this worker covers the "agent-advisory" topic.
     * The actual type (MANAGER or SPECIALIST) is in the message property but
     * both route here — workerType() serves as the primary subscription key.
     */
    @Override
    public String workerType() {
        return "MANAGER";
    }

    /**
     * Default system prompt — used only as a fallback if resolveSystemPromptFile() is somehow
     * not overridden or the profile cannot be resolved. In practice, the advisory worker
     * always resolves the profile-specific file via resolveSystemPromptFile().
     */
    @Override
    protected String systemPromptFile() {
        return "prompts/council/managers/be-manager.agent.md";
    }

    /**
     * Routes the system prompt to the profile-specific agent file.
     *
     * <p>Manager profiles use {@code prompts/council/managers/}, specialist profiles use
     * {@code prompts/council/specialists/}.</p>
     */
    @Override
    protected String resolveSystemPromptFile(AgentTask task) {
        String profile = task.workerProfile();
        if (profile == null || profile.isBlank()) {
            log.warn("[{}] Advisory task {} has no workerProfile — using default be-manager prompt",
                     task.workerType(), task.taskKey());
            return "prompts/council/managers/be-manager.agent.md";
        }
        String path = resolveProfilePath(profile);
        log.debug("[{}] Routing advisory task {} (profile={}) to prompt: {}",
                  task.workerType(), task.taskKey(), profile, path);
        return path;
    }

    /**
     * Read-only tool allowlist: Glob, Grep, Read only — no write access.
     */
    @Override
    protected ToolAllowlist toolAllowlist() {
        return new ToolAllowlist.Explicit(READ_ONLY_TOOLS);
    }

    @Override
    protected String execute(AgentContext context, ChatClient chatClient)
            throws WorkerExecutionException {

        String userPrompt = buildStandardUserPrompt(context, INSTRUCTIONS);
        log.info("[ADVISORY] Executing task '{}' with {} dependency result(s)",
                 context.taskKey(), context.dependencyResults().size());

        try {
            ChatResponse chatResponse = chatClient.prompt()
                .system(context.systemPrompt())
                .user(userPrompt)
                .call()
                .chatResponse();

            String response = chatResponse.getResult().getOutput().getText();
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                recordTokenUsage(chatResponse.getMetadata().getUsage());
            }

            log.info("[ADVISORY] Task '{}' completed, response length: {} chars",
                     context.taskKey(), response != null ? response.length() : 0);
            return response;

        } catch (Exception e) {
            throw new WorkerExecutionException(
                "Advisory worker execution failed for task " + context.taskKey(), e);
        }
    }

    /**
     * Maps a worker profile name to its classpath agent file path.
     *
     * <p>Profiles ending in {@code -manager} are routed to {@code managers/},
     * all others (specialists) to {@code specialists/}.</p>
     */
    private static String resolveProfilePath(String profile) {
        String dir = profile.endsWith("-manager") ? "managers" : "specialists";
        return "prompts/council/" + dir + "/" + profile + ".agent.md";
    }
}
