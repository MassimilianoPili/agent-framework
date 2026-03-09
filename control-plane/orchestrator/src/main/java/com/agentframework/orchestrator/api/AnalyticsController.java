package com.agentframework.orchestrator.api;

import com.agentframework.orchestrator.analytics.*;
import com.agentframework.orchestrator.api.dto.ShapleyDagResponse;
import com.agentframework.orchestrator.orchestration.CriticalityMonitor;
import com.agentframework.orchestrator.orchestration.CriticalitySnapshot;
import com.agentframework.orchestrator.analytics.CalibrationAudit.CalibrationReport;
import com.agentframework.orchestrator.analytics.FisherInformationService.FisherUncertaintyReport;
import com.agentframework.orchestrator.analytics.ContractTheoryService.ContractEvaluationReport;
import com.agentframework.orchestrator.analytics.GoodhartDetectorService.GoodhartAuditReport;
import com.agentframework.orchestrator.analytics.ModelPredictiveControlService.MpcScheduleReport;
import com.agentframework.orchestrator.analytics.RealOptionsService.RealOptionsValuationReport;
import com.agentframework.orchestrator.analytics.ProspectTheory.ProspectEvaluation;
import com.agentframework.orchestrator.analytics.ShapleyValue.ShapleyReport;
import com.agentframework.orchestrator.analytics.VCGMechanismService.VCGPricingReport;
import com.agentframework.orchestrator.analytics.ValueOfInformationService.VoiExplorationReport;
import com.agentframework.orchestrator.budget.KellyCriterion.KellyRecommendation;
import com.agentframework.orchestrator.budget.KellyCriterionService;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.OptimalStopping.StoppingDecision;
import com.agentframework.orchestrator.orchestration.OptimalStoppingService;
import com.agentframework.orchestrator.orchestration.OrchestrationService;
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
 * calibration audit, VCG mechanism pricing, Shapley value attribution,
 * MPC scheduling, Fisher uncertainty analysis, Value of Information exploration,
 * Goodhart metric health, Real Options deferral valuation,
 * and Contract Theory incentive evaluation endpoints for the worker profile ecosystem.</p>
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
    private final Optional<ModelPredictiveControlService> mpcService;
    private final Optional<FisherInformationService> fisherService;
    private final Optional<ValueOfInformationService> voiService;
    private final Optional<GoodhartDetectorService> goodhartService;
    private final Optional<RealOptionsService> realOptionsService;
    private final Optional<ContractTheoryService> contractTheoryService;
    private final ShapleyDagService shapleyDagService;
    private final OrchestrationService orchestrationService;
    private final CriticalityMonitor criticalityMonitor;

    public AnalyticsController(ReplicatorDynamicsService replicatorDynamicsService,
                                Optional<WorkerDriftMonitor> driftMonitor,
                                Optional<ProspectTheoryService> prospectTheoryService,
                                Optional<HedgeAlgorithmService> hedgeAlgorithmService,
                                Optional<KellyCriterionService> kellyCriterionService,
                                Optional<OptimalStoppingService> optimalStoppingService,
                                Optional<CalibrationAuditService> calibrationAuditService,
                                Optional<VCGMechanismService> vcgMechanismService,
                                Optional<ShapleyValueService> shapleyValueService,
                                Optional<ModelPredictiveControlService> mpcService,
                                Optional<FisherInformationService> fisherService,
                                Optional<ValueOfInformationService> voiService,
                                Optional<GoodhartDetectorService> goodhartService,
                                Optional<RealOptionsService> realOptionsService,
                                Optional<ContractTheoryService> contractTheoryService,
                                ShapleyDagService shapleyDagService,
                                OrchestrationService orchestrationService,
                                CriticalityMonitor criticalityMonitor) {
        this.replicatorDynamicsService = replicatorDynamicsService;
        this.driftMonitor = driftMonitor;
        this.prospectTheoryService = prospectTheoryService;
        this.hedgeAlgorithmService = hedgeAlgorithmService;
        this.kellyCriterionService = kellyCriterionService;
        this.optimalStoppingService = optimalStoppingService;
        this.calibrationAuditService = calibrationAuditService;
        this.vcgMechanismService = vcgMechanismService;
        this.shapleyValueService = shapleyValueService;
        this.mpcService = mpcService;
        this.fisherService = fisherService;
        this.voiService = voiService;
        this.goodhartService = goodhartService;
        this.realOptionsService = realOptionsService;
        this.contractTheoryService = contractTheoryService;
        this.shapleyDagService = shapleyDagService;
        this.orchestrationService = orchestrationService;
        this.criticalityMonitor = criticalityMonitor;
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

    /**
     * GET /api/v1/analytics/shapley-dag?planId=&lt;uuid&gt;
     *
     * <p>Computes DAG-aware Shapley value attribution for all tasks in the plan,
     * respecting dependency structure. Unlike {@code /shapley-attribution} which
     * groups by worker profile, this endpoint attributes value per-task along the DAG.</p>
     */
    @GetMapping("/shapley-dag")
    public ResponseEntity<ShapleyDagResponse> getShapleyDag(@RequestParam UUID planId) {
        return orchestrationService.getPlan(planId)
                .map(plan -> {
                    java.util.Map<String, Double> shapleyValues = shapleyDagService.computeForPlan(plan);
                    java.util.List<PlanItem> doneItems = plan.getItems().stream()
                            .filter(i -> i.getStatus() == ItemStatus.DONE)
                            .toList();
                    return ResponseEntity.ok(ShapleyDagResponse.from(
                            planId, doneItems, shapleyValues, 1000));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/analytics/mpc-schedule?workerType=BE
     *
     * <p>Computes optimal task scheduling using Model Predictive Control
     * over a finite prediction horizon. Returns the recommended first action
     * and the full lookahead trajectory.</p>
     */
    @GetMapping("/mpc-schedule")
    public ResponseEntity<MpcScheduleReport> getMpcSchedule(
            @RequestParam String workerType) {
        if (mpcService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        MpcScheduleReport report = mpcService.get().computeSchedule(workerType);
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/v1/analytics/fisher-uncertainty?workerType=BE
     *
     * <p>Analyzes uncertainty for worker profiles using Fisher Information metrics.
     * Decomposes uncertainty into reducible (more data helps) and irreducible
     * (noise floor) components.</p>
     */
    @GetMapping("/fisher-uncertainty")
    public ResponseEntity<FisherUncertaintyReport> getFisherUncertainty(
            @RequestParam String workerType) {
        if (fisherService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        FisherUncertaintyReport report = fisherService.get().analyzeUncertainty(workerType);
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/v1/analytics/voi-exploration?workerType=BE
     *
     * <p>Evaluates exploration opportunities for worker profiles using
     * Value of Information analysis. Returns EVSI, net VoI, and a ranking
     * of profiles by exploration value.</p>
     */
    @GetMapping("/voi-exploration")
    public ResponseEntity<VoiExplorationReport> getVoiExploration(
            @RequestParam String workerType) {
        if (voiService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        VoiExplorationReport report = voiService.get().evaluateExploration(workerType);
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/v1/analytics/goodhart-audit?workerType=BE
     *
     * <p>Audits metric health for worker profiles using Goodhart's Law detection.
     * Checks for regressional, extremal, and causal Goodhart mechanisms and
     * returns per-profile health scores with remediation recommendations.</p>
     */
    @GetMapping("/goodhart-audit")
    public ResponseEntity<GoodhartAuditReport> getGoodhartAudit(
            @RequestParam String workerType) {
        if (goodhartService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        GoodhartAuditReport report = goodhartService.get().auditMetrics(workerType);
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/v1/analytics/real-options-valuation?workerType=BE
     *
     * <p>Evaluates task deferral opportunities using Real Options Theory
     * (Dixit &amp; Pindyck 1994). Computes the perpetual American option value
     * for each profile, recommending deferral or execution based on
     * expected reward vs. threshold V*.</p>
     */
    @GetMapping("/real-options-valuation")
    public ResponseEntity<RealOptionsValuationReport> getRealOptionsValuation(
            @RequestParam String workerType) {
        if (realOptionsService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        RealOptionsValuationReport report = realOptionsService.get().evaluateDeferral(workerType);
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/v1/analytics/contract-evaluation?workerType=BE
     *
     * <p>Evaluates SLA contracts for worker profiles using Contract Theory
     * (Hart &amp; Holmström 2016). Calibrates optimal contracts from historical
     * observations, evaluates performance, and checks incentive compatibility.</p>
     */
    @GetMapping("/contract-evaluation")
    public ResponseEntity<ContractEvaluationReport> getContractEvaluation(
            @RequestParam String workerType) {
        if (contractTheoryService.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        ContractEvaluationReport report = contractTheoryService.get().evaluateContracts(workerType);
        if (report == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/v1/analytics/criticality
     *
     * <p>Returns a real-time snapshot of the system's criticality state using the
     * Bak-Tang-Wiesenfeld sandpile model (#56). Includes per-WorkerType loads,
     * thresholds, topple cascades, and the overall criticality index.</p>
     */
    @GetMapping("/criticality")
    public ResponseEntity<CriticalitySnapshot> getCriticalitySnapshot() {
        CriticalitySnapshot snapshot = criticalityMonitor.computeSnapshot();
        return ResponseEntity.ok(snapshot);
    }
}
