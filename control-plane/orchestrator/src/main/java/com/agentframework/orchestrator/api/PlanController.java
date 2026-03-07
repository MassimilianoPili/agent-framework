package com.agentframework.orchestrator.api;

import com.agentframework.orchestrator.api.dto.DispatchAttemptResponse;
import com.agentframework.orchestrator.api.dto.PlanCostResponse;
import com.agentframework.orchestrator.api.dto.PlanRequest;
import com.agentframework.orchestrator.api.dto.PlanResponse;
import com.agentframework.orchestrator.api.dto.PlanSnapshotResponse;
import com.agentframework.orchestrator.api.dto.QualityGateReportResponse;
import com.agentframework.orchestrator.analytics.RootCauseAnalyzer;
import com.agentframework.orchestrator.budget.CovarianceMatrix;
import com.agentframework.orchestrator.budget.PortfolioOptimizer;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.domain.IllegalStateTransitionException;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.graph.CriticalPathCalculator;
import com.agentframework.orchestrator.graph.PlanGraphService;
import com.agentframework.orchestrator.graph.SpectralAnalyzer;
import com.agentframework.orchestrator.graph.SpectralMetrics;
import com.agentframework.orchestrator.orchestration.OrchestrationService;
import com.agentframework.orchestrator.repository.QualityGateReportRepository;
import com.agentframework.orchestrator.service.PlanSnapshotService;
import com.agentframework.orchestrator.sse.SseEmitterRegistry;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private static final Logger log = LoggerFactory.getLogger(PlanController.class);

    private final OrchestrationService orchestrationService;
    private final PlanSnapshotService snapshotService;
    private final QualityGateReportRepository reportRepository;
    private final PlanGraphService planGraphService;
    private final CriticalPathCalculator criticalPathCalculator;
    private final SpectralAnalyzer spectralAnalyzer;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final PlanItemRepository planItemRepository;
    private final Optional<TaskOutcomeRepository> taskOutcomeRepository;
    private final Optional<RootCauseAnalyzer> rootCauseAnalyzer;

    public PlanController(OrchestrationService orchestrationService,
                          PlanSnapshotService snapshotService,
                          QualityGateReportRepository reportRepository,
                          PlanGraphService planGraphService,
                          CriticalPathCalculator criticalPathCalculator,
                          SpectralAnalyzer spectralAnalyzer,
                          SseEmitterRegistry sseEmitterRegistry,
                          PlanItemRepository planItemRepository,
                          Optional<TaskOutcomeRepository> taskOutcomeRepository,
                          Optional<RootCauseAnalyzer> rootCauseAnalyzer) {
        this.orchestrationService = orchestrationService;
        this.snapshotService = snapshotService;
        this.reportRepository = reportRepository;
        this.planGraphService = planGraphService;
        this.criticalPathCalculator = criticalPathCalculator;
        this.spectralAnalyzer = spectralAnalyzer;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.planItemRepository = planItemRepository;
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.rootCauseAnalyzer = rootCauseAnalyzer;
    }

    /**
     * POST /api/v1/plans
     * Creates a new execution plan from a natural language specification.
     * Returns 202 Accepted with the plan in RUNNING state.
     */
    @PostMapping
    public ResponseEntity<PlanResponse> createPlan(@Valid @RequestBody PlanRequest request) {
        log.info("Received plan request: {}...",
                 request.spec().substring(0, Math.min(80, request.spec().length())));

        Plan plan = orchestrationService.createAndStart(request.spec(), request.budget(), request.projectPath());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(PlanResponse.from(plan));
    }

    /**
     * GET /api/v1/plans/{id}
     * Returns the current state of a plan with item statuses.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PlanResponse> getPlan(@PathVariable UUID id) {
        return orchestrationService.getPlan(id)
            .map(plan -> ResponseEntity.ok(PlanResponse.from(plan)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/v1/plans/{id}/items/{itemId}/retry
     * Retries a failed plan item. Transitions FAILED → WAITING and triggers dispatch.
     */
    @PostMapping("/{id}/items/{itemId}/retry")
    public ResponseEntity<?> retryItem(@PathVariable UUID id, @PathVariable UUID itemId) {
        try {
            orchestrationService.retryFailedItem(itemId);
            log.info("Retry triggered for item {} in plan {}", itemId, id);
            return ResponseEntity.accepted().body(Map.of(
                    "status", "retrying",
                    "itemId", itemId.toString(),
                    "planId", id.toString()));
        } catch (IllegalStateTransitionException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()));
        }
    }

    /**
     * GET /api/v1/plans/{id}/items/{itemId}/attempts
     * Returns the dispatch history for a plan item.
     */
    @GetMapping("/{id}/items/{itemId}/attempts")
    public ResponseEntity<List<DispatchAttemptResponse>> getAttempts(
            @PathVariable UUID id, @PathVariable UUID itemId) {
        return ResponseEntity.ok(orchestrationService.getAttempts(itemId).stream()
            .map(DispatchAttemptResponse::from).toList());
    }

    /**
     * GET /api/v1/plans/{id}/snapshots
     * Lists all snapshots for a plan.
     */
    @GetMapping("/{id}/snapshots")
    public ResponseEntity<List<PlanSnapshotResponse>> listSnapshots(@PathVariable UUID id) {
        return ResponseEntity.ok(snapshotService.listSnapshots(id).stream()
            .map(PlanSnapshotResponse::from).toList());
    }

    /**
     * POST /api/v1/plans/{id}/restore/{snapshotId}
     * Restores a plan to the state captured in the given snapshot.
     */
    @PostMapping("/{id}/restore/{snapshotId}")
    public ResponseEntity<?> restoreSnapshot(@PathVariable UUID id,
                                              @PathVariable UUID snapshotId) {
        try {
            snapshotService.restore(snapshotId);
            log.info("Plan {} restored from snapshot {}", id, snapshotId);
            return ResponseEntity.ok(Map.of(
                    "status", "restored",
                    "planId", id.toString(),
                    "snapshotId", snapshotId.toString()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()));
        }
    }

    /**
     * POST /api/v1/plans/{id}/resume
     * Resumes a PAUSED plan. Transitions PAUSED → RUNNING and re-triggers dispatch.
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resumePlan(@PathVariable UUID id) {
        try {
            orchestrationService.resumePlan(id);
            log.info("Plan {} resumed", id);
            return ResponseEntity.accepted().body(Map.of(
                    "status", "resuming",
                    "planId", id.toString()));
        } catch (IllegalStateTransitionException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()));
        }
    }

    /**
     * PUT /api/v1/plans/{id}/items/{itemId}/issue-snapshot
     * Internal endpoint called by the TASK_MANAGER worker to persist the fetched
     * issue snapshot on the PlanItem. Not intended for external callers.
     */
    @PutMapping("/{id}/items/{itemId}/issue-snapshot")
    public ResponseEntity<?> saveIssueSnapshot(@PathVariable UUID id,
                                               @PathVariable UUID itemId,
                                               @RequestBody Map<String, String> body) {
        return planItemRepository.findById(itemId)
            .map(item -> {
                item.setIssueSnapshot(body.get("snapshotJson"));
                planItemRepository.save(item);
                log.info("Issue snapshot saved for item {} in plan {}", itemId, id);
                return ResponseEntity.ok(Map.of("status", "saved", "itemId", itemId.toString()));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/v1/plans/{id}/items/{itemId}/approve
     * Approves a task that was held in AWAITING_APPROVAL (riskLevel=CRITICAL).
     * Transitions: AWAITING_APPROVAL → WAITING (re-enters dispatch queue).
     */
    @PostMapping("/{id}/items/{itemId}/approve")
    public ResponseEntity<?> approveItem(@PathVariable UUID id,
                                         @PathVariable UUID itemId) {
        return planItemRepository.findById(itemId)
            .map(item -> {
                if (item.getStatus() != ItemStatus.AWAITING_APPROVAL) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Item is not in AWAITING_APPROVAL state",
                                         "currentStatus", item.getStatus().name()));
                }
                item.transitionTo(ItemStatus.WAITING);
                planItemRepository.save(item);
                log.info("Item {} approved (plan={}), re-entering dispatch queue", itemId, id);
                // Trigger a new dispatch wave without altering plan status
                // (plan stays RUNNING — resumePlan() would throw if not PAUSED)
                orchestrationService.triggerDispatch(id);
                return ResponseEntity.ok(Map.of("status", "approved", "itemId", itemId.toString()));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/v1/plans/{id}/items/{itemId}/reject
     * Rejects a task that was held in AWAITING_APPROVAL.
     * Transitions: AWAITING_APPROVAL → FAILED.
     */
    @PostMapping("/{id}/items/{itemId}/reject")
    public ResponseEntity<?> rejectItem(@PathVariable UUID id,
                                         @PathVariable UUID itemId,
                                         @RequestBody(required = false) Map<String, String> body) {
        return planItemRepository.findById(itemId)
            .map(item -> {
                if (item.getStatus() != ItemStatus.AWAITING_APPROVAL) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Item is not in AWAITING_APPROVAL state",
                                         "currentStatus", item.getStatus().name()));
                }
                String reason = body != null ? body.getOrDefault("reason", "Rejected by human reviewer") : "Rejected by human reviewer";
                item.transitionTo(ItemStatus.FAILED);
                item.setFailureReason(reason);
                item.setCompletedAt(Instant.now());
                planItemRepository.save(item);
                log.info("Item {} rejected (plan={}, reason={})", itemId, id, reason);
                return ResponseEntity.ok(Map.of("status", "rejected", "itemId", itemId.toString()));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/v1/plans/{id}/items/{itemId}/compensate
     * Creates a COMPENSATOR_MANAGER task to undo the effects of the specified item.
     * The compensation task is added to the plan and dispatched immediately.
     * Optionally accepts a JSON body with a "reason" field.
     */
    @PostMapping("/{id}/items/{itemId}/compensate")
    public ResponseEntity<?> compensateItem(@PathVariable UUID id,
                                             @PathVariable UUID itemId,
                                             @RequestBody(required = false) Map<String, String> body) {
        try {
            String reason = body != null
                    ? body.getOrDefault("reason", "Manual compensation requested")
                    : "Manual compensation requested";
            var compensationItem = orchestrationService.createCompensationTask(itemId, reason);
            log.info("Compensation requested for item {} in plan {} (compensationTask={})",
                     itemId, id, compensationItem.getId());
            return ResponseEntity.accepted().body(Map.of(
                    "status", "compensation_started",
                    "compensationItemId", compensationItem.getId().toString(),
                    "compensationKey",    compensationItem.getTaskKey(),
                    "planId",             id.toString()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/v1/plans/{id}/quality-gate
     * Returns the quality gate report for a completed plan.
     */
    @GetMapping("/{id}/quality-gate")
    public ResponseEntity<QualityGateReportResponse> getQualityGateReport(@PathVariable UUID id) {
        return reportRepository.findByPlanId(id)
            .map(report -> ResponseEntity.ok(QualityGateReportResponse.from(report)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/plans/{id}/events
     * Returns a Server-Sent Events stream of plan state changes.
     * Each event carries a JSON-serialized SpringPlanEvent.
     * The connection stays open until the plan reaches a terminal state or the client disconnects.
     * Timeout: 5 minutes (after which the client should reconnect).
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPlanEvents(@PathVariable UUID id) {
        log.info("SSE client connected to plan {}", id);
        return sseEmitterRegistry.subscribe(id);
    }

    /**
     * GET /api/v1/plans/{id}/schedule
     *
     * <p>Returns the tropical-geometry critical path schedule: EST, LST, float, makespan,
     * and critical path for every task in the plan. DONE items use actual elapsed time;
     * others use a 5-minute default estimate.</p>
     */
    @GetMapping("/{id}/schedule")
    public ResponseEntity<CriticalPathCalculator.ScheduleView> getPlanSchedule(@PathVariable UUID id) {
        return orchestrationService.getPlan(id)
            .map(plan -> ResponseEntity.ok(criticalPathCalculator.buildView(plan)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/plans/{id}/spectral
     *
     * <p>Returns spectral graph theory metrics for the plan's task DAG: Fiedler value
     * (algebraic connectivity), spectral gap, bi-partition from the Fiedler eigenvector,
     * and bottleneck nodes near the partition boundary.</p>
     */
    @GetMapping("/{id}/spectral")
    public ResponseEntity<SpectralMetrics> getPlanSpectral(@PathVariable UUID id) {
        return orchestrationService.getPlan(id)
            .map(plan -> ResponseEntity.ok(spectralAnalyzer.analyse(plan)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/plans/{id}/graph?format=mermaid|json
     *
     * <p>Returns the task DAG of a plan as a Mermaid diagram (default) or a JSON adjacency list.
     * Each node includes: taskKey, workerType, status, duration, and token usage (when available).
     * Edges represent {@code dependsOn} relationships, with inferred labels for context/schema
     * dependencies.
     *
     * <p>The Mermaid output can be embedded directly in Gitea/GitHub Markdown:
     * <pre>
     * ```mermaid
     * graph LR
     *   CM_001["CM-001\n📂 CONTEXT_MANAGER\nDONE\n8s | 1.2k tk"]:::st_DONE
     *   BE_001["BE-001\n⚙ BE · be-java\nRUNNING"]:::st_RUNNING
     *   CM_001 -->|context| BE_001
     * ```
     * </pre>
     */
    @GetMapping(value = "/{id}/graph")
    public ResponseEntity<String> getPlanGraph(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "mermaid") String format) {

        return orchestrationService.getPlan(id)
            .map(plan -> {
                String body;
                MediaType contentType;
                if ("json".equalsIgnoreCase(format)) {
                    body = planGraphService.toJson(plan);
                    contentType = MediaType.APPLICATION_JSON;
                } else {
                    body = planGraphService.toMermaid(plan);
                    contentType = MediaType.TEXT_PLAIN;
                }
                return ResponseEntity.ok()
                    .contentType(contentType)
                    .body(body);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/plans/{id}/council-report
     *
     * <p>Returns the pre-planning {@code CouncilReport} stored on the plan as raw JSON,
     * or 404 if the plan is not found, or 204 No Content if council was disabled or the
     * plan predates the council feature.</p>
     */
    @GetMapping(value = "/{id}/council-report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getCouncilReport(@PathVariable UUID id) {
        var planOpt = orchestrationService.getPlan(id);
        if (planOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String report = planOpt.get().getCouncilReport();
        if (report == null || report.isBlank()) {
            return ResponseEntity.<String>status(HttpStatus.NO_CONTENT).build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(report);
    }

    /**
     * GET /api/v1/plans/{id}/portfolio-analysis
     *
     * <p>Performs Markowitz Mean-Variance portfolio optimization on the historical
     * reward data of worker types used in this plan. Returns optimal budget allocation
     * weights, expected return, volatility, and Sharpe ratio.</p>
     *
     * <p>Read-only analysis endpoint — does not modify plan state.</p>
     */
    @GetMapping("/{id}/portfolio-analysis")
    public ResponseEntity<PortfolioOptimizer.PortfolioResult> getPortfolioAnalysis(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0.5") double riskTolerance) {

        if (taskOutcomeRepository.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        return orchestrationService.getPlan(id)
            .map(plan -> {
                // Collect worker types used in this plan
                Set<String> planWorkerTypes = plan.getItems().stream()
                        .map(item -> item.getWorkerType().name())
                        .collect(Collectors.toCollection(LinkedHashSet::new));

                if (planWorkerTypes.isEmpty()) {
                    return ResponseEntity.ok(new PortfolioOptimizer.PortfolioResult(
                            Map.of(), 0, 0, 0, true));
                }

                // Load historical rewards per worker type
                List<Object[]> rows = taskOutcomeRepository.get().findRewardsByWorkerType();

                // Group rewards by worker type, filtering to only types used in this plan
                Map<String, List<Double>> rewardMap = new LinkedHashMap<>();
                for (String wt : planWorkerTypes) {
                    rewardMap.put(wt, new ArrayList<>());
                }
                for (Object[] row : rows) {
                    String wt = (String) row[0];
                    if (rewardMap.containsKey(wt)) {
                        rewardMap.get(wt).add(((Number) row[1]).doubleValue());
                    }
                }

                String[] types = rewardMap.keySet().toArray(new String[0]);
                double[][] history = new double[types.length][];
                for (int i = 0; i < types.length; i++) {
                    List<Double> rewards = rewardMap.get(types[i]);
                    history[i] = rewards.stream().mapToDouble(Double::doubleValue).toArray();
                }

                CovarianceMatrix cov = new CovarianceMatrix(types, history);
                PortfolioOptimizer optimizer = new PortfolioOptimizer(cov, riskTolerance);
                return ResponseEntity.ok(optimizer.optimize());
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/plans/{id}/items/{taskKey}/root-cause
     *
     * <p>Runs causal inference (Pearl's do-calculus) on a task outcome to identify
     * the root cause of success or failure. Requires the causal inference module.</p>
     */
    @GetMapping("/{id}/items/{taskKey}/root-cause")
    public ResponseEntity<RootCauseAnalyzer.RootCauseReport> getRootCause(
            @PathVariable UUID id,
            @PathVariable String taskKey) {
        if (rootCauseAnalyzer.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        try {
            return ResponseEntity.ok(rootCauseAnalyzer.get().analyseTask(id, taskKey));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /api/v1/plans/{id}/cost
     *
     * <p>Returns the total and per-task token usage and estimated cost breakdown for a plan.
     * Tasks that have not yet completed (or whose workers did not report token usage)
     * contribute zero to the totals.</p>
     */
    @GetMapping("/{id}/cost")
    public ResponseEntity<PlanCostResponse> getPlanCost(@PathVariable UUID id) {
        return orchestrationService.getPlan(id)
            .map(plan -> ResponseEntity.ok(PlanCostResponse.from(plan)))
            .orElse(ResponseEntity.notFound().build());
    }
}
