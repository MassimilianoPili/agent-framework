package com.agentframework.workers.advisory;

import com.agentframework.worker.LlmWorker;
import com.agentframework.worker.WorkerMetadata;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.dto.AgentTask;
import com.agentframework.worker.interceptor.WorkerInterceptor;
import com.agentframework.worker.messaging.WorkerResultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

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
@WorkerMetadata(
    workerType = "MANAGER",
    systemPromptFile = "prompts/council/managers/be-manager.agent.md",
    toolAllowlist = {"Glob", "Grep", "Read"}
)
public class AdvisoryWorker extends LlmWorker {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryWorker.class);

    private static final String ADVISORY_INSTRUCTIONS = """
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

    private static final String ADVISORY_RESULT_SCHEMA = """
            {
              "summary": "...",
              "insights": ["..."],
              "recommendations": ["..."]
            }""";

    public AdvisoryWorker(AgentContextBuilder contextBuilder,
                          WorkerChatClientFactory chatClientFactory,
                          WorkerResultProducer resultProducer,
                          List<WorkerInterceptor> interceptors) {
        super(contextBuilder, chatClientFactory, resultProducer, interceptors);
    }

    @Override
    protected String instructions() {
        return ADVISORY_INSTRUCTIONS;
    }

    @Override
    protected String resultSchema() {
        return ADVISORY_RESULT_SCHEMA;
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
