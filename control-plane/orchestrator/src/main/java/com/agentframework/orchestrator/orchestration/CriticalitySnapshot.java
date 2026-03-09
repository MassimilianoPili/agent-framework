package com.agentframework.orchestrator.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of the system's criticality state (#56).
 *
 * <p>Produced by {@link CriticalityMonitor#computeSnapshot()} and consumed by
 * both the scheduled evaluation loop and the REST analytics endpoint.</p>
 *
 * @param criticalityIndex C = max(load[wt] / threshold[wt]) — overall system stress
 * @param level            STABLE / WARNING / ALERT
 * @param loads            per-WorkerType load <em>before</em> stabilisation
 * @param thresholds       per-WorkerType topple threshold
 * @param toppled          ordered list of nodes that toppled during stabilisation
 * @param loadsAfterStabilise per-WorkerType load <em>after</em> sandpile stabilisation
 * @param evaluatedAt      timestamp of the evaluation
 */
public record CriticalitySnapshot(
    double criticalityIndex,
    String level,
    Map<String, Double> loads,
    Map<String, Double> thresholds,
    List<String> toppled,
    Map<String, Double> loadsAfterStabilise,
    Instant evaluatedAt
) {}
