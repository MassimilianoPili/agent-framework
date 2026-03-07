package com.agentframework.orchestrator.api;

import com.agentframework.orchestrator.analytics.*;
import com.agentframework.orchestrator.analytics.CalibrationAudit.CalibrationReport;
import com.agentframework.orchestrator.analytics.ProspectTheory.ProspectEvaluation;
import com.agentframework.orchestrator.analytics.ShapleyValue.ShapleyReport;
import com.agentframework.orchestrator.analytics.VCGMechanismService.VCGPricingReport;
import com.agentframework.orchestrator.budget.KellyCriterion.KellyRecommendation;
import com.agentframework.orchestrator.budget.KellyCriterionService;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.OptimalStopping.StoppingDecision;
import com.agentframework.orchestrator.orchestration.OptimalStoppingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST API for system-level analytics.
 *
 * <p>Provides population analysis (replicator dynamics), drift detection
 * (Wasserstein distance), prospect theory evaluation, hedge weights,
 * Kelly criterion fractions, optimal stopping thresholds,
 * calibration audit, VCG mechanism pricing, and Shapley value attribution
 * endpoints for the worker profile ecosystem.</p>
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final ReplicatorDynamicsService replicatorDynamicsService;
    private final Optional<WorkerDriftMonitor> driftMonitor;
    private final Optional<ProspectTheoryService> prospectTheoryService;
    private final Optional<HedgeAlgorithmService> hedgeAlgorithmService;
    private final Optional<KellyCriterionService> kellyCriterionService;
    private final Optional<OptimalStoppingService> optimalStoppingService;
    private final Optional<CalibrationAuditService> calibrationAuditService;
    private final Optional<VCGMechanismService> vcgMechanismService;
    private final Optional<ShapleyValueService> shapleyValueService;

    public AnalyticsController(ReplicatorDynamicsService replicatorDynamicsService,
                                Optional<WorkerDriftMonitor> driftMonitor,
                                Optional<ProspectTheoryService> prospectTheoryService,
                                Optional<HedgeAlgorithmService> hedgeAlgorithmService,
                                Optional<KellyCriterionService> kellyCriterionService,
                                Optional<OptimalStoppingService> optimalStoppingService,
                                Optional<CalibrationAuditService> calibrationAuditService,
                                Optional<VCGMechanismService> vcgMechanismService,
                                Optional<ShapleyValueService> shapleyValueService) {
        this.replicatorDynamicsService = replicatorDynamicsService;
        this.driftMonitor = driftMonitor;
        this.prospectTheoryService = prospectTheoryService;
        this.hedgeAlgorithmService = hedgeAlgorithmService;
        this.kellyCriterionService = kellyCriterionService;
        this.optimalStoppingService = optimalStoppingService;
        this.calibrationAuditService = calibrationAuditService;
        this.vcgMechanismService = vcgMechanismService;
        this.shapleyValueService = shapleyValueService;
    }

    /**
     * GET /api/v1/analytics/population
     *
     * <p>Runs replicator dynamics on the current worker profile population and returns
     * the equilibrium distribution, ESS deviation, and rebalance recommendations.</p>
     */
    @GetMapping("/population")
    public ResponseEntity<WorkerPopulationReport> getPopulationReport() {
        return ResponseEntity.ok(replicatorDynamicsService.analyse());
    }

    /**
     * GET /api/v1/analytics/worker-drift
     *
     * <p>Returns the latest drift detection results for all worker profiles.
     * Each result includes the Wasserstein-1 distance between recent and historical
     * reward distributions.</p>
     */
    @GetMapping("/worker-drift")
    public ResponseEntity<List<DriftResult>> getWorkerDrift() {
        if (driftMonitor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(driftMonitor.get().getLatestResults());
    }

    /**
     * GET /api/v1/analytics/prospect-evaluation?workerType=BE&amp;profile=be-java
     *
     * <p>Evaluates a worker profile using Prospect Theory (Kahneman-Tversky).
     * Returns the prospect value, raw expected value, and loss aversion penalty.</p>
     */
    @GetMapping("/prospect-evaluation")
    public ResponseEntity<ProspectEvaluation> getProspectEvaluation(
            @RequestParam String workerType,
            @RequestParam String profile) {
        if (prospectTheoryService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(prospectTheoryService.get().evaluate(workerType, profile));
    }

    /**
     * GET /api/v1/analytics/hedge-weights?workerType=BE
     *
     * <p>Returns the current Hedge algorithm weight distribution over worker profiles
     * for the specified worker type.</p>
     */
    @GetMapping("/hedge-weights")
    public ResponseEntity<Map<String, Double>> getHedgeWeights(
            @RequestParam String workerType) {
        if (hedgeAlgorithmService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(hedgeAlgorithmService.get().getWeights(
                WorkerType.valueOf(workerType)));
    }

    /**
     * GET /api/v1/analytics/kelly-fraction?workerType=BE&amp;profile=be-java
     *
     * <p>Computes the Kelly Criterion optimal budget fraction for a worker profile
     * based on its historical win rate and payoffs.</p>
     */
    @GetMapping("/kelly-fraction")
    public ResponseEntity<KellyRecommendation> getKellyFraction(
            @RequestParam String workerType,
            @RequestParam String profile) {
        if (kellyCriterionService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(kellyCriterionService.get().computeForProfile(workerType, profile));
    }

    /**
     * GET /api/v1/analytics/stopping-threshold?workerType=BE
     *
     * <p>Returns the current optimal stopping threshold for a worker type,
     * computed using the 1/e rule (Secretary Problem).</p>
     */
    @GetMapping("/stopping-threshold")
    public ResponseEntity<StoppingDecision> getStoppingThreshold(
            @RequestParam String workerType,
            @RequestParam(defaultValue = "0.5") double candidateReward) {
        if (optimalStoppingService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(optimalStoppingService.get()
                .evaluateForWorkerType(workerType, candidateReward));
    }

    /**
     * GET /api/v1/analytics/calibration-report
     * GET /api/v1/analytics/calibration-report?workerType=BE
     *
     * <p>Returns the calibration audit report (ECE, Brier Score, Dutch Book vulnerability).
     * When workerType is specified, returns a filtered report. Otherwise returns the
     * latest global cached report.</p>
     */
    @GetMapping("/calibration-report")
    public ResponseEntity<CalibrationReport> getCalibrationReport(
            @RequestParam(required = false) String workerType) {
        if (calibrationAuditService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (workerType != null) {
            CalibrationReport report = calibrationAuditService.get().auditByWorkerType(workerType);
            if (report == null) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(report);
        }
        CalibrationReport report = calibrationAuditService.get().getLatestReport();
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/v1/analytics/vcg-pricing?workerType=BE
     *
     * <p>Computes VCG mechanism pricing for worker profiles of the specified type.
     * Returns the VCG auction result (winner, second-price payment, information rent)
     * along with all profile bids derived from historical performance.</p>
     */
    @GetMapping("/vcg-pricing")
    public ResponseEntity<VCGPricingReport> getVcgPricing(
            @RequestParam String workerType) {
        if (vcgMechanismService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(vcgMechanismService.get().computePricing(workerType));
    }

    /**
     * GET /api/v1/analytics/shapley-attribution?planId=&lt;uuid&gt;
     *
     * <p>Computes Shapley value credit attribution for all workers that contributed
     * to the specified plan. Returns Shapley values, Banzhaf indices, and the
     * grand coalition value.</p>
     */
    @GetMapping("/shapley-attribution")
    public ResponseEntity<ShapleyReport> getShapleyAttribution(
            @RequestParam UUID planId) {
        if (shapleyValueService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        ShapleyReport report = shapleyValueService.get().computeForPlan(planId);
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report);
    }
}
