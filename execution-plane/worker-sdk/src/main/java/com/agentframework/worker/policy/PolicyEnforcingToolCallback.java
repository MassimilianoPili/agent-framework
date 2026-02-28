package com.agentframework.worker.policy;

import com.agentframework.common.policy.HookPolicy;
import com.agentframework.worker.policy.ToolAuditLogger.Outcome;
import com.agentframework.worker.policy.ToolAuditLogger.ToolAuditEvent;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decorator that wraps a {@link ToolCallback} with policy enforcement.
 *
 * <p>Applied transparently by {@code WorkerChatClientFactory} when the policy
 * layer is active. The LLM sees the same tool definitions; only the execution
 * path is intercepted for ownership checks and audit logging.</p>
 *
 * <p>Enforcement behavior:</p>
 * <ul>
 *   <li><b>Path ownership</b>: write tools targeting paths outside {@code ownsPaths}
 *       return a JSON error (no exception) so the LLM can adapt</li>
 *   <li><b>Audit</b>: every tool call is logged with outcome, timing, and optional
 *       input preview via the {@code audit.tools} logger</li>
 * </ul>
 */
public class PolicyEnforcingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final PathOwnershipEnforcer enforcer;
    private final ToolAuditLogger auditLogger;
    private final String workerType;
    private final String workerProfile;

    // Accumulates tool names called during a single task execution (per-thread).
    // PolicyEnforcingToolCallback instances are created per-task, but the ThreadLocal
    // allows AbstractWorker.process() to drain the list after execute() completes.
    private static final ThreadLocal<List<String>> TOOL_NAMES = ThreadLocal.withInitial(ArrayList::new);

    /** Resets the tool-name accumulator for the current thread. Call before execute(). */
    public static void resetToolNames() { TOOL_NAMES.get().clear(); }

    /** Drains and returns tool names accumulated since the last reset. Clears the list. */
    public static List<String> drainToolNames() {
        List<String> names = new ArrayList<>(TOOL_NAMES.get());
        TOOL_NAMES.get().clear();
        return names;
    }

    // Holds the relevant-files allowlist provided by CONTEXT_MANAGER for the current task.
    // Set by AbstractWorker.process() after AgentContext is built; cleared in the finally block.
    private static final ThreadLocal<List<String>> CONTEXT_FILES = ThreadLocal.withInitial(List::of);

    /** Sets the context file allowlist for Read enforcement. Call after AgentContext is built. */
    public static void setContextFiles(List<String> files) {
        CONTEXT_FILES.set(files != null ? List.copyOf(files) : List.of());
    }

    /** Clears the context file allowlist. Call in finally to prevent ThreadLocal leaks. */
    public static void clearContextFiles() { CONTEXT_FILES.remove(); }

    // Holds the task-level HookPolicy set by HOOK_MANAGER for the current task.
    // When present, overrides the static PolicyProperties configuration.
    // Set by AbstractWorker.process() after AgentContext is built; cleared in the finally block.
    private static final ThreadLocal<HookPolicy> TASK_POLICY = new ThreadLocal<>();

    /** Sets the task-level HookPolicy. Call after AgentContext is built. */
    public static void setTaskPolicy(HookPolicy policy) { TASK_POLICY.set(policy); }

    /** Clears the task-level HookPolicy. Call in finally to prevent ThreadLocal leaks. */
    public static void clearTaskPolicy() { TASK_POLICY.remove(); }

    public PolicyEnforcingToolCallback(ToolCallback delegate,
                                       PathOwnershipEnforcer enforcer,
                                       ToolAuditLogger auditLogger,
                                       String workerType,
                                       String workerProfile) {
        this.delegate = delegate;
        this.enforcer = enforcer;
        this.auditLogger = auditLogger;
        this.workerType = workerType;
        this.workerProfile = workerProfile;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return executeWithPolicy(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return executeWithPolicy(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String executeWithPolicy(String toolInput, ToolCallSupplier supplier) {
        String toolName = getToolDefinition().name();
        TOOL_NAMES.get().add(toolName);
        long startMs = System.currentTimeMillis();

        // 0. Task-level tool allowlist check (HookPolicy from HOOK_MANAGER overrides static config)
        HookPolicy taskPolicy = TASK_POLICY.get();
        if (taskPolicy != null && !taskPolicy.allowedTools().isEmpty()
                && !taskPolicy.allowedTools().contains(toolName)) {
            long durationMs = System.currentTimeMillis() - startMs;
            String msg = "Tool '" + toolName + "' is not in the task-level allowlist "
                    + "(allowed: " + taskPolicy.allowedTools() + ")";
            auditLogger.logToolCall(new ToolAuditEvent(
                    toolName, workerType, workerProfile,
                    Outcome.DENIED, durationMs,
                    auditLogger.truncateInput(toolInput), msg));
            return "{\"error\":true,\"message\":\"" + escapeJson(msg) + "\"}";
        }

        // 1. Path ownership check — use task-level policy if present, else static config
        List<String> effectivePaths = (taskPolicy != null && !taskPolicy.ownedPaths().isEmpty())
                ? taskPolicy.ownedPaths()
                : null; // null → checkOwnership falls back to PolicyProperties

        Optional<String> violation = (effectivePaths != null)
                ? enforcer.checkOwnership(toolName, toolInput, effectivePaths)
                : enforcer.checkOwnership(toolName, toolInput);
        if (violation.isPresent()) {
            long durationMs = System.currentTimeMillis() - startMs;
            auditLogger.logToolCall(new ToolAuditEvent(
                    toolName, workerType, workerProfile,
                    Outcome.DENIED, durationMs,
                    auditLogger.truncateInput(toolInput),
                    violation.get()));
            return "{\"error\":true,\"message\":\"" + escapeJson(violation.get()) + "\"}";
        }

        // 1b. Context-aware read check (Read tool, when CONTEXT_MANAGER provided relevant_files)
        Optional<String> readViolation = enforcer.checkReadOwnership(toolName, toolInput, CONTEXT_FILES.get());
        if (readViolation.isPresent()) {
            long durationMs = System.currentTimeMillis() - startMs;
            auditLogger.logToolCall(new ToolAuditEvent(
                    toolName, workerType, workerProfile,
                    Outcome.DENIED, durationMs,
                    auditLogger.truncateInput(toolInput),
                    readViolation.get()));
            return "{\"error\":true,\"message\":\"" + escapeJson(readViolation.get()) + "\"}";
        }

        // 2. Delegate to actual tool
        try {
            String result = supplier.call();
            long durationMs = System.currentTimeMillis() - startMs;
            auditLogger.logToolCall(new ToolAuditEvent(
                    toolName, workerType, workerProfile,
                    Outcome.SUCCESS, durationMs,
                    auditLogger.truncateInput(toolInput),
                    null));
            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            auditLogger.logToolCall(new ToolAuditEvent(
                    toolName, workerType, workerProfile,
                    Outcome.FAILURE, durationMs,
                    auditLogger.truncateInput(toolInput),
                    e.getMessage()));
            throw e;
        }
    }

    /** Escapes double quotes and backslashes for safe JSON embedding. */
    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    private interface ToolCallSupplier {
        String call();
    }
}
