package com.agentframework.worker;

import com.agentframework.worker.cache.ContextCacheHolder;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.dto.AgentResult;
import com.agentframework.worker.dto.AgentTask;
import com.agentframework.worker.dto.FileModificationEvent;
import com.agentframework.worker.dto.Provenance;
import com.agentframework.worker.event.WorkerEventPublisher;
import com.agentframework.worker.interceptor.WorkerInterceptor;
import com.agentframework.worker.messaging.WorkerResultProducer;
import com.agentframework.worker.policy.PolicyEnforcingToolCallback;
import com.agentframework.worker.util.HashUtil;
import org.springframework.ai.chat.metadata.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CancellationException;

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

    // ThreadLocal to capture the first LLM reasoning text (text before the first tool call).
    // Set by subclasses via captureReasoning(). Only the first call per task is stored (idempotent).
    private static final ThreadLocal<String> REASONING = new ThreadLocal<>();

    // G1: ThreadLocal to capture the full LLM conversation JSON for post-mortem debugging.
    // Set by subclasses via captureConversation(). Truncated to 500KB to keep DB payload reasonable.
    private static final ThreadLocal<String> CONVERSATION_LOG = new ThreadLocal<>();

    /** Called by generated workers after each LLM response to capture token usage. */
    protected void recordTokenUsage(Usage usage) {
        TOKEN_USAGE.set(new Provenance.TokenUsage(
            toLong(usage.getPromptTokens()), toLong(usage.getCompletionTokens()), toLong(usage.getTotalTokens())));

        // G6: Emit TOKEN_UPDATE event in real-time
        if (workerEventPublisher != null) {
            try {
                workerEventPublisher.publishTokenUpdate(
                    PolicyEnforcingToolCallback.getCurrentPlanId(),
                    PolicyEnforcingToolCallback.getCurrentTaskKey(),
                    toLong(usage.getPromptTokens()) != null ? toLong(usage.getPromptTokens()) : 0L,
                    toLong(usage.getCompletionTokens()) != null ? toLong(usage.getCompletionTokens()) : 0L,
                    toLong(usage.getTotalTokens()) != null ? toLong(usage.getTotalTokens()) : 0L);
            } catch (Exception ignored) { /* never block task processing */ }
        }
    }

    /**
     * Called by generated workers to capture the first reasoning text block from the LLM response,
     * i.e., the text that appears before the first tool call. Only the first non-blank invocation
     * per task is stored — subsequent calls are ignored (idempotent).
     *
     * <p>Truncated to 2000 characters to keep provenance compact.</p>
     */
    protected void captureReasoning(String text) {
        if (REASONING.get() == null && text != null && !text.isBlank()) {
            REASONING.set(text.length() > 2000 ? text.substring(0, 2000) : text);
        }
    }

    /**
     * Called by generated workers to capture the full LLM conversation for debugging.
     * Accepts pre-serialized JSON (system + user + assistant messages).
     * Truncated to 500KB to keep DB payload reasonable.
     */
    protected void captureConversation(String conversationJson) {
        if (conversationJson != null && !conversationJson.isBlank()) {
            CONVERSATION_LOG.set(conversationJson.length() > 512_000
                ? conversationJson.substring(0, 512_000) : conversationJson);
        }
    }

    private static Long toLong(Integer value) {
        return value != null ? value.longValue() : null;
    }

    /** Default LLM model ID, injected from each worker's application.yml at startup. */
    @Value("${spring.ai.anthropic.chat.options.model:claude-sonnet-4-6}")
    private String defaultModelId;

    /** G6: Optional event publisher for real-time worker events (tool calls, token updates). */
    @Autowired(required = false)
    private WorkerEventPublisher workerEventPublisher;

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
     *
     * <p>Default implementation reads {@link WorkerMetadata#workerType()}.
     * Subclasses may override for dynamic behavior.</p>
     */
    public String workerType() {
        WorkerMetadata meta = getClass().getAnnotation(WorkerMetadata.class);
        if (meta != null) return meta.workerType();
        throw new IllegalStateException(
            "@WorkerMetadata or workerType() override required on " + getClass().getName());
    }

    /**
     * Worker profile identifier (e.g., "fe-vanillajs", "be-java").
     * Used by WorkerTaskConsumer to filter incoming messages on shared topics.
     * Returns null for untyped workers (AI_TASK, REVIEW, CONTRACT).
     *
     * <p>Default implementation reads {@link WorkerMetadata#workerProfile()}.
     * Returns null if the annotation value is empty.</p>
     */
    public String workerProfile() {
        WorkerMetadata meta = getClass().getAnnotation(WorkerMetadata.class);
        if (meta != null && !meta.workerProfile().isEmpty()) return meta.workerProfile();
        return null;
    }

    /**
     * Classpath path of the Markdown skill file for this agent.
     * Example: "skills/be-worker.agent.md"
     *
     * <p>Default implementation reads {@link WorkerMetadata#systemPromptFile()}.
     * Subclasses may override for dynamic behavior.</p>
     */
    protected String systemPromptFile() {
        WorkerMetadata meta = getClass().getAnnotation(WorkerMetadata.class);
        if (meta != null) return meta.systemPromptFile();
        throw new IllegalStateException(
            "@WorkerMetadata or systemPromptFile() override required on " + getClass().getName());
    }

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
     * <p>Default implementation reads {@link WorkerMetadata#toolAllowlist()}.
     * Falls back to {@link ToolAllowlist#ALL} if annotation has empty array.</p>
     */
    protected ToolAllowlist toolAllowlist() {
        WorkerMetadata meta = getClass().getAnnotation(WorkerMetadata.class);
        if (meta != null && meta.toolAllowlist().length > 0) {
            return new ToolAllowlist.Explicit(List.of(meta.toolAllowlist()));
        }
        return ToolAllowlist.ALL;
    }

    /**
     * Resolves the effective tool allowlist by merging the worker's static
     * policy with the planner's toolHints from the task.
     *
     * <p>Priority: toolHints from planner &gt; worker static allowlist &gt; ALL (default).
     * If toolHints is non-empty, it becomes the {@link ToolAllowlist.Explicit} allowlist.
     * If toolHints is empty/null, falls back to the worker's {@link #toolAllowlist()}.</p>
     */
    private ToolAllowlist resolveToolAllowlist(AgentTask task) {
        if (task.toolHints() != null && !task.toolHints().isEmpty()) {
            return new ToolAllowlist.Explicit(task.toolHints());
        }
        return toolAllowlist();
    }

    /**
     * Additional skill document paths to compose into the system prompt.
     *
     * <p>Each path is resolved by {@link com.agentframework.worker.context.SkillLoader}
     * (filesystem override first, then classpath). The loaded content is appended to the
     * primary system prompt separated by horizontal rules.</p>
     *
     * <p>Default implementation reads {@link WorkerMetadata#skillPaths()}.
     * Falls back to empty list if annotation has empty array.</p>
     */
    protected List<String> skillPaths() {
        WorkerMetadata meta = getClass().getAnnotation(WorkerMetadata.class);
        if (meta != null && meta.skillPaths().length > 0) {
            return List.of(meta.skillPaths());
        }
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

        // Workspace path for shared file access (#44)
        if (context.workspacePath() != null && !context.workspacePath().isBlank()) {
            prompt.add("## Workspace\nWrite all generated files under: " + context.workspacePath()
                + "\nThis directory is shared with other workers in this plan. "
                + "Use absolute paths when calling file system tools.");
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
        PolicyEnforcingToolCallback.resetFileMods();
        // G6: Set plan/task context for event publishing
        PolicyEnforcingToolCallback.setCurrentContext(task.planId(), task.taskKey());
        if (workerEventPublisher != null) {
            PolicyEnforcingToolCallback.setEventPublisher(workerEventPublisher);
        }
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
                // Check cancellation BEFORE starting the LLM call (especially useful in in-process mode
                // where Future.cancel(true) interrupts the virtual thread between tool calls).
                if (Thread.interrupted()) {
                    throw new InterruptedException("Task " + task.taskKey() + " cancelled before LLM call");
                }
                ChatClient chatClient = chatClientFactory.create(workerType(), resolveToolAllowlist(task), task.modelId());
                resultJson = execute(context, chatClient);
            }

            // Run afterExecute interceptors
            for (WorkerInterceptor interceptor : interceptors) {
                resultJson = interceptor.afterExecute(context, resultJson, task);
            }

            List<String> toolsUsed = PolicyEnforcingToolCallback.drainToolNames();
            List<FileModificationEvent> fileMods = PolicyEnforcingToolCallback.drainFileMods();
            String promptHashValue = HashUtil.sha256(context.systemPrompt());
            String skillsHashValue = HashUtil.sha256(context.skillsContent());
            Provenance.TokenUsage tokenUsage = TOKEN_USAGE.get();
            String resolvedModel = (task.modelId() != null && !task.modelId().isBlank())
                ? task.modelId() : defaultModelId;

            Provenance provenance = new Provenance(
                workerType(), task.workerProfile(),
                task.attemptNumber(),
                task.dispatchAttemptId() != null ? task.dispatchAttemptId().toString() : null,
                task.dispatchedAt(), Instant.now().toString(),
                task.traceId() != null ? task.traceId().toString() : null,
                resolvedModel,                              // actual LLM model used
                toolsUsed.isEmpty() ? null : toolsUsed,
                promptHashValue, skillsHashValue, tokenUsage,
                REASONING.get()                             // first LLM reasoning text (null if not captured)
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
                resolvedModel,          // actual model used (haiku/sonnet/opus)
                promptHashValue,
                provenance,
                tokenUsage != null ? tokenUsage.totalTokens() : null,  // tokensUsed
                CONVERSATION_LOG.get(),  // G1: full LLM conversation (nullable)
                fileMods.isEmpty() ? null : fileMods  // G3: file modifications (nullable)
            );
            log.info("[{}] Task {} completed in {}ms (profile={}, files={})",
                     workerType(), task.taskKey(), result.durationMs(), task.workerProfile(), fileMods.size());

        } catch (InterruptedException | CancellationException e) {
            // Task was cancelled (via Future.cancel(true) in in-process mode or CancellationException
            // from PolicyEnforcingToolCallback). Restore interrupt flag and publish a CANCELLED result.
            Thread.currentThread().interrupt();
            log.info("[{}] Task {} was cancelled (profile={})", workerType(), task.taskKey(), task.workerProfile());

            List<String> toolsUsed = PolicyEnforcingToolCallback.drainToolNames();
            String resolvedModel = (task.modelId() != null && !task.modelId().isBlank())
                ? task.modelId() : defaultModelId;

            Provenance provenance = new Provenance(
                workerType(), task.workerProfile(),
                task.attemptNumber(),
                task.dispatchAttemptId() != null ? task.dispatchAttemptId().toString() : null,
                task.dispatchedAt(), Instant.now().toString(),
                task.traceId() != null ? task.traceId().toString() : null,
                resolvedModel,
                toolsUsed.isEmpty() ? null : toolsUsed,
                null, null, null, null
            );

            result = new AgentResult(
                task.planId(), task.itemId(), task.taskKey(),
                false, null, "CANCELLED",
                Instant.now().toEpochMilli() - startMs,
                workerType(), task.workerProfile(), resolvedModel,
                null, provenance, null, null, null
            );

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
            String resolvedModel = (task.modelId() != null && !task.modelId().isBlank())
                ? task.modelId() : defaultModelId;

            Provenance provenance = new Provenance(
                workerType(), task.workerProfile(),
                task.attemptNumber(),
                task.dispatchAttemptId() != null ? task.dispatchAttemptId().toString() : null,
                task.dispatchedAt(), Instant.now().toString(),
                task.traceId() != null ? task.traceId().toString() : null,
                resolvedModel,                              // actual LLM model attempted
                toolsUsed.isEmpty() ? null : toolsUsed,
                promptHashValue, null, null,                // no skillsHash or tokenUsage on failure
                null                                        // no reasoning captured on failure path
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
                resolvedModel,          // actual model attempted
                null,
                provenance,
                null,                   // tokensUsed: unavailable on failure
                CONVERSATION_LOG.get(), // G1: capture partial conversation even on failure
                null                    // G3: fileModifications unavailable on failure
            );
        } finally {
            ContextCacheHolder.clear();                      // prevent ThreadLocal leak on cache-hit path
            TOKEN_USAGE.remove();                            // always clean up to prevent ThreadLocal leak in thread pools
            REASONING.remove();                              // always clean up to prevent stale reasoning in reused threads
            CONVERSATION_LOG.remove();                       // G1: clean up conversation log
            PolicyEnforcingToolCallback.clearFileMods();       // G3: clear file mod accumulator
            PolicyEnforcingToolCallback.clearContextFiles(); // clear read-context allowlist
            PolicyEnforcingToolCallback.clearTaskPolicy();   // clear task-level HookPolicy
            PolicyEnforcingToolCallback.clearDynamicOwnsPaths(); // clear dynamic ownsPaths
            PolicyEnforcingToolCallback.clearCurrentContext();     // G6: clear plan/task context
            PolicyEnforcingToolCallback.clearEventPublisher();     // G6: clear event publisher
        }

        resultProducer.publish(result);
    }
}
