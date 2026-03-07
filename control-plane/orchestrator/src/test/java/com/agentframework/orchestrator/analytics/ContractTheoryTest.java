package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ContractTheory.ContractEvaluation;
import com.agentframework.orchestrator.analytics.ContractTheory.WorkerContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ContractTheory}.
 *
 * <p>Verifies surplus computation, effective budget with bonus/penalty,
 * information rent, participation and IC constraints, contract evaluation,
 * Bayesian adjustment, and optimal contract calibration.</p>
 */
@DisplayName("Contract Theory — SLA worker incentive design")
class ContractTheoryTest {

    private static final WorkerContract STANDARD_CONTRACT = new WorkerContract(
            "BE", "be-java", 1000.0, 0.6, 1.5, 0.5);

    // ── Surplus ──────────────────────────────────────────────────

    @Test
    void computeSurplus_aboveTarget_positive() {
        double surplus = ContractTheory.computeSurplus(0.8, 0.6);
        assertThat(surplus).isCloseTo(0.2, within(1e-9));
    }

    @Test
    void computeSurplus_belowTarget_negative() {
        double surplus = ContractTheory.computeSurplus(0.4, 0.6);
        assertThat(surplus).isCloseTo(-0.2, within(1e-9));
    }

    // ── Effective Budget ─────────────────────────────────────────

    @Test
    void effectiveBudget_aboveTarget_includesBonus() {
        // surplus = +0.2, bonus = 1.5 × 0.2 = 0.3, penalty = 0
        // effective = 1000 + 0.3 - 0 = 1000.3
        double eb = ContractTheory.effectiveBudget(1000.0, 1.5, 0.5, 0.2);
        assertThat(eb).isCloseTo(1000.3, within(1e-9));
    }

    @Test
    void effectiveBudget_belowTarget_includesPenalty() {
        // surplus = -0.2, bonus = 0, penalty = 0.5 × 0.2 = 0.1
        // effective = 1000 + 0 - 0.1 = 999.9
        double eb = ContractTheory.effectiveBudget(1000.0, 1.5, 0.5, -0.2);
        assertThat(eb).isCloseTo(999.9, within(1e-9));
    }

    @Test
    void effectiveBudget_atTarget_equalsBase() {
        // surplus = 0, no bonus, no penalty
        double eb = ContractTheory.effectiveBudget(1000.0, 1.5, 0.5, 0.0);
        assertThat(eb).isCloseTo(1000.0, within(1e-9));
    }

    @Test
    void effectiveBudget_clampedToZero() {
        // Large penalty must not produce negative budget
        // surplus = -5000, penalty = 0.5 × 5000 = 2500 > base 1000
        double eb = ContractTheory.effectiveBudget(1000.0, 1.5, 0.5, -5000.0);
        assertThat(eb).isCloseTo(0.0, within(1e-9));
    }

    // ── Information Rent ─────────────────────────────────────────

    @Test
    void informationRent_highAboveLow_positive() {
        // High-type at 0.9, low-type at 0.3 → different effective budgets
        double ir = ContractTheory.informationRent(0.9, 0.3, STANDARD_CONTRACT);
        // High: surplus = 0.3, eb = 1000 + 1.5×0.3 = 1000.45
        // Low:  surplus = -0.3, eb = 1000 - 0.5×0.3 = 999.85
        // IR = 1000.45 - 999.85 = 0.6
        assertThat(ir).isCloseTo(0.6, within(1e-9));
    }

    // ── Participation Constraint ─────────────────────────────────

    @Test
    void participationSatisfied_sufficientBudget_true() {
        assertThat(ContractTheory.participationSatisfied(1000.0, 500.0)).isTrue();
    }

    @Test
    void participationSatisfied_insufficientBudget_false() {
        assertThat(ContractTheory.participationSatisfied(400.0, 500.0)).isFalse();
    }

    // ── Incentive Compatibility ──────────────────────────────────

    @Test
    void incentiveCompatible_bonusExceedsPenalty_true() {
        // β = 1.5 > γ = 0.5 → IC satisfied
        assertThat(ContractTheory.incentiveCompatible(1.5, 0.5)).isTrue();
    }

    @Test
    void incentiveCompatible_penaltyExceedsBonus_false() {
        // β = 0.3 < γ = 0.5 → IC violated
        assertThat(ContractTheory.incentiveCompatible(0.3, 0.5)).isFalse();
    }

    // ── Evaluate ─────────────────────────────────────────────────

    @Test
    void evaluate_withContract_returnsCorrectEvaluation() {
        ContractEvaluation eval = ContractTheory.evaluate(STANDARD_CONTRACT, 0.8);

        assertThat(eval.profile()).isEqualTo("be-java");
        assertThat(eval.targetMet()).isTrue();
        assertThat(eval.actualQuality()).isCloseTo(0.8, within(1e-9));
        assertThat(eval.qualityTarget()).isCloseTo(0.6, within(1e-9));
        assertThat(eval.surplus()).isCloseTo(0.2, within(1e-9));
        // effective = 1000 + 1.5 × 0.2 = 1000.3
        assertThat(eval.effectiveBudget()).isCloseTo(1000.3, within(1e-9));
        // information rent > 0 (high type vs low type at 50% of target)
        assertThat(eval.informationRent()).isGreaterThan(0.0);
    }

    @Test
    void evaluate_belowTarget_hasPenalty() {
        ContractEvaluation eval = ContractTheory.evaluate(STANDARD_CONTRACT, 0.4);

        assertThat(eval.targetMet()).isFalse();
        assertThat(eval.surplus()).isCloseTo(-0.2, within(1e-9));
        // effective = 1000 - 0.5 × 0.2 = 999.9
        assertThat(eval.effectiveBudget()).isCloseTo(999.9, within(1e-9));
    }

    // ── Adjust Contract ──────────────────────────────────────────

    @Test
    void adjustContract_afterObservations_updatesTarget() {
        // Observations consistently above target → target moves up
        double[] qualities = {0.8, 0.85, 0.9, 0.75, 0.8};
        WorkerContract adjusted = ContractTheory.adjustContract(
                STANDARD_CONTRACT, qualities, 0.1);

        // mean = 0.82, meanSurplus = 0.82 - 0.6 = 0.22
        // newTarget = 0.6 + 0.1 × 0.22 = 0.622
        assertThat(adjusted.qualityTarget()).isCloseTo(0.622, within(1e-9));
        // Other fields unchanged
        assertThat(adjusted.workerType()).isEqualTo("BE");
        assertThat(adjusted.profile()).isEqualTo("be-java");
        assertThat(adjusted.baseTokenBudget()).isCloseTo(1000.0, within(1e-9));
        assertThat(adjusted.bonusMultiplier()).isCloseTo(1.5, within(1e-9));
        assertThat(adjusted.penaltyRate()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void adjustContract_emptyObservations_returnsUnchanged() {
        WorkerContract adjusted = ContractTheory.adjustContract(
                STANDARD_CONTRACT, new double[0], 0.1);

        assertThat(adjusted.qualityTarget()).isCloseTo(
                STANDARD_CONTRACT.qualityTarget(), within(1e-9));
    }

    @Test
    void adjustContract_targetClampedToZeroOne() {
        // Very low quality observations with high learning rate → target tries to go negative
        double[] qualities = {0.0, 0.0, 0.0};
        WorkerContract adjusted = ContractTheory.adjustContract(
                STANDARD_CONTRACT, qualities, 1.0);

        // mean = 0, meanSurplus = 0 - 0.6 = -0.6
        // newTarget = 0.6 + 1.0 × (-0.6) = 0.0
        assertThat(adjusted.qualityTarget()).isGreaterThanOrEqualTo(0.0);
        assertThat(adjusted.qualityTarget()).isLessThanOrEqualTo(1.0);
    }

    // ── Optimal Contract ─────────────────────────────────────────

    @Test
    void optimalContract_fromObservations_calibratesParams() {
        double[] qualities = {0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.5, 0.6, 0.7};
        double[] costs = {1000, 1200, 900, 1100, 1050, 950, 1000, 1150, 1050, 900};

        WorkerContract contract = ContractTheory.optimalContract(
                "BE", "be-java", qualities, costs, 1.5, 0.5);

        // Base budget = mean(costs) = 10300/10 = 1030
        assertThat(contract.baseTokenBudget()).isCloseTo(1030.0, within(1e-9));

        // Quality target = 40th percentile of sorted [0.3,0.4,0.5,0.5,0.6,0.6,0.7,0.7,0.8,0.9]
        // index = (int)(10 × 0.4) = 4 → sorted[4] = 0.6
        assertThat(contract.qualityTarget()).isCloseTo(0.6, within(1e-9));

        // Bonus/penalty from defaults
        assertThat(contract.bonusMultiplier()).isCloseTo(1.5, within(1e-9));
        assertThat(contract.penaltyRate()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void optimalContract_smallSample_usesMedian() {
        // n < 5: uses median (50th percentile) instead of 40th
        double[] qualities = {0.3, 0.7, 0.5};
        double[] costs = {1000, 1200, 900};

        WorkerContract contract = ContractTheory.optimalContract(
                "BE", "be-go", qualities, costs, 1.5, 0.5);

        // sorted = [0.3, 0.5, 0.7], percentile = 0.5
        // index = min((int)(3 × 0.5), 2) = min(1, 2) = 1 → sorted[1] = 0.5
        assertThat(contract.qualityTarget()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void optimalContract_emptyObservations_usesFallbacks() {
        WorkerContract contract = ContractTheory.optimalContract(
                "BE", "be-go", new double[0], new double[0], 1.5, 0.5);

        // Empty qualities → target = 0.5 (fallback)
        assertThat(contract.qualityTarget()).isCloseTo(0.5, within(1e-9));
        // Empty costs → baseBudget = 1.0 (fallback)
        assertThat(contract.baseTokenBudget()).isCloseTo(1.0, within(1e-9));
    }
}
