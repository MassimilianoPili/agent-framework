package com.agentframework.orchestrator.benchmark;

import com.agentframework.orchestrator.benchmark.TrendAnalyzer.TrendResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the Longitudinal Effectiveness Benchmark.
 */
@RestController
@RequestMapping("/api/v1/benchmark")
@ConditionalOnProperty(prefix = "benchmark", name = "enabled", havingValue = "true", matchIfMissing = false)
public class BenchmarkController {

    private final BenchmarkRepository repository;
    private final TrendAnalyzer trendAnalyzer;
    private final BenchmarkCollector collector;

    public BenchmarkController(BenchmarkRepository repository,
                               TrendAnalyzer trendAnalyzer,
                               BenchmarkCollector collector) {
        this.repository = repository;
        this.trendAnalyzer = trendAnalyzer;
        this.collector = collector;
    }

    /**
     * Returns recent snapshots for a metric.
     *
     * @param metric metric name (REWARD_MEAN, COMPLETION_RATE, etc.)
     * @param days   lookback window in days (default 7)
     * @param limit  max results (default 100)
     */
    @GetMapping("/snapshots")
    public ResponseEntity<List<EffectivenessSnapshot>> getSnapshots(
            @RequestParam String metric,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "100") int limit) {

        Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
        Instant to = Instant.now();
        List<EffectivenessSnapshot> snapshots = repository.findByMetricAndRange(metric, from, to, limit);
        return ResponseEntity.ok(snapshots);
    }

    /**
     * Returns trend analysis for a specific metric.
     *
     * @param metric metric name
     */
    @GetMapping("/trends")
    public ResponseEntity<Object> getTrend(@RequestParam String metric) {
        TrendResult result = trendAnalyzer.analyzeTrend(metric);
        if (result == null) {
            return ResponseEntity.ok(Map.of(
                    "metric", metric,
                    "error", "insufficient_data",
                    "message", "Not enough snapshots for trend analysis"));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Returns trend analysis for all tracked metrics.
     */
    @GetMapping("/trends/all")
    public ResponseEntity<List<TrendResult>> getAllTrends() {
        return ResponseEntity.ok(trendAnalyzer.analyzeAllTrends());
    }

    /**
     * Triggers a manual snapshot collection (for testing).
     */
    @PostMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> triggerSnapshot() {
        int collected = collector.triggerManualSnapshot();
        return ResponseEntity.ok(Map.of(
                "status", "collected",
                "maxMetrics", collected));
    }
}
