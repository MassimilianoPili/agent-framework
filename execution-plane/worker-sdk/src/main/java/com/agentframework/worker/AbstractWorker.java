package com.agentframework.worker;

import com.agentframework.worker.cache.ContextCacheHolder;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.dto.AgentResult;
import com.agentframework.worker.dto.AgentTask;
import com.agentframework.worker.dto.Provenance;
import com.agentframework.worker.interceptor.WorkerInterceptor;
import com.agentframework.worker.messaging.WorkerResultProducer;
import com.agentframework.worker.policy.PolicyEnforcingToolCallback;
import com.agentframework.worker.util.HashUtil;
import org.springframework.ai.chat.metadata.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Base class for all worker implementations.
 *
 * Subclasses override:
 * - {@link #workerType()} — identifies which Service Bus topic to consume
 * - {@link #systemPromptFile()} — classpath path of the agent's skill file
 * - {@link #execute(AgentContext, ChatClient)} — main work logic
 * - {@link #toolAllowlist()} — (optional) restrict which MCP tools this worker can use
 *
 * The SDK wires a Service Bus listener that calls {@link #process(AgentTask)}
 * automatically on each incoming message.
 *
 * process() is final (template method pattern) to ensure:
 * - Context is always built before execution
 * - Results are always published (success or failure)
 * - Exceptions never escape to the message handler
 */
public abstract class AbstractWorker {

    private static final Logger log = LoggerFactory.getLogger(AbstractWorker.class);

    // ThreadLocal to capture token usage set by generated worker's execute() method.
    // AbstractWorker is a singleton @Component; ThreadLocal prevents cross-task contamination.
    private static final ThreadLocal<Provenance.TokenUsage> TOKEN_USAGE = new ThreadLocal<>();

    /** Called by generated workers after each LLM response to capture token usage. */
    protected void recordTokenUsage(Usage usage) {
        TOKEN_USAGE.set(new Provenance.TokenUsage(
            toLong(usage.getPromptTokens()), toLong(usage.getCompletionTokens()), toLong(usage.getTotalTokens())));
    }

    private static Long toLong(Integer value) {
        return value != null ? value.longValue() : null;
    }

    private final AgentContextBuilder contextBuilder;
    private final WorkerChatClientFactory chatClientFactory;
    private final WorkerResultProducer resultProducer;
    private final List<WorkerInterceptor> interceptors;

    protected AbstractWorker(AgentContextBuilder contextBuilder,
                             WorkerChatClientFactory chatClientFactory,
                             WorkerResultProducer resultProducer) {
        this(contextBuilder, chatClientFactory, resultProducer, List.of());
    }

    protected AbstractWorker(AgentContextBuilder contextBuilder,
                             WorkerChatClientFactory chatClientFactory,
                             WorkerResultProducer resultProducer,
                             List<WorkerInterceptor> interceptors) {
        this.contextBuilder = contextBuilder;
        this.chatClientFactory = chatClientFactory;
        this.resultProducer = resultProducer;
        this.interceptors = interceptors;
    }

    /**
     * Worker type identifier. Must match WorkerType enum name in orchestrator.
     * Used to route tasks from the correct Service Bus topic.
     */
    public abstract String workerType();

    /**
     * Worker profile identifier (e.g., "fe-vanillajs", "be-java").
     * Used by WorkerTaskConsumer to filter incoming messages on shared topics.
     * Returns null for untyped workers (AI_TASK, REVIEW, CONTRACT).
     */
    public String workerProfile() {
        return null;
    }

    /**
     * Classpath path of the Markdown skill file for this agent.
     * Example: "skills/be-worker.agent.md"
     */
    protected abstract String systemPromptFile();

    /**
     * Resolves the system prompt file for the given task.
     *
     * <p>Override this method (instead of {@link #systemPromptFile()}) when the system prompt
     * depends on the incoming task — for example, to route by {@code workerProfile}.
     * The default implementation ignores the task and delegates to {@link #systemPromptFile()}.</p>
     */
    protected String resolveSystemPromptFile(AgentTask task) {
        return systemPromptFile();
    }

    /**
     * Core execution logic.
     *
     * @param context    fully assembled context including system prompt, spec, and dependency results
     * @param chatClient ChatClient pre-configured with MCP tools for this worker type
     * @return JSON string representing the task result/artifacts
     * @throws WorkerExecutionException on unrecoverable failure
     */
    protected abstract String execute(AgentContext context, ChatClient chatClient)
        throws WorkerExecutionException;

    /**
     * MCP tool policy for this worker. Override to restrict which tools are available
     * to this worker's ChatClient.
     *
     * <p>Defaults to {@link ToolAllowlist#ALL} (all auto-discovered tools).
     * Generated workers override with {@link ToolAllowlist.Explicit}.</p>
     */
    protected ToolAllowlist toolAllowlist() {
        return ToolAllowlist.ALL;
    }

    /**
     * Additional skill document paths to compose into the system prompt.
     *
     * <p>Each path is resolved by {@link com.agentframework.worker.context.SkillLoader}
     * (filesystem override first, then classpath). The loaded content is appended to the
     * primary system prompt separated by horizontal rules.</p>
     *
     * <p>Defaults to empty (primary prompt only).
     * Generated workers override with paths declared in their manifest.</p>
     */
    protected List<String> skillPaths() {
        return List.of();
    }

    /**
     * Builds a standard user prompt from the AgentContext and worker-specific instructions.
     * Extracted from the common pattern across all worker implementations.
     *
     * <p>Structure:</p>
     * <ol>
     *   <li>Task title and key</li>
     *   <li>Description (if present)</li>
     *   <li>Specification (if present)</li>
     *   <li>Dependency results (if any)</li>
     *   <li>Worker-specific instructions</li>
     * </ol>
     *
     * @param context      the agent context
     * @param instructions worker-specific instruction text (appended at the end)
     * @return the assembled user prompt
     */
    protected String buildStandardUserPrompt(AgentContext context, String instructions) {
        StringJoiner prompt = new StringJoiner("\n\n");

        prompt.add("# Task: " + context.title());
        prompt.add("## Task Key\n" + context.taskKey());

        if (context.description() != null && !context.description().isBlank()) {
            prompt.add("## Description\n" + context.description());
        }

        if (context.spec() != null && !context.spec().isBlank()) {
            prompt.add("## Specification\n" + context.spec());
        }

        // Council guidance from pre-planning or task-level council session
        if (context.hasCouncilGuidance()) {
            prompt.add("## Council Guidance\n" + context.councilGuidance()
                + "\n\n> The council has deliberated on the architecture for this plan. "
                + "Honor their decisions as hard constraints in your implementation.");
        }

        Map<String, String> deps = context.dependencyResults();
        if (deps != null && !deps.isEmpty()) {
            StringJoiner depSection = new StringJoiner("\n\n");
            depSection.add("## Dependency Results");
            for (Map.Entry<String, String> entry : deps.entrySet()) {
                depSection.add("### " + entry.getKey() + "\n" + entry.getValue());
            }
            prompt.add(depSection.toString());
        }

        prompt.add("## Instructions\n" + instructions);

        return prompt.toString();
    }

    /**
     * Template method: builds context, runs interceptor pipeline, executes, publishes result.
     * Catches all exceptions and publishes a failure AgentResult rather than throwing.
     * TOKEN_USAGE ThreadLocal is always cleaned up in the finally block.
     */
    public final void process(AgentTask task) {
        long startMs = Instant.now().toEpochMilli();
        log.info("[{}] Processing task {} (plan={}, profile={})",
                 workerType(), task.taskKey(), task.planId(), task.workerProfile());

        AgentResult result;
        AgentContext context = null;
        PolicyEnforcingToolCallback.resetToolNames();
        try {
            context = contextBuilder.build(task, resolveSystemPromptFile(task), skillPaths());
            PolicyEnforcingToolCallback.setContextFiles(context.relevantFiles());
            PolicyEnforcingToolCallback.setTaskPolicy(context.policy()); // task-level HookPolicy (may be null)
            PolicyEnforcingToolCallback.setDynamicOwnsPaths(task.dynamicOwnsPaths());

            // Run beforeExecute interceptors
            for (WorkerInterceptor interceptor : interceptors) {
                context = interceptor.beforeExecute(context, task);
            }

            // Check context cache: ContextCacheInterceptor.beforeExecute may have stored a hit.
            // If so, skip the LLM call entirely — same result, zero tokens.
            String cachedResult = ContextCacheHolder.get();
            String resultJson;
            if (cachedResult != null) {
                log.info("[{}] Task {} served from context cache", workerType(), task.taskKey());
                resultJson = cachedResult;
            } else {
                ChatClient chatClient = chatClientFactory.create(workerType(), toolAllowlist());
                resultJson = execute(context, chatClient);
            }

            // Run afterExecute interceptors
            for (WorkerInterceptor interceptor : interceptors) {
                resultJson = interceptor.afterExecute(context, resultJson, task);
            }

            List<String> toolsUsed = PolicyEnforcingToolCallback.drainToolNames();
            String promptHashValue = HashUtil.sha256(context.systemPrompt());
            String skillsHashValue = HashUtil.sha256(context.skillsContent());
            Provenance.TokenUsage tokenUsage = TOKEN_USAGE.get();

            Provenance provenance = new Provenance(
                workerType(), task.workerProfile(),
                task.attemptNumber(),
                task.dispatchAttemptId() != null ? task.dispatchAttemptId().toString() : null,
                task.dispatchedAt(), Instant.now().toString(),
                task.traceId() != null ? task.traceId().toString() : null,
                null,                                       // model: reserved for future use
                toolsUsed.isEmpty() ? null : toolsUsed,
                promptHashValue, skillsHashValue, tokenUsage
            );

            result = new AgentResult(
                task.planId(),
                task.itemId(),
                task.taskKey(),
                true,
                resultJson,
                null,
                Instant.now().toEpochMilli() - startMs,
                workerType(),           // provenance (flat, kept for backward compat)
                task.workerProfile(),   // provenance (flat, kept for backward compat)
                null,                   // modelId: reserved
                promptHashValue,
                provenance,
                tokenUsage != null ? tokenUsage.totalTokens() : null   // tokensUsed
            );
            log.info("[{}] Task {} completed in {}ms (profile={})",
                     workerType(), task.taskKey(), result.durationMs(), task.workerProfile());

        } catch (Exception e) {
            log.error("[{}] Task {} failed: {} (profile={})",
                      workerType(), task.taskKey(), e.getMessage(), task.workerProfile(), e);

            // Run onError interceptors
            for (WorkerInterceptor interceptor : interceptors) {
                try {
                    interceptor.onError(context, e, task);
                } catch (Exception interceptorEx) {
                    log.warn("[{}] Interceptor {} failed in onError: {}",
                             workerType(), interceptor.getClass().getSimpleName(), interceptorEx.getMessage());
                }
            }

            List<String> toolsUsed = PolicyEnforcingToolCallback.drainToolNames();
            String promptHashValue = context != null ? HashUtil.sha256(context.systemPrompt()) : null;

            Provenance provenance = new Provenance(
                workerType(), task.workerProfile(),
                task.attemptNumber(),
                task.dispatchAttemptId() != null ? task.dispatchAttemptId().toString() : null,
                task.dispatchedAt(), Instant.now().toString(),
                task.traceId() != null ? task.traceId().toString() : null,
                null,                                       // model: reserved for future use
                toolsUsed.isEmpty() ? null : toolsUsed,
                promptHashValue, null, null                 // no skillsHash or tokenUsage on failure
            );

            result = new AgentResult(
                task.planId(),
                task.itemId(),
                task.taskKey(),
                false,
                null,
                e.getMessage(),
                Instant.now().toEpochMilli() - startMs,
                workerType(),           // provenance (flat, kept for backward compat)
                task.workerProfile(),   // provenance (flat, kept for backward compat)
                null,                   // modelId: reserved
                null,
                provenance,
                null                    // tokensUsed: unavailable on failure
            );
        } finally {
            ContextCacheHolder.clear();                      // prevent ThreadLocal leak on cache-hit path
            TOKEN_USAGE.remove();                            // always clean up to prevent ThreadLocal leak in thread pools
            PolicyEnforcingToolCallback.clearContextFiles(); // clear read-context allowlist
            PolicyEnforcingToolCallback.clearTaskPolicy();   // clear task-level HookPolicy
            PolicyEnforcingToolCallback.clearDynamicOwnsPaths(); // clear dynamic ownsPaths
        }

        resultProducer.publish(result);
    }
}
