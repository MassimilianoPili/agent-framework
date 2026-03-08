package com.agentframework.worker.policy;

import com.agentframework.common.policy.HookPolicy;
import com.agentframework.worker.dto.FileModificationEvent;
import com.agentframework.worker.event.WorkerEventPublisher;
import com.agentframework.worker.policy.ToolAuditLogger.Outcome;
import com.agentframework.worker.policy.ToolAuditLogger.ToolAuditEvent;
import com.agentframework.worker.util.HashUtil;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;

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

    // Holds dynamic ownsPaths resolved from Plan.projectPath + profile's relative paths.
    // Merged with the static/task-policy ownsPaths for the current task.
    private static final ThreadLocal<List<String>> DYNAMIC_OWNS_PATHS = new ThreadLocal<>();

    /** Sets dynamic ownsPaths (from AgentTask.dynamicOwnsPaths). */
    public static void setDynamicOwnsPaths(List<String> paths) { DYNAMIC_OWNS_PATHS.set(paths); }

    /** Clears dynamic ownsPaths. Call in finally to prevent ThreadLocal leaks. */
    public static void clearDynamicOwnsPaths() { DYNAMIC_OWNS_PATHS.remove(); }

    // G3: Accumulates file modification events for the current task (per-thread).
    private static final ThreadLocal<List<FileModificationEvent>> FILE_MODS = ThreadLocal.withInitial(ArrayList::new);

    /** Resets the file-mod accumulator. Call before execute(). */
    public static void resetFileMods() { FILE_MODS.get().clear(); }

    /** Drains and returns file modifications accumulated since the last reset. Clears the list. */
    public static List<FileModificationEvent> drainFileMods() {
        List<FileModificationEvent> mods = new ArrayList<>(FILE_MODS.get());
        FILE_MODS.get().clear();
        return mods;
    }

    /** Clears file mods. Call in finally to prevent ThreadLocal leaks. */
    public static void clearFileMods() { FILE_MODS.remove(); }

    // G6: ThreadLocals for plan/task context — set by AbstractWorker.process()
    private static final ThreadLocal<UUID> CURRENT_PLAN_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TASK_KEY = new ThreadLocal<>();
    private static final ThreadLocal<WorkerEventPublisher> EVENT_PUBLISHER = new ThreadLocal<>();

    public static void setCurrentContext(UUID planId, String taskKey) {
        CURRENT_PLAN_ID.set(planId);
        CURRENT_TASK_KEY.set(taskKey);
    }
    public static void clearCurrentContext() {
        CURRENT_PLAN_ID.remove();
        CURRENT_TASK_KEY.remove();
    }
    public static UUID getCurrentPlanId() { return CURRENT_PLAN_ID.get(); }
    public static String getCurrentTaskKey() { return CURRENT_TASK_KEY.get(); }
    public static void setEventPublisher(WorkerEventPublisher publisher) { EVENT_PUBLISHER.set(publisher); }
    public static void clearEventPublisher() { EVENT_PUBLISHER.remove(); }

    // Write-type tool names that create or modify files
    private static final List<String> WRITE_TOOLS = List.of("fs_write");
    // Delete-type tool names
    private static final List<String> DELETE_TOOLS = List.of("fs_delete");

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
        // Cancellation check: in in-process mode, Future.cancel(true) sets the interrupt flag.
        // We check it BEFORE starting each tool call so a cancelled task stops promptly
        // rather than completing the current tool call and starting a new one.
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Task cancelled — refusing to start tool call");
        }

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

        // 1. Path ownership check — use task-level policy if present, else static config.
        //    Dynamic ownsPaths (from Plan.projectPath) are merged with effective paths.
        List<String> effectivePaths = (taskPolicy != null && !taskPolicy.ownedPaths().isEmpty())
                ? taskPolicy.ownedPaths()
                : null; // null → checkOwnership falls back to PolicyProperties

        // Merge dynamic ownsPaths from Plan.projectPath (if present)
        List<String> dynamicPaths = DYNAMIC_OWNS_PATHS.get();
        if (dynamicPaths != null && !dynamicPaths.isEmpty()) {
            if (effectivePaths != null) {
                List<String> merged = new ArrayList<>(effectivePaths);
                merged.addAll(dynamicPaths);
                effectivePaths = merged;
            } else {
                effectivePaths = dynamicPaths;
            }
        }

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

        // G6: Emit TOOL_CALL_START event
        emitToolCallStart(toolName, toolInput);

        // 2. Delegate to actual tool
        try {
            String result = supplier.call();
            long durationMs = System.currentTimeMillis() - startMs;
            auditLogger.logToolCall(new ToolAuditEvent(
                    toolName, workerType, workerProfile,
                    Outcome.SUCCESS, durationMs,
                    auditLogger.truncateInput(toolInput),
                    null));

            // G3: Capture file modification events for write/delete tools
            captureFileMod(toolName, toolInput);

            // G6: Emit TOOL_CALL_END event (success)
            emitToolCallEnd(toolName, true, durationMs);

            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            // G6: Emit TOOL_CALL_END event (failure)
            emitToolCallEnd(toolName, false, durationMs);

            auditLogger.logToolCall(new ToolAuditEvent(
                    toolName, workerType, workerProfile,
                    Outcome.FAILURE, durationMs,
                    auditLogger.truncateInput(toolInput),
                    e.getMessage()));
            throw e;
        }
    }

    /** G6: Emits TOOL_CALL_START event if publisher is available. */
    private static void emitToolCallStart(String toolName, String toolInput) {
        WorkerEventPublisher pub = EVENT_PUBLISHER.get();
        if (pub != null) {
            try {
                pub.publishToolCallStart(CURRENT_PLAN_ID.get(), CURRENT_TASK_KEY.get(),
                    toolName, toolInput != null ? toolInput.substring(0, Math.min(toolInput.length(), 200)) : "");
            } catch (Exception ignored) { /* never block tool execution */ }
        }
    }

    /** G6: Emits TOOL_CALL_END event if publisher is available. */
    private static void emitToolCallEnd(String toolName, boolean success, long durationMs) {
        WorkerEventPublisher pub = EVENT_PUBLISHER.get();
        if (pub != null) {
            try {
                pub.publishToolCallEnd(CURRENT_PLAN_ID.get(), CURRENT_TASK_KEY.get(),
                    toolName, success, durationMs);
            } catch (Exception ignored) { /* never block tool execution */ }
        }
    }

    /**
     * G3: Captures a file modification event if the tool is a write or delete tool.
     * Parses file_path and content from the JSON toolInput using simple string extraction
     * (avoids Jackson dependency — toolInput is a flat JSON object from MCP).
     */
    private static void captureFileMod(String toolName, String toolInput) {
        try {
            if (WRITE_TOOLS.contains(toolName)) {
                String filePath = extractJsonField(toolInput, "file_path");
                if (filePath == null) filePath = extractJsonField(toolInput, "path");
                if (filePath == null) return;

                String content = extractJsonField(toolInput, "content");
                String hashAfter = (content != null) ? HashUtil.sha256(content) : null;

                FILE_MODS.get().add(new FileModificationEvent(
                    filePath, "MODIFIED", null, hashAfter, null));
            } else if (DELETE_TOOLS.contains(toolName)) {
                String filePath = extractJsonField(toolInput, "file_path");
                if (filePath == null) filePath = extractJsonField(toolInput, "path");
                if (filePath == null) return;

                FILE_MODS.get().add(new FileModificationEvent(
                    filePath, "DELETED", null, null, null));
            }
        } catch (Exception e) {
            // Never fail the tool call because of tracking — silently skip
        }
    }

    /**
     * Extracts a string value from a flat JSON object by field name.
     * Simple regex-free extraction for performance: finds "field":"value".
     */
    private static String extractJsonField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            char c = json.charAt(quoteEnd);
            if (c == '\\') { quoteEnd += 2; continue; }
            if (c == '"') break;
            quoteEnd++;
        }
        return (quoteEnd < json.length()) ? json.substring(quoteStart + 1, quoteEnd) : null;
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
