package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Models a plan DAG as a Petri net and analyses reachability, deadlocks, and liveness.
 *
 * <p>A Petri net N = (P, T, F, M₀) where:
 * <ul>
 *   <li>P = places (task states: PENDING, RUNNING, DONE, FAILED)</li>
 *   <li>T = transitions (task completions / state changes)</li>
 *   <li>F ⊆ (P × T) ∪ (T × P) = flow relation (arcs)</li>
 *   <li>M₀ = initial marking (one token per PENDING task)</li>
 * </ul>
 *
 * <p>Analysis performed:
 * <ol>
 *   <li><b>Reachability</b>: BFS over reachable markings — can the final marking
 *       (all tasks DONE) be reached from M₀?</li>
 *   <li><b>Deadlock detection</b>: a marking is a deadlock if no transition is enabled
 *       (some tasks are RUNNING/PENDING but no dependency path can complete them).</li>
 *   <li><b>Liveness</b>: a transition t is live if from every reachable marking there
 *       exists a firing sequence enabling t again. Approximated here as: t is reachable
 *       from M₀ in at least one path.</li>
 * </ol>
 *
 * <p>The input is a list of directed edges ({@link PlanItemEdge}) representing the DAG
 * of task dependencies. Each edge {@code (from, to)} means task {@code to} depends on
 * task {@code from}.
 *
 * @see <a href="https://doi.org/10.1145/3450856">
 *     van der Aalst (2021), Process Mining: Data Science in Action</a>
 * @see <a href="https://link.springer.com/book/10.1007/978-3-642-80250-8">
 *     Peterson (1977), Petri Net Theory and the Modeling of Systems</a>
 */
@Service
@ConditionalOnProperty(prefix = "petri-nets", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PetriNetAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PetriNetAnalyzer.class);

    /** Maximum reachable markings to explore before bounding the BFS. */
    static final int MAX_MARKINGS = 2000;

    /**
     * Analyses the plan DAG as a Petri net.
     *
     * @param edges directed edges (from taskKey → to taskKey), where "to" depends on "from"
     * @return analysis report, or {@code null} if the edge list is empty
     */
    public PetriNetReport analyze(List<PlanItemEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            log.debug("PetriNetAnalyzer: empty edge list, skipping");
            return null;
        }

        // Collect all task nodes
        Set<String> places = new LinkedHashSet<>();
        Map<String, Set<String>> successors   = new HashMap<>();   // task → tasks that depend on it
        Map<String, Set<String>> predecessors = new HashMap<>();   // task → tasks it depends on

        for (PlanItemEdge e : edges) {
            places.add(e.from());
            places.add(e.to());
            successors  .computeIfAbsent(e.from(), k -> new HashSet<>()).add(e.to());
            predecessors.computeIfAbsent(e.to(),   k -> new HashSet<>()).add(e.from());
        }

        // Initial marking: all tasks are PENDING (token in PENDING place)
        // A task transition fires when all predecessor tasks are DONE
        // Final marking: all tasks DONE

        // Compact representation: marking = bitmask of DONE tasks
        List<String> taskList = new ArrayList<>(places);
        int n = taskList.size();
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(taskList.get(i), i);

        // Precompute prerequisite bitmask for each task
        int[] prereqMask = new int[n];
        for (int i = 0; i < n; i++) {
            String task = taskList.get(i);
            Set<String> preds = predecessors.getOrDefault(task, Set.of());
            for (String pred : preds) {
                prereqMask[i] |= (1 << idx.get(pred));
            }
        }

        int finalMarking = (1 << n) - 1;
        int initialMarking = 0;

        // BFS over reachable markings
        Set<Integer> visited = new HashSet<>();
        Queue<Integer> queue = new ArrayDeque<>();
        Set<Integer> deadlocks = new HashSet<>();
        Set<Integer> enabledTransitions = new HashSet<>();  // task indices ever fired

        queue.add(initialMarking);
        visited.add(initialMarking);

        boolean reachable = (n == 0);

        while (!queue.isEmpty() && visited.size() <= MAX_MARKINGS) {
            int marking = queue.poll();

            if (marking == finalMarking) {
                reachable = true;
                continue;
            }

            // Find enabled transitions: tasks not yet done whose all predecessors are done
            boolean anyEnabled = false;
            for (int i = 0; i < n; i++) {
                boolean done = (marking & (1 << i)) != 0;
                if (done) continue;
                // Task i is enabled if all its prerequisites are done
                if ((marking & prereqMask[i]) == prereqMask[i]) {
                    anyEnabled = true;
                    enabledTransitions.add(i);
                    int next = marking | (1 << i);
                    if (!visited.contains(next)) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }

            if (!anyEnabled) {
                // Deadlock: tasks remain but none can fire
                deadlocks.add(marking);
            }
        }

        // Identify deadlocked tasks (tasks in a deadlock marking that are not done)
        List<String> deadlockedTasks = new ArrayList<>();
        for (int deadlockMarking : deadlocks) {
            for (int i = 0; i < n; i++) {
                boolean done = (deadlockMarking & (1 << i)) != 0;
                if (!done) {
                    String task = taskList.get(i);
                    if (!deadlockedTasks.contains(task)) {
                        deadlockedTasks.add(task);
                    }
                }
            }
        }

        // Live transitions: tasks that were ever enabled (reachable from M₀)
        List<String> liveTransitions = new ArrayList<>();
        for (int i : enabledTransitions) {
            liveTransitions.add(taskList.get(i));
        }

        log.debug("PetriNetAnalyzer: {} places, {} edges, reachable={}, deadlocks={}, live={}",
                n, edges.size(), reachable, deadlocks.size(), liveTransitions.size());

        return new PetriNetReport(
                n,
                edges.size(),
                reachable,
                !deadlocks.isEmpty(),
                Collections.unmodifiableList(deadlockedTasks),
                Collections.unmodifiableList(liveTransitions)
        );
    }

    /**
     * A directed dependency edge between two plan items.
     *
     * @param from the task that must complete first
     * @param to   the task that depends on {@code from}
     */
    public record PlanItemEdge(String from, String to) {}

    /**
     * Petri net analysis report for a plan DAG.
     *
     * @param numPlaces        number of task nodes (places in the net)
     * @param numTransitions   number of dependency arcs (transitions)
     * @param reachable        whether the final marking (all tasks DONE) is reachable from M₀
     * @param deadlockDetected whether at least one deadlock marking was found
     * @param deadlockedTasks  tasks that are stuck in at least one deadlock marking
     * @param liveTransitions  tasks that can fire (enabled) from at least one reachable marking
     */
    public record PetriNetReport(
            int numPlaces,
            int numTransitions,
            boolean reachable,
            boolean deadlockDetected,
            List<String> deadlockedTasks,
            List<String> liveTransitions
    ) {}
}
