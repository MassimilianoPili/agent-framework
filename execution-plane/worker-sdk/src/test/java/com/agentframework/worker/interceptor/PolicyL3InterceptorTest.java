package com.agentframework.worker.interceptor;

import com.agentframework.common.policy.ApprovalMode;
import com.agentframework.common.policy.HookPolicy;
import com.agentframework.common.policy.RiskLevel;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.dto.AgentTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PolicyL3Interceptor} — post-execution enforcement (#10).
 */
class PolicyL3InterceptorTest {

    private PolicyL3Interceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new PolicyL3Interceptor();
        // Clean up any leftover ThreadLocal state
        PolicyL3Interceptor.drainTokenUsage();
    }

    private AgentContext contextWithPolicy(HookPolicy policy) {
        return new AgentContext(
                UUID.randomUUID(), UUID.randomUUID(), "TEST-001",
                "Test Task", "description", "spec", "system prompt",
                Map.of(), null, List.of(), policy, null, null);
    }

    private AgentContext contextWithoutPolicy() {
        return new AgentContext(
                UUID.randomUUID(), UUID.randomUUID(), "TEST-001",
                "Test Task", "description", "spec", "system prompt",
                Map.of(), null, List.of(), null, null, null);
    }

    private AgentTask dummyTask() {
        return new AgentTask(
                UUID.randomUUID(), UUID.randomUUID(), "TEST-001",
                "Test", "desc", "BE", "be-java",
                null, null, 1, null, null, null,
                null, null, null, null, null, null, null);
    }

    private HookPolicy policyWithBudget(int budget) {
        return new HookPolicy(
                List.of("fs_read"), List.of(), List.of(), true,
                budget, List.of(), ApprovalMode.NONE, 0, RiskLevel.LOW, null, false);
    }

    private HookPolicy policyAuditEnabled() {
        return new HookPolicy(
                List.of("fs_read"), List.of(), List.of(), true,
                null, List.of(), ApprovalMode.NONE, 0, RiskLevel.LOW, null, false);
    }

    // ── No policy → pass through ─────────────────────────────────────────────

    @Test
    @DisplayName("no policy → result passes through unchanged")
    void afterExecute_noPolicy_passThrough() {
        String result = "{\"output\":\"hello\"}";
        String returned = interceptor.afterExecute(contextWithoutPolicy(), result, dummyTask());
        assertThat(returned).isEqualTo(result);
    }

    // ── Empty result → logged but returned ───────────────────────────────────

    @Test
    @DisplayName("empty result with policy → returned unchanged (fail-open)")
    void afterExecute_emptyResult_failOpen() {
        String returned = interceptor.afterExecute(
                contextWithPolicy(policyAuditEnabled()), "", dummyTask());
        assertThat(returned).isEmpty();
    }

    @Test
    @DisplayName("null result with policy → returned unchanged (fail-open)")
    void afterExecute_nullResult_failOpen() {
        String returned = interceptor.afterExecute(
                contextWithPolicy(policyAuditEnabled()), null, dummyTask());
        assertThat(returned).isNull();
    }

    // ── Token budget ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("token usage within budget → result passes through")
    void afterExecute_withinBudget_passThrough() {
        PolicyL3Interceptor.recordTokenUsage(500);
        String result = "{\"output\":\"ok\"}";
        String returned = interceptor.afterExecute(
                contextWithPolicy(policyWithBudget(1000)), result, dummyTask());
        assertThat(returned).isEqualTo(result);
    }

    @Test
    @DisplayName("token usage exceeds budget → result still passes (fail-open)")
    void afterExecute_exceedsBudget_failOpen() {
        PolicyL3Interceptor.recordTokenUsage(2000);
        String result = "{\"output\":\"expensive\"}";
        String returned = interceptor.afterExecute(
                contextWithPolicy(policyWithBudget(1000)), result, dummyTask());
        // Fail-open: result is returned despite budget violation
        assertThat(returned).isEqualTo(result);
    }

    @Test
    @DisplayName("no token recording + budget → no error")
    void afterExecute_noTokensRecorded_noBudgetCheck() {
        String result = "{\"output\":\"ok\"}";
        String returned = interceptor.afterExecute(
                contextWithPolicy(policyWithBudget(1000)), result, dummyTask());
        assertThat(returned).isEqualTo(result);
    }

    // ── Token accumulation ───────────────────────────────────────────────────

    @Test
    @DisplayName("multiple recordTokenUsage calls accumulate")
    void recordTokenUsage_accumulates() {
        PolicyL3Interceptor.recordTokenUsage(300);
        PolicyL3Interceptor.recordTokenUsage(400);
        Integer total = PolicyL3Interceptor.drainTokenUsage();
        assertThat(total).isEqualTo(700);
    }

    @Test
    @DisplayName("drainTokenUsage clears ThreadLocal")
    void drainTokenUsage_clears() {
        PolicyL3Interceptor.recordTokenUsage(100);
        PolicyL3Interceptor.drainTokenUsage();
        assertThat(PolicyL3Interceptor.drainTokenUsage()).isNull();
    }

    // ── Audit logging ────────────────────────────────────────────────────────

    @Test
    @DisplayName("audit enabled → result passes through (log verification omitted, behavior test)")
    void afterExecute_auditEnabled_passThrough() {
        String result = "{\"output\":\"audited\"}";
        String returned = interceptor.afterExecute(
                contextWithPolicy(policyAuditEnabled()), result, dummyTask());
        assertThat(returned).isEqualTo(result);
    }

    // ── Ordering ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("order is LOWEST_PRECEDENCE - 10 (before schema validation)")
    void getOrder_beforeSchemaValidation() {
        assertThat(interceptor.getOrder())
                .isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 10);
    }
}
