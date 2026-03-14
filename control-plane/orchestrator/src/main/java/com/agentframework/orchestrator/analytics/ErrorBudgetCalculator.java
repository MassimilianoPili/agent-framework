package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.SloTracker.SloCheck;
import com.agentframework.orchestrator.analytics.SloTracker.SloComplianceReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes error budgets and burn rate alerts for worker types.
 *
 * <p>The error budget is the allowed margin of failure: {@code budget = 1 − SLO target}.
 * For example, with a 95% availability SLO, the error budget is 5% — meaning up to 5%
 * of tasks can fail before violating the SLO.</p>
 *
 * <p>The <b>burn rate</b> measures how fast the error budget is being consumed:
 * <pre>
 *   burn_rate = actual_error_rate / allowed_error_rate
 * </pre>
 * A burn rate of 1.0 means the budget is being consumed at the expected pace.
 * A burn rate &gt; 1.0 means faster consumption — the SLO will be violated before the
 * window ends. A burn rate of 2.0 means the budget will be exhausted in half the window.</p>
 *
 * @see SloTracker
 * @see <a href="https://sre.google/workbook/alerting-on-slos/">
 *     Google SRE Workbook — Alerting on SLOs</a>
 */
@Service
@ConditionalOnProperty(prefix = "observability-sli", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ErrorBudgetCalculator {

    private static final Logger log = LoggerFactory.getLogger(ErrorBudgetCalculator.class);

    /** Tolerance for floating-point threshold comparisons (IEEE 754 rounding). */
    private static final double EPSILON = 1e-9;

    private final SloTracker sloTracker;

    @Value("${observability-sli.burn-rate-threshold-warning:1.0}")
    private double burnRateWarning;

    @Value("${observability-sli.burn-rate-threshold-critical:2.0}")
    private double burnRateCritical;

    public ErrorBudgetCalculator(SloTracker sloTracker) {
        this.sloTracker = sloTracker;
    }

    /**
     * Computes error budget and burn rate alerts for a worker type.
     *
     * @param workerType worker type name (e.g. "BE")
     * @return error budget report with remaining budget and alerts
     */
    public ErrorBudgetReport computeBudget(String workerType) {
        SloComplianceReport compliance = sloTracker.checkCompliance(workerType);
        List<BurnRateAlert> alerts = new ArrayList<>();

        for (SloCheck check : compliance.checks()) {
            double budget = computeErrorBudget(check);
            double consumed = computeConsumed(check);
            double remaining = Math.max(0.0, budget - consumed);
            double burnRate = budget > 0 ? consumed / budget : 0.0;

            AlertSeverity severity = AlertSeverity.OK;
            if (burnRate >= burnRateCritical - EPSILON) {
                severity = AlertSeverity.CRITICAL;
            } else if (burnRate >= burnRateWarning - EPSILON) {
                severity = AlertSeverity.WARNING;
            }

            alerts.add(new BurnRateAlert(check.sliName(), budget, consumed, remaining, burnRate, severity));

            if (severity != AlertSeverity.OK) {
                log.warn("Error budget alert for {}/{}: burnRate={} ({}), remaining={}",
                         workerType, check.sliName(), burnRate, severity, remaining);
            }
        }

        boolean budgetExhausted = alerts.stream()
                .anyMatch(a -> a.remaining() <= 0);

        return new ErrorBudgetReport(workerType, alerts, budgetExhausted, compliance.totalItems());
    }

    /**
     * Computes the total error budget for an SLO check.
     * For "higher is better" SLIs (availability, quality): budget = 1 − target.
     * For "lower is better" SLIs (latency): budget = target (max allowed value).
     */
    private double computeErrorBudget(SloCheck check) {
        return switch (check.sliName()) {
            case "latency_p99_seconds" -> check.target(); // max allowed seconds
            default -> 1.0 - check.target(); // e.g., 1 - 0.95 = 0.05
        };
    }

    /**
     * Computes how much of the error budget has been consumed.
     * For "higher is better" SLIs: consumed = target − actual (if actual < target).
     * For "lower is better" SLIs: consumed = actual (the latency itself).
     */
    private double computeConsumed(SloCheck check) {
        return switch (check.sliName()) {
            case "latency_p99_seconds" -> check.actual(); // actual latency consumed
            default -> Math.max(0.0, check.target() - check.actual()); // gap below target
        };
    }

    /**
     * Error budget report for a worker type.
     *
     * @param workerType       the analysed worker type
     * @param alerts           per-SLO burn rate alerts
     * @param budgetExhausted  true if any SLO's error budget is fully consumed
     * @param totalItems       total items used for SLI computation
     */
    public record ErrorBudgetReport(
            String workerType,
            List<BurnRateAlert> alerts,
            boolean budgetExhausted,
            int totalItems
    ) {}

    /**
     * Burn rate alert for a single SLO.
     *
     * @param sliName    the SLI being tracked
     * @param budget     total error budget (e.g. 0.05 for 95% SLO)
     * @param consumed   how much budget has been consumed
     * @param remaining  remaining budget (budget − consumed, ≥ 0)
     * @param burnRate   consumption rate (consumed / budget)
     * @param severity   alert severity based on burn rate thresholds
     */
    public record BurnRateAlert(
            String sliName,
            double budget,
            double consumed,
            double remaining,
            double burnRate,
            AlertSeverity severity
    ) {}

    /**
     * Alert severity levels based on error budget burn rate.
     */
    public enum AlertSeverity {
        OK,
        WARNING,
        CRITICAL
    }
}
