package com.agentframework.orchestrator.api;

import com.agentframework.orchestrator.analytics.DriftResult;
import com.agentframework.orchestrator.analytics.ReplicatorDynamicsService;
import com.agentframework.orchestrator.analytics.WorkerDriftMonitor;
import com.agentframework.orchestrator.analytics.WorkerPopulationReport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * REST API for system-level analytics.
 *
 * <p>Provides population analysis (replicator dynamics) and drift detection
 * (Wasserstein distance) endpoints for the worker profile ecosystem.</p>
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final ReplicatorDynamicsService replicatorDynamicsService;
    private final Optional<WorkerDriftMonitor> driftMonitor;

    public AnalyticsController(ReplicatorDynamicsService replicatorDynamicsService,
                                Optional<WorkerDriftMonitor> driftMonitor) {
        this.replicatorDynamicsService = replicatorDynamicsService;
        this.driftMonitor = driftMonitor;
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
}
