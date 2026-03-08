package com.agentframework.worker.policy;

import com.agentframework.common.policy.ApprovalMode;
import com.agentframework.common.policy.HookPolicy;
import com.agentframework.common.policy.RiskLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PolicyEnforcingToolCallback}.
 *
 * <p>Verifies that the three enforcement layers (task-policy allowlist,
 * path ownership, context-aware read check) work correctly and that
 * the audit logger is always called regardless of outcome.</p>
 *
 * <p>ThreadLocal state is cleaned up in {@code @AfterEach} to prevent
 * test pollution across test methods.</p>
 */
@ExtendWith(MockitoExtension.class)
class PolicyEnforcingToolCallbackTest {

    @Mock private ToolCallback delegate;
    @Mock private PathOwnershipEnforcer enforcer;
    @Mock private ToolAuditLogger auditLogger;
    @Mock private ToolDefinition toolDefinition;

    private PolicyEnforcingToolCallback callback;

    @BeforeEach
    void setUp() {
        when(delegate.getToolDefinition()).thenReturn(toolDefinition);
        when(toolDefinition.name()).thenReturn("fs_write");

        callback = new PolicyEnforcingToolCallback(
                delegate, enforcer, auditLogger, "BE_JAVA", "be-java");
    }

    @AfterEach
    void clearThreadLocals() {
        // Prevent test pollution: ThreadLocals must be cleaned between tests.
        PolicyEnforcingToolCallback.clearTaskPolicy();
        PolicyEnforcingToolCallback.clearContextFiles();
        PolicyEnforcingToolCallback.clearDynamicOwnsPaths();
        PolicyEnforcingToolCallback.resetToolNames();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Creates a minimal HookPolicy that allows only the specified tools. */
    private static HookPolicy policyAllowing(String... toolNames) {
        return new HookPolicy(
                List.of(toolNames),   // allowedTools
                List.of(),            // ownedPaths
                List.of(),            // allowedMcpServers
                false,                // auditEnabled
                null,                 // maxTokenBudget
                List.of(),            // allowedNetworkHosts
                ApprovalMode.NONE,    // requiredHumanApproval
                0,                    // approvalTimeoutMinutes
                RiskLevel.LOW,        // riskLevel
                null,                 // estimatedTokens
                false                 // shouldSnapshot
        );
    }

    // ── Task-level allowlist enforcement ────────────────────────────────────────

    @Test
    @DisplayName("call() is DENIED when tool not in task-level allowlist")
    void call_denied_whenToolNotInTaskAllowlist() {
        // policy allows only fs_read — fs_write (our tool) should be denied
        PolicyEnforcingToolCallback.setTaskPolicy(policyAllowing("fs_read"));

        String result = callback.call("{\"filePath\":\"src/Main.java\",\"content\":\"hello\"}");

        assertThat(result).contains("error").contains("not in the task-level allowlist");
        verify(delegate, never()).call(anyString());
        verify(auditLogger).logToolCall(argThat(e ->
                e.outcome() == ToolAuditLogger.Outcome.DENIED));
    }

    @Test
    @DisplayName("call() is ALLOWED when tool is in task-level allowlist")
    void call_allowed_whenToolInTaskAllowlist() {
        // policy allows fs_write
        PolicyEnforcingToolCallback.setTaskPolicy(policyAllowing("fs_write"));

        // No path ownership violation
        when(enforcer.checkOwnership(anyString(), anyString())).thenReturn(Optional.empty());
        // Note: checkReadOwnership is called on the mock but returns Optional.empty() by default
        // (Mockito smart-null for Optional). No explicit stub needed for write tools.
        when(delegate.call(anyString())).thenReturn("{\"success\":true}");

        String result = callback.call("{\"filePath\":\"/backend/Foo.java\",\"content\":\"hello\"}");

        assertThat(result).contains("success");
        verify(delegate).call(anyString());
    }

    // ── Path ownership enforcement ───────────────────────────────────────────────

    @Test
    @DisplayName("call() is DENIED when path ownership is violated (no task policy)")
    void call_denied_whenPathOwnershipViolated() {
        // No task policy — static enforcement applies via checkOwnership(String, String)
        when(enforcer.checkOwnership(anyString(), anyString()))
                .thenReturn(Optional.of("Path /frontend/ is not owned by be-java"));

        String result = callback.call("{\"filePath\":\"/frontend/App.tsx\"}");

        assertThat(result).contains("error").contains("/frontend/");
        verify(delegate, never()).call(anyString());
        verify(auditLogger).logToolCall(argThat(e ->
                e.outcome() == ToolAuditLogger.Outcome.DENIED));
    }

    @Test
    @DisplayName("call() delegates to wrapped tool when no policy violations")
    void call_delegatesWhenNoPolicyViolations() {
        when(enforcer.checkOwnership(anyString(), anyString())).thenReturn(Optional.empty());
        when(enforcer.checkReadOwnership(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(delegate.call(anyString())).thenReturn("{\"written\":true}");

        String result = callback.call("{\"filePath\":\"/backend/Service.java\",\"content\":\"x\"}");

        assertThat(result).contains("written");
        verify(delegate).call(anyString());
        verify(auditLogger).logToolCall(argThat(e ->
                e.outcome() == ToolAuditLogger.Outcome.SUCCESS));
    }

    // ── Tool name tracking ───────────────────────────────────────────────────────

    @Test
    @DisplayName("call() accumulates tool name in TOOL_NAMES ThreadLocal")
    void call_accumulatesToolName() {
        when(enforcer.checkOwnership(anyString(), anyString())).thenReturn(Optional.empty());
        when(enforcer.checkReadOwnership(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(delegate.call(anyString())).thenReturn("{}");

        PolicyEnforcingToolCallback.resetToolNames();
        callback.call("{}");

        List<String> names = PolicyEnforcingToolCallback.drainToolNames();
        assertThat(names).containsExactly("fs_write");
    }

    @Test
    @DisplayName("drainToolNames() clears the list after returning it")
    void drainToolNames_clearsAfterReturn() {
        when(enforcer.checkOwnership(anyString(), anyString())).thenReturn(Optional.empty());
        when(enforcer.checkReadOwnership(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(delegate.call(anyString())).thenReturn("{}");

        PolicyEnforcingToolCallback.resetToolNames();
        callback.call("{}");
        PolicyEnforcingToolCallback.drainToolNames(); // first drain
        List<String> second = PolicyEnforcingToolCallback.drainToolNames(); // should be empty

        assertThat(second).isEmpty();
    }

    // ── Audit on exception ───────────────────────────────────────────────────────

    @Test
    @DisplayName("call() logs FAILURE outcome when delegate throws")
    void call_logsFailureWhenDelegateThrows() {
        when(enforcer.checkOwnership(anyString(), anyString())).thenReturn(Optional.empty());
        when(enforcer.checkReadOwnership(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(delegate.call(anyString())).thenThrow(new RuntimeException("disk full"));

        try {
            callback.call("{\"filePath\":\"/backend/X.java\"}");
        } catch (RuntimeException ignored) { }

        verify(auditLogger).logToolCall(argThat(e ->
                e.outcome() == ToolAuditLogger.Outcome.FAILURE));
    }
}
