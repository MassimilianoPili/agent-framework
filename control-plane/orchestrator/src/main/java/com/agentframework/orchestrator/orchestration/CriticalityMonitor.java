package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.config.CriticalityProperties;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.orchestrator.metrics.OrchestratorMetrics;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Monitors system-level load using the Bak-Tang-Wiesenfeld sandpile model
 * for Self-Organized Criticality (SOC) (#56).
 *
 * <p>Load per WorkerType:
 * <pre>
 *   load[wt] = pending_count + failed_count × 2 + stale_count × 1.5
 * </pre>
 *
 * <p>Threshold per WorkerType:
 * <pre>
 *   threshold[wt] = target_inventory × 3  (default target_inventory = 5, threshold = 15)
 * </pre>
 *
 * <p>Criticality index C = max(load[wt] / threshold[wt]):
 * <ul>
 *   <li>C &lt; thresholdStable → STABLE (debug log)</li>
 *   <li>thresholdStable ≤ C &lt; thresholdWarning → WARNING (warn log)</li>
 *   <li>C ≥ thresholdWarning → ALERT (error log + publishes {@code SYSTEM_CRITICALITY} event)</li>
 * </ul>
 *
 * <p>Neighbours model retry-storm propagation paths:
 * CONTEXT_MANAGER failure spills load to dependent domain workers (BE, FE, DBA, AI_TASK).
 *
 * @see SandpileSimulator
 * @see <a href="https://doi.org/10.1103/PhysRevLett.59.381">
 *     Bak, Tang &amp; Wiesenfeld (1987)</a>
 */
@Component
public class CriticalityMonitor {

    private static final Logger log = LoggerFactory.getLogger(CriticalityMonitor.class);

    private final PlanItemRepository planItemRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrchestratorMetrics metrics;
    private final CriticalityProperties properties;

    @Value("${stale.timeout-minutes:30}")
    private int staleTimeoutMinutes;

    public CriticalityMonitor(PlanItemRepository planItemRepository,
                               ApplicationEventPublisher eventPublisher,
                               OrchestratorMetrics metrics,
                               CriticalityProperties properties) {
        this.planItemRepository = planItemRepository;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${criticality.interval-ms:30000}")
    public void evaluate() {
        if (!properties.enabled()) return;

        CriticalitySnapshot snapshot = computeSnapshot();
        CriticalityLevel level = CriticalityLevel.from(
                snapshot.criticalityIndex(),
                properties.thresholdStable(),
                properties.thresholdWarning());

        // Record Prometheus metrics
        metrics.recordCriticalityIndex(snapshot.criticalityIndex());
        snapshot.loads().forEach(metrics::recordWorkerLoad);
        snapshot.toppled().forEach(metrics::recordToppleEvent);

        switch (level) {
            case STABLE -> log.debug("CriticalityMonitor: C={} (STABLE)", format(snapshot.criticalityIndex()));
            case WARNING -> log.warn("CriticalityMonitor: C={} (WARNING) — toppled: {}",
                    format(snapshot.criticalityIndex()), snapshot.toppled());
            case ALERT -> {
                log.error("CriticalityMonitor: C={} (ALERT) — toppled: {} — publishing SYSTEM_CRITICALITY",
                        format(snapshot.criticalityIndex()), snapshot.toppled());
                eventPublisher.publishEvent(
                        SpringPlanEvent.forSystem(SpringPlanEvent.SYSTEM_CRITICALITY));
            }
        }
    }

    /**
     * Computes a full criticality snapshot on demand.
     * Used by both the scheduled evaluation and the REST analytics endpoint.
     */
    public CriticalitySnapshot computeSnapshot() {
        Map<String, Double> loads      = computeLoads();
        Map<String, Double> thresholds = computeThresholds();
        Map<String, List<String>> neighbours = buildNeighbourGraph();

        if (loads.isEmpty()) {
            return new CriticalitySnapshot(0.0, "STABLE",
                    loads, thresholds, List.of(), loads, Instant.now());
        }

        SandpileSimulator simulator = new SandpileSimulator(
                loads, thresholds, neighbours,
                properties.spilloverRatio(), properties.maxToppleIterations());
        List<String> toppled = simulator.stabilise();
        double c = simulator.criticalityIndex();

        String level = CriticalityLevel.from(c,
                properties.thresholdStable(), properties.thresholdWarning()).name();

        return new CriticalitySnapshot(
                c, level, loads, thresholds, toppled,
                simulator.getLoads(), Instant.now());
    }

    // ── package-private for testing ──────────────────────────────────────────

    Map<String, Double> computeLoads() {
        Instant staleCutoff = Instant.now().minus(Duration.ofMinutes(staleTimeoutMinutes));

        Map<String, Long> pending = toMap(planItemRepository.countPendingByWorkerType());
        Map<String, Long> failed  = toMap(planItemRepository.countFailedByWorkerType());
        Map<String, Long> stale   = toMap(planItemRepository.countStaleDispatchedByWorkerType(staleCutoff));

        Set<String> allTypes = new HashSet<>();
        allTypes.addAll(pending.keySet());
        allTypes.addAll(failed.keySet());
        allTypes.addAll(stale.keySet());

        Map<String, Double> loads = new LinkedHashMap<>();
        for (String wt : allTypes) {
            double load = pending.getOrDefault(wt, 0L)
                        + failed.getOrDefault(wt, 0L) * 2.0
                        + stale.getOrDefault(wt, 0L) * 1.5;
            loads.put(wt, load);
        }
        return loads;
    }

    Map<String, Double> computeThresholds() {
        Map<String, Double> thresholds = new LinkedHashMap<>();
        double t = properties.targetInventory() * 3.0;
        for (WorkerType wt : WorkerType.values()) {
            thresholds.put(wt.name(), t);
        }
        return thresholds;
    }

    /**
     * Adjacency graph capturing retry-storm propagation paths.
     * CONTEXT_MANAGER failure spills to dependent domain workers.
     */
    Map<String, List<String>> buildNeighbourGraph() {
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("CONTEXT_MANAGER", List.of("BE", "FE", "DBA", "AI_TASK", "MOBILE"));
        graph.put("BE",              List.of("REVIEW", "CONTEXT_MANAGER"));
        graph.put("FE",              List.of("REVIEW", "CONTEXT_MANAGER"));
        graph.put("DBA",             List.of("REVIEW", "CONTEXT_MANAGER"));
        graph.put("AI_TASK",         List.of("REVIEW"));
        graph.put("REVIEW",          List.of("BE", "FE"));
        graph.put("SCHEMA_MANAGER",  List.of("BE", "FE", "DBA"));
        graph.put("HOOK_MANAGER",    List.of("BE", "FE", "AI_TASK"));
        graph.put("TASK_MANAGER",    List.of("CONTEXT_MANAGER", "SCHEMA_MANAGER"));
        return graph;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Converts aggregate query results to a Map.
     * The query returns Object[]{WorkerType enum, Long count}.
     */
    private Map<String, Long> toMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
                row -> ((WorkerType) row[0]).name(),
                row -> (Long) row[1]));
    }

    private String format(double c) {
        return String.format("%.3f", c);
    }

    enum CriticalityLevel {
        STABLE, WARNING, ALERT;

        static CriticalityLevel from(double c, double stableThreshold, double warningThreshold) {
            if (c >= warningThreshold) return ALERT;
            if (c >= stableThreshold)  return WARNING;
            return STABLE;
        }
    }
}
