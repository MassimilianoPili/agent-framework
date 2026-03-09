package com.agentframework.orchestrator.budget;

import com.agentframework.common.policy.HookPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PidBudgetController} — PID-based adaptive token budget (#37).
 *
 * <p>Tests cover warmup passthrough, PID adjustment mechanics, output clamping,
 * anti-windup, adaptive setpoint (EMA), plan eviction, and convergence behavior.</p>
 */
class PidBudgetControllerTest {

    private PidBudgetController controller;
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final String WORKER_TYPE = "BE";

    @BeforeEach
    void setUp() {
        PidBudgetProperties props = new PidBudgetProperties(
                true,       // enabled
                0.5,        // kp
                0.1,        // ki
                0.2,        // kd
                5000,       // minBudget
                500000,     // maxBudget
                100000.0,   // integralLimit
                3            // warmupSamples
        );
        controller = new PidBudgetController(props);
    }

    @Test
    void adjustPolicy_nullPolicy_returnsNull() {
        assertThat(controller.adjustPolicy(PLAN_ID, WORKER_TYPE, null)).isNull();
    }

    @Test
    void adjustPolicy_belowWarmup_returnsUnchanged() {
        HookPolicy original = policy(50000, 40000);

        // Only 2 samples — below warmup threshold of 3
        controller.update(PLAN_ID, WORKER_TYPE, 40000, 45000);
        controller.update(PLAN_ID, WORKER_TYPE, 40000, 42000);

        HookPolicy result = controller.adjustPolicy(PLAN_ID, WORKER_TYPE, original);
        assertThat(result).isSameAs(original);
    }

    @Test
    void adjustPolicy_afterWarmup_adjustsBudget() {
        HookPolicy original = policy(50000, 40000);

        // 3 samples with overuse (actual > estimated) → budget should increase
        controller.update(PLAN_ID, WORKER_TYPE, 40000, 50000);
        controller.update(PLAN_ID, WORKER_TYPE, 40000, 48000);
        controller.update(PLAN_ID, WORKER_TYPE, 40000, 52000);

        HookPolicy result = controller.adjustPolicy(PLAN_ID, WORKER_TYPE, original);
        assertThat(result.maxTokenBudget()).isGreaterThan(50000);
        // estimatedTokens should remain unchanged
        assertThat(result.estimatedTokens()).isEqualTo(40000);
    }

    @Test
    void adjustPolicy_clampsToMinMax() {
        // Extreme overuse to push budget above maxBudget
        for (int i = 0; i < 10; i++) {
            controller.update(PLAN_ID, WORKER_TYPE, 10000, 400000);
        }
        HookPolicy result = controller.adjustPolicy(PLAN_ID, WORKER_TYPE, policy(50000, 10000));
        assertThat(result.maxTokenBudget()).isLessThanOrEqualTo(500000);

        // Extreme underuse to push budget below minBudget
        UUID plan2 = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            controller.update(plan2, WORKER_TYPE, 100000, 100);
        }
        HookPolicy result2 = controller.adjustPolicy(plan2, WORKER_TYPE, policy(50000, 100000));
        assertThat(result2.maxTokenBudget()).isGreaterThanOrEqualTo(5000);
    }

    @Test
    void update_accumulatesError() {
        controller.update(PLAN_ID, WORKER_TYPE, 10000, 15000); // error = +5000
        controller.update(PLAN_ID, WORKER_TYPE, 10000, 12000); // error = +2000

        PidBudgetController.PidState state = controller.getState(PLAN_ID, WORKER_TYPE);
        assertThat(state.sampleCount).isEqualTo(2);
        assertThat(state.previousError).isEqualTo(2000.0);
        // integral = 5000 + 2000 = 7000
        assertThat(state.integral).isEqualTo(7000.0);
    }

    @Test
    void update_antiWindup_clampsIntegral() {
        // Push integral beyond integralLimit (100000)
        for (int i = 0; i < 50; i++) {
            controller.update(PLAN_ID, WORKER_TYPE, 1000, 10000); // error = +9000 each
        }

        PidBudgetController.PidState state = controller.getState(PLAN_ID, WORKER_TYPE);
        assertThat(state.integral).isLessThanOrEqualTo(100000.0);
        assertThat(state.integral).isGreaterThanOrEqualTo(-100000.0);
    }

    @Test
    void update_adaptiveSetpoint_usesEmaWhenNoEstimate() {
        // No estimatedTokens — setpoint should be EMA of actual tokens
        controller.update(PLAN_ID, WORKER_TYPE, null, 10000); // EMA = 10000
        controller.update(PLAN_ID, WORKER_TYPE, null, 20000); // EMA = 0.3*20000 + 0.7*10000 = 13000

        PidBudgetController.PidState state = controller.getState(PLAN_ID, WORKER_TYPE);
        assertThat(state.runningAvgTokens).isEqualTo(13000.0);
        // error on 2nd update: 20000 - 13000 = 7000
        assertThat(state.previousError).isEqualTo(7000.0);
    }

    @Test
    void evictPlan_removesAllEntries() {
        controller.update(PLAN_ID, "BE", 10000, 12000);
        controller.update(PLAN_ID, "FE", 8000, 9000);
        assertThat(controller.getState(PLAN_ID, "BE")).isNotNull();
        assertThat(controller.getState(PLAN_ID, "FE")).isNotNull();

        controller.evictPlan(PLAN_ID);

        assertThat(controller.getState(PLAN_ID, "BE")).isNull();
        assertThat(controller.getState(PLAN_ID, "FE")).isNull();
    }

    @Test
    void convergence_overuse_increasesBudget() {
        HookPolicy original = policy(30000, 20000);

        // Consistent overuse: actual > estimated
        for (int i = 0; i < 5; i++) {
            controller.update(PLAN_ID, WORKER_TYPE, 20000, 35000);
        }

        HookPolicy result = controller.adjustPolicy(PLAN_ID, WORKER_TYPE, original);
        assertThat(result.maxTokenBudget()).isGreaterThan(30000);
    }

    @Test
    void convergence_underuse_decreasesBudget() {
        HookPolicy original = policy(50000, 40000);

        // Consistent underuse: actual < estimated
        for (int i = 0; i < 5; i++) {
            controller.update(PLAN_ID, WORKER_TYPE, 40000, 15000);
        }

        HookPolicy result = controller.adjustPolicy(PLAN_ID, WORKER_TYPE, original);
        assertThat(result.maxTokenBudget()).isLessThan(50000);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static HookPolicy policy(int maxTokenBudget, int estimatedTokens) {
        return new HookPolicy(
                List.of(), List.of(), List.of(), false,
                maxTokenBudget,
                List.of(), null, 0,
                null, estimatedTokens, false
        );
    }
}
