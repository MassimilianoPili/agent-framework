package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ErrorBudgetCalculator.AlertSeverity;
import com.agentframework.orchestrator.analytics.ErrorBudgetCalculator.BurnRateAlert;
import com.agentframework.orchestrator.analytics.ErrorBudgetCalculator.ErrorBudgetReport;
import com.agentframework.orchestrator.analytics.SloTracker.SloCheck;
import com.agentframework.orchestrator.analytics.SloTracker.SloComplianceReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ErrorBudgetCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class ErrorBudgetCalculatorTest {

    @Mock private SloTracker sloTracker;

    private ErrorBudgetCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ErrorBudgetCalculator(sloTracker);
        ReflectionTestUtils.setField(calculator, "burnRateWarning", 1.0);
        ReflectionTestUtils.setField(calculator, "burnRateCritical", 2.0);
    }

    @Test
    @DisplayName("all SLOs met — burn rate 0, severity OK")
    void allMet_noBudgetConsumed() {
        when(sloTracker.checkCompliance("BE")).thenReturn(compliance(
                new SloCheck("availability", 0.95, 0.99, true)
        ));

        ErrorBudgetReport report = calculator.computeBudget("BE");

        assertThat(report.budgetExhausted()).isFalse();
        BurnRateAlert alert = report.alerts().get(0);
        assertThat(alert.sliName()).isEqualTo("availability");
        assertThat(alert.budget()).isCloseTo(0.05, within(1e-10)); // 1 - 0.95
        assertThat(alert.consumed()).isEqualTo(0.0); // actual > target, no consumed
        assertThat(alert.burnRate()).isEqualTo(0.0);
        assertThat(alert.severity()).isEqualTo(AlertSeverity.OK);
    }

    @Test
    @DisplayName("availability below target — budget partially consumed")
    void availability_partialConsumption() {
        when(sloTracker.checkCompliance("BE")).thenReturn(compliance(
                new SloCheck("availability", 0.95, 0.93, false)
        ));

        ErrorBudgetReport report = calculator.computeBudget("BE");

        BurnRateAlert alert = report.alerts().get(0);
        assertThat(alert.budget()).isCloseTo(0.05, within(1e-10));
        assertThat(alert.consumed()).isCloseTo(0.02, within(1e-10)); // 0.95 - 0.93
        assertThat(alert.burnRate()).isCloseTo(0.4, within(1e-10)); // 0.02 / 0.05
        assertThat(alert.severity()).isEqualTo(AlertSeverity.OK); // < 1.0
    }

    @Test
    @DisplayName("burn rate >= warning threshold triggers WARNING")
    void burnRate_warning() {
        // Availability target 0.95, actual 0.89 → consumed ≈ 0.06, budget ≈ 0.05, rate ≈ 1.2
        // Uses values well above threshold to avoid IEEE 754 boundary issues
        when(sloTracker.checkCompliance("BE")).thenReturn(compliance(
                new SloCheck("availability", 0.95, 0.89, false)
        ));

        ErrorBudgetReport report = calculator.computeBudget("BE");

        BurnRateAlert alert = report.alerts().get(0);
        assertThat(alert.burnRate()).as("burnRate").isGreaterThanOrEqualTo(1.0);
        assertThat(alert.burnRate()).as("burnRate < critical").isLessThan(2.0);
        assertThat(alert.severity()).isEqualTo(AlertSeverity.WARNING);
    }

    @Test
    @DisplayName("burn rate >= critical threshold triggers CRITICAL")
    void burnRate_critical() {
        // Availability target 0.95, actual 0.83 → consumed ≈ 0.12, budget ≈ 0.05, rate ≈ 2.4
        // Uses values well above threshold to avoid IEEE 754 boundary issues
        when(sloTracker.checkCompliance("BE")).thenReturn(compliance(
                new SloCheck("availability", 0.95, 0.83, false)
        ));

        ErrorBudgetReport report = calculator.computeBudget("BE");

        BurnRateAlert alert = report.alerts().get(0);
        assertThat(alert.burnRate()).as("burnRate").isGreaterThanOrEqualTo(2.0);
        assertThat(alert.severity()).isEqualTo(AlertSeverity.CRITICAL);
    }

    @Test
    @DisplayName("budget exhausted when remaining reaches zero")
    void budgetExhausted() {
        // Availability target 0.95, actual 0.85 → consumed = 0.10 > budget 0.05
        when(sloTracker.checkCompliance("BE")).thenReturn(compliance(
                new SloCheck("availability", 0.95, 0.85, false)
        ));

        ErrorBudgetReport report = calculator.computeBudget("BE");

        assertThat(report.budgetExhausted()).isTrue();
        assertThat(report.alerts().get(0).remaining()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("latency SLO uses actual latency as consumed")
    void latency_budgetComputation() {
        when(sloTracker.checkCompliance("BE")).thenReturn(compliance(
                new SloCheck("latency_p99_seconds", 300.0, 150.0, true)
        ));

        ErrorBudgetReport report = calculator.computeBudget("BE");

        BurnRateAlert alert = report.alerts().get(0);
        assertThat(alert.budget()).isEqualTo(300.0);     // target = max allowed
        assertThat(alert.consumed()).isEqualTo(150.0);    // actual latency
        assertThat(alert.burnRate()).isCloseTo(0.5, within(1e-10)); // 150/300
        assertThat(alert.severity()).isEqualTo(AlertSeverity.OK);
    }

    @Test
    @DisplayName("multiple SLOs checked in single report")
    void multipleSlos() {
        when(sloTracker.checkCompliance("BE")).thenReturn(compliance(
                new SloCheck("availability", 0.95, 0.99, true),
                new SloCheck("quality", 0.50, 0.80, true),
                new SloCheck("latency_p99_seconds", 300.0, 100.0, true)
        ));

        ErrorBudgetReport report = calculator.computeBudget("BE");

        assertThat(report.alerts()).hasSize(3);
        assertThat(report.budgetExhausted()).isFalse();
        assertThat(report.alerts()).allMatch(a -> a.severity() == AlertSeverity.OK);
    }

    private SloComplianceReport compliance(SloCheck... checks) {
        boolean allMet = List.of(checks).stream().allMatch(SloCheck::met);
        return new SloComplianceReport("BE", List.of(checks), allMet, 100);
    }
}
