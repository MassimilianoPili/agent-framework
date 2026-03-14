package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.SliDefinitionService.SliReport;
import com.agentframework.orchestrator.analytics.SliDefinitionService.SliValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks SLI measurements against SLO targets and reports compliance.
 *
 * <p>An SLO (Service Level Objective) is a target value for an SLI. This tracker
 * compares computed SLIs against configured targets and flags violations.
 * SLO compliance is the foundation for error budget calculation.</p>
 *
 * <p>Default targets (configurable via application.yml):
 * <ul>
 *   <li>Availability: 95% (availability ≥ 0.95)</li>
 *   <li>Latency P99: ≤ 300 seconds</li>
 *   <li>Quality: ≥ 50% tasks above quality threshold</li>
 * </ul></p>
 *
 * @see SliDefinitionService
 * @see ErrorBudgetCalculator
 */
@Service
@ConditionalOnProperty(prefix = "observability-sli", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SloTracker {

    private static final Logger log = LoggerFactory.getLogger(SloTracker.class);

    private final SliDefinitionService sliDefinitionService;

    @Value("${observability-sli.slo-targets.availability:0.95}")
    private double availabilityTarget;

    @Value("${observability-sli.slo-targets.latency-p99-seconds:300}")
    private double latencyP99Target;

    @Value("${observability-sli.slo-targets.quality-threshold:0.5}")
    private double qualityTarget;

    public SloTracker(SliDefinitionService sliDefinitionService) {
        this.sliDefinitionService = sliDefinitionService;
    }

    /**
     * Checks SLO compliance for a worker type.
     *
     * @param workerType worker type name (e.g. "BE")
     * @return compliance report with pass/fail for each SLO
     */
    public SloComplianceReport checkCompliance(String workerType) {
        SliReport sliReport = sliDefinitionService.computeSlis(workerType);
        List<SloCheck> checks = new ArrayList<>();

        // Availability SLO
        SliValue availability = sliReport.findByName("availability");
        if (availability != null) {
            boolean met = availability.value() >= availabilityTarget;
            checks.add(new SloCheck("availability", availabilityTarget, availability.value(), met));
        }

        // Latency P99 SLO (lower is better)
        SliValue latencyP99 = sliReport.findByName("latency_p99_seconds");
        if (latencyP99 != null) {
            boolean met = latencyP99.value() <= latencyP99Target;
            checks.add(new SloCheck("latency_p99_seconds", latencyP99Target, latencyP99.value(), met));
        }

        // Quality SLO
        SliValue quality = sliReport.findByName("quality");
        if (quality != null) {
            boolean met = quality.value() >= qualityTarget;
            checks.add(new SloCheck("quality", qualityTarget, quality.value(), met));
        }

        boolean allMet = checks.stream().allMatch(SloCheck::met);

        log.debug("SLO compliance for {}: allMet={}, checks={}", workerType, allMet, checks.size());

        return new SloComplianceReport(workerType, checks, allMet, sliReport.totalItems());
    }

    /**
     * SLO compliance report for a worker type.
     *
     * @param workerType    the analysed worker type
     * @param checks        individual SLO check results
     * @param allMet        true if all SLOs are met
     * @param totalItems    total items used for SLI computation
     */
    public record SloComplianceReport(
            String workerType,
            List<SloCheck> checks,
            boolean allMet,
            int totalItems
    ) {}

    /**
     * Result of checking a single SLO.
     *
     * @param sliName    the SLI being checked
     * @param target     the SLO target value
     * @param actual     the actual SLI value
     * @param met        true if the SLO is satisfied
     */
    public record SloCheck(
            String sliName,
            double target,
            double actual,
            boolean met
    ) {}
}
