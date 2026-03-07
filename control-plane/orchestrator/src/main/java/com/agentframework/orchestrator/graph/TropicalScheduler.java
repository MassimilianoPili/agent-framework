package com.agentframework.orchestrator.graph;

import java.util.*;

/**
 * Computes critical path scheduling (EST, LST, float, makespan) for a task DAG.
 *
 * <p>Uses the max-plus dual of tropical algebra for the forward pass (AND-join semantics:
 * a task starts when ALL predecessors complete) and min for the backward pass (latest
 * start time computation).</p>
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Topological sort (Kahn's algorithm on predecessors)</li>
 *   <li>Forward pass: {@code EST[j] = max_i(EST[i] + duration[i])} for each predecessor i</li>
 *   <li>Makespan: {@code max_n(EST[n] + duration[n])}</li>
 *   <li>Backward pass: {@code LST[i] = min_j(LST[j]) - duration[i]} for each successor j</li>
 *   <li>Total float: {@code float[i] = LST[i] - EST[i]}</li>
 *   <li>Critical path: nodes with {@code |float| < ε} (ε = 1e-9)</li>
 * </ol>
 */
public class TropicalScheduler {

    private static final double EPSILON = 1e-9;

    private final Map<String, Double> durations;
    private final Map<String, List<String>> predecessors;

    /**
     * @param durations    task key → duration in milliseconds
     * @param predecessors task key → list of predecessor task keys
     */
    public TropicalScheduler(Map<String, Double> durations,
                              Map<String, List<String>> predecessors) {
        this.durations = new LinkedHashMap<>(durations);
        this.predecessors = new LinkedHashMap<>(predecessors);
    }

    /**
     * Computes the full schedule: EST, LST, float, critical path, and makespan.
     */
    public ScheduleResult compute() {
        List<String> topoOrder = topologicalSort();

        if (topoOrder.isEmpty()) {
            return new ScheduleResult(Map.of(), Map.of(), Map.of(), List.of(), 0.0);
        }

        // Build successor map (inverse of predecessors)
        Map<String, List<String>> successors = new LinkedHashMap<>();
        for (String task : topoOrder) {
            successors.put(task, new ArrayList<>());
        }
        for (String task : topoOrder) {
            for (String pred : predecessors.getOrDefault(task, List.of())) {
                successors.computeIfAbsent(pred, k -> new ArrayList<>()).add(task);
            }
        }

        // Forward pass: EST[j] = max_i(EST[i] + duration[i]) for each predecessor i
        Map<String, Double> est = new LinkedHashMap<>();
        for (String task : topoOrder) {
            double earliest = 0.0;
            for (String pred : predecessors.getOrDefault(task, List.of())) {
                double predFinish = est.getOrDefault(pred, 0.0) + durations.getOrDefault(pred, 0.0);
                earliest = Math.max(earliest, predFinish);
            }
            est.put(task, earliest);
        }

        // Makespan = max(EST[n] + duration[n])
        double makespan = 0.0;
        for (String task : topoOrder) {
            double finish = est.get(task) + durations.getOrDefault(task, 0.0);
            makespan = Math.max(makespan, finish);
        }

        // Backward pass: LST[i] = min_j(LST[j]) - duration[i] for each successor j
        Map<String, Double> lst = new LinkedHashMap<>();
        List<String> reverseTopo = new ArrayList<>(topoOrder);
        Collections.reverse(reverseTopo);
        for (String task : reverseTopo) {
            List<String> succs = successors.getOrDefault(task, List.of());
            if (succs.isEmpty()) {
                // Sink node: LST = makespan - duration
                lst.put(task, makespan - durations.getOrDefault(task, 0.0));
            } else {
                double latest = Double.POSITIVE_INFINITY;
                for (String succ : succs) {
                    latest = Math.min(latest, lst.get(succ));
                }
                lst.put(task, latest - durations.getOrDefault(task, 0.0));
            }
        }

        // Total float and critical path
        Map<String, Double> totalFloat = new LinkedHashMap<>();
        List<String> criticalPath = new ArrayList<>();
        for (String task : topoOrder) {
            double f = lst.get(task) - est.get(task);
            totalFloat.put(task, f);
            if (Math.abs(f) < EPSILON) {
                criticalPath.add(task);
            }
        }

        return new ScheduleResult(est, lst, totalFloat, criticalPath, makespan);
    }

    /**
     * Kahn's topological sort on the predecessor DAG.
     */
    private List<String> topologicalSort() {
        Set<String> allTasks = new LinkedHashSet<>(durations.keySet());

        // Compute in-degree
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String task : allTasks) {
            inDegree.put(task, 0);
        }
        for (String task : allTasks) {
            for (String pred : predecessors.getOrDefault(task, List.of())) {
                inDegree.merge(task, 1, Integer::sum);
            }
        }

        // Kahn's algorithm
        Deque<String> queue = new ArrayDeque<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String task = queue.poll();
            sorted.add(task);

            // Find successors: tasks that list this task as a predecessor
            for (String candidate : allTasks) {
                if (predecessors.getOrDefault(candidate, List.of()).contains(task)) {
                    inDegree.merge(candidate, -1, Integer::sum);
                    if (inDegree.get(candidate) == 0) {
                        queue.add(candidate);
                    }
                }
            }
        }
        return sorted;
    }

    /**
     * Schedule computation result.
     *
     * @param earliestStart EST per task (ms from time 0)
     * @param latestStart   LST per task (ms from time 0)
     * @param totalFloat    slack per task (LST - EST)
     * @param criticalPath  tasks on the critical path (float ≈ 0)
     * @param makespanMs    total project duration (ms)
     */
    public record ScheduleResult(
            Map<String, Double> earliestStart,
            Map<String, Double> latestStart,
            Map<String, Double> totalFloat,
            List<String> criticalPath,
            double makespanMs
    ) {}
}
