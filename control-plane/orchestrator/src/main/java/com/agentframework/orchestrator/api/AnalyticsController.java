package com.agentframework.orchestrator.api;

import com.agentframework.orchestrator.analytics.ReplicatorDynamicsService;
import com.agentframework.orchestrator.analytics.WorkerPopulationReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for system-level analytics.
 *
 * <p>Provides population analysis endpoints powered by evolutionary game theory
 * (replicator dynamics on worker profiles).</p>
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final ReplicatorDynamicsService replicatorDynamicsService;

    public AnalyticsController(ReplicatorDynamicsService replicatorDynamicsService) {
        this.replicatorDynamicsService = replicatorDynamicsService;
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
}
