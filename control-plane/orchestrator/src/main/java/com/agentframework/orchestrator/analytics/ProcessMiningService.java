package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Discovers workflow process models from task execution logs using Process Mining
 * (van der Aalst, 2004 — Alpha Algorithm, simplified variant).
 *
 * <p>The Alpha Algorithm discovers a Petri net model from an event log by mining
 * directly-follows relations and inferring causal dependencies, parallelism, and choice.</p>
 *
 * <p>Event log source: each plan's worker-type execution sequence, extracted from
 * {@link TaskOutcomeRepository#findPlanWorkerRewardSummary()}.
 * Each plan is a trace; each worker type in the plan is an activity.</p>
 *
 * <p>Alpha Algorithm relations:</p>
 * <ul>
 *   <li><b>Direct-follows</b>: A &gt;_L B — B immediately follows A in some trace</li>
 *   <li><b>Causality</b>: A → B — A &gt;_L B and ¬(B &gt;_L A)</li>
 *   <li><b>Parallelism</b>: A ∥ B — A &gt;_L B and B &gt;_L A (both orders observed)</li>
 *   <li><b>Choice</b>: A # B — ¬(A &gt;_L B) and ¬(B &gt;_L A)</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "process-mining", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProcessMiningService {

    private static final Logger log = LoggerFactory.getLogger(ProcessMiningService.class);

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${process-mining.min-support:0.1}")
    private double minSupport;

    public ProcessMiningService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Discovers a process model from plan execution traces.
     *
     * @return process model report, or {@code null} if no data exists
     */
    public ProcessModelReport discover() {
        List<Object[]> rows = taskOutcomeRepository.findPlanWorkerRewardSummary();
        if (rows.isEmpty()) return null;

        // Build traces: plan_id → ordered list of worker_types
        // findPlanWorkerRewardSummary returns [plan_id_text, worker_type, reward]
        Map<String, List<String>> traces = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String planId     = (String) row[0];
            String workerType = (String) row[1];
            traces.computeIfAbsent(planId, k -> new ArrayList<>()).add(workerType);
        }

        int totalTraces = traces.size();

        // ── Direct-follows matrix ──────────────────────────────────────────
        Map<String, Map<String, Integer>> dfCounts = new HashMap<>();
        for (List<String> trace : traces.values()) {
            for (int i = 0; i + 1 < trace.size(); i++) {
                String a = trace.get(i);
                String b = trace.get(i + 1);
                dfCounts.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1, Integer::sum);
            }
        }

        // Filter by minimum support threshold
        Map<String, Set<String>> directFollows = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : dfCounts.entrySet()) {
            for (Map.Entry<String, Integer> f : e.getValue().entrySet()) {
                if ((double) f.getValue() / totalTraces >= minSupport) {
                    directFollows.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>())
                                 .add(f.getKey());
                }
            }
        }

        // ── Alpha algorithm relations ──────────────────────────────────────
        Set<String>      allActivities    = new LinkedHashSet<>();
        traces.values().forEach(allActivities::addAll);

        Map<String, Set<String>> causalRelations   = new LinkedHashMap<>();
        List<String[]>           parallelPairs     = new ArrayList<>();
        List<String[]>           choicePoints      = new ArrayList<>();

        for (String a : allActivities) {
            for (String b : allActivities) {
                if (a.equals(b)) continue;
                boolean aToB = follows(directFollows, a, b);
                boolean bToA = follows(directFollows, b, a);

                if (aToB && !bToA) {
                    causalRelations.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
                } else if (aToB && bToA && a.compareTo(b) < 0) {
                    parallelPairs.add(new String[]{a, b});
                } else if (!aToB && !bToA && a.compareTo(b) < 0) {
                    choicePoints.add(new String[]{a, b});
                }
            }
        }

        // ── Loop detection: DFS cycle search in causal graph ──────────────
        boolean loopDetected = detectCycle(causalRelations);

        // ── Fitness: fraction of traces replayable on discovered model ─────
        int replayable = 0;
        for (List<String> trace : traces.values()) {
            if (isReplayable(trace, directFollows, parallelPairs)) replayable++;
        }
        double fitness = totalTraces > 0 ? (double) replayable / totalTraces : 1.0;

        // Collect discovered causal sequences as human-readable strings
        List<String> discoveredSequences = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : causalRelations.entrySet()) {
            for (String b : e.getValue()) {
                discoveredSequences.add(e.getKey() + " \u2192 " + b);
            }
        }
        Collections.sort(discoveredSequences);

        log.debug("ProcessMining: traces={} activities={} causal={} parallel={} loops={} fitness={}",
                totalTraces, allActivities.size(), causalRelations.size(),
                parallelPairs.size(), loopDetected, fitness);

        return new ProcessModelReport(
                directFollows, causalRelations, parallelPairs, choicePoints,
                fitness, loopDetected, discoveredSequences
        );
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private boolean follows(Map<String, Set<String>> df, String a, String b) {
        Set<String> s = df.get(a);
        return s != null && s.contains(b);
    }

    private boolean detectCycle(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String node : graph.keySet()) {
            if (dfsCycle(node, graph, visited, inStack)) return true;
        }
        return false;
    }

    private boolean dfsCycle(String node, Map<String, Set<String>> graph,
                              Set<String> visited, Set<String> inStack) {
        if (inStack.contains(node)) return true;
        if (visited.contains(node)) return false;
        visited.add(node);
        inStack.add(node);
        for (String next : graph.getOrDefault(node, Collections.emptySet())) {
            if (dfsCycle(next, graph, visited, inStack)) return true;
        }
        inStack.remove(node);
        return false;
    }

    private boolean isReplayable(List<String> trace, Map<String, Set<String>> df,
                                  List<String[]> parallel) {
        for (int i = 0; i + 1 < trace.size(); i++) {
            String a = trace.get(i);
            String b = trace.get(i + 1);
            if (follows(df, a, b)) continue;
            // Allow parallel (unordered) pairs
            boolean inParallel = parallel.stream().anyMatch(p ->
                    (p[0].equals(a) && p[1].equals(b)) || (p[0].equals(b) && p[1].equals(a)));
            if (!inParallel) return false;
        }
        return true;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * Discovered process model report.
     *
     * @param directFollows       A &gt;_L B relations (filtered by minSupport)
     * @param causalRelations     A → B causal dependencies
     * @param parallelActivities  pairs [A,B] where A ∥ B
     * @param choicePoints        pairs [A,B] where A # B
     * @param fitness             fraction of traces replayable on the model ∈ [0,1]
     * @param loopDetected        true if a cycle exists in the causal graph
     * @param discoveredSequences human-readable causal sequences (e.g. "A → B")
     */
    public record ProcessModelReport(
            Map<String, Set<String>> directFollows,
            Map<String, Set<String>> causalRelations,
            List<String[]> parallelActivities,
            List<String[]> choicePoints,
            double fitness,
            boolean loopDetected,
            List<String> discoveredSequences
    ) {}
}
