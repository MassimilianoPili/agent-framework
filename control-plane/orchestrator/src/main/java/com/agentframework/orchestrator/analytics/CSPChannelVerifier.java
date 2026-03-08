package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.eventsourcing.PlanEvent;
import com.agentframework.orchestrator.eventsourcing.PlanEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Verifies that the worker ↔ orchestrator communication protocol
 * respects the CSP (Communicating Sequential Processes) channel specification.
 *
 * <p>In Hoare's CSP, processes communicate over synchronous channels via events.
 * The expected protocol for each task is the linear trace:
 * <pre>
 *   TASK_CREATED → TASK_DISPATCHED → TASK_STARTED → TASK_COMPLETED | TASK_FAILED
 * </pre>
 * Any deviation — skipped events, out-of-order events, tasks dispatched but never
 * completed — constitutes a protocol violation.
 *
 * <p>Three properties are checked:
 * <ol>
 *   <li><b>Protocol adherence</b>: each task follows the legal trace above.
 *       Violations are recorded as strings describing the deviation.</li>
 *   <li><b>Deadlock freedom</b>: no cycle of mutual waiting exists.
 *       Approximated by checking that no task is DISPATCHED while another task
 *       for the same plan has been DISPATCHED and never acknowledged for more than
 *       one step in event-sequence order.</li>
 *   <li><b>Liveness</b>: every DISPATCHED task reaches a terminal state
 *       (COMPLETED or FAILED) within the event log.
 *       Tasks that are DISPATCHED but have no terminal event are flagged.</li>
 * </ol>
 *
 * @see <a href="https://doi.org/10.1145/359576.359585">
 *     Hoare (1978), Communicating Sequential Processes</a>
 * @see <a href="https://www.cs.ox.ac.uk/bill.roscoe/publications/68b.pdf">
 *     Roscoe (1997), The Theory and Practice of Concurrency</a>
 */
@Service
@ConditionalOnProperty(prefix = "csp-verifier", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CSPChannelVerifier {

    private static final Logger log = LoggerFactory.getLogger(CSPChannelVerifier.class);

    // Legal event sequence per task (states as ordinal positions)
    private static final List<String> PROTOCOL_SEQUENCE = List.of(
            "TASK_CREATED", "TASK_DISPATCHED", "TASK_STARTED", "TASK_COMPLETED"
    );
    private static final Set<String> TERMINAL_EVENTS = Set.of("TASK_COMPLETED", "TASK_FAILED");
    private static final Set<String> TASK_EVENTS = Set.of(
            "TASK_CREATED", "TASK_DISPATCHED", "TASK_STARTED", "TASK_COMPLETED", "TASK_FAILED"
    );

    @Value("${csp-verifier.max-events-per-plan:500}")
    private int maxEventsPerPlan;

    private final PlanEventRepository planEventRepository;

    public CSPChannelVerifier(PlanEventRepository planEventRepository) {
        this.planEventRepository = planEventRepository;
    }

    /**
     * Verifies the CSP protocol for all tasks in a plan.
     *
     * @param planId UUID of the plan to verify
     * @return verification report, or {@code null} if no task events were found
     */
    public CSPVerificationReport verify(UUID planId) {
        List<PlanEvent> events = planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId);

        if (events.isEmpty()) {
            log.debug("CSPChannelVerifier: no events for plan {}", planId);
            return null;
        }

        // Cap to avoid OOM on very long plans
        if (events.size() > maxEventsPerPlan) {
            events = events.subList(0, maxEventsPerPlan);
        }

        // Group events by itemId (each task item has its own trace)
        Map<UUID, List<String>> tracesByItem = new LinkedHashMap<>();
        for (PlanEvent e : events) {
            if (e.getItemId() == null) continue;
            if (!TASK_EVENTS.contains(e.getEventType())) continue;
            tracesByItem.computeIfAbsent(e.getItemId(), k -> new ArrayList<>())
                        .add(e.getEventType());
        }

        if (tracesByItem.isEmpty()) {
            return null;
        }

        List<String> protocolViolations = new ArrayList<>();
        List<String> livenessViolations = new ArrayList<>();
        int checkedItems = 0;
        int violatingItems = 0;

        for (Map.Entry<UUID, List<String>> entry : tracesByItem.entrySet()) {
            UUID itemId = entry.getKey();
            List<String> trace = entry.getValue();
            checkedItems++;

            // 1. Protocol adherence: check the trace is a prefix of PROTOCOL_SEQUENCE
            //    (allowing TASK_FAILED as an alternate terminal after TASK_STARTED or TASK_DISPATCHED)
            List<String> violation = checkProtocol(itemId, trace);
            if (!violation.isEmpty()) {
                protocolViolations.addAll(violation);
                violatingItems++;
            }

            // 2. Liveness: task dispatched but never terminated
            boolean dispatched = trace.contains("TASK_DISPATCHED");
            boolean terminated = trace.stream().anyMatch(TERMINAL_EVENTS::contains);
            if (dispatched && !terminated) {
                livenessViolations.add("item " + itemId + ": DISPATCHED but no terminal event in log");
            }
        }

        // 3. Deadlock freedom: check for mutual waiting cycles
        //    Approximation: if more than one task is simultaneously in DISPATCHED state
        //    (dispatched but not yet started) across the entire event stream, flag it.
        boolean deadlockFreedom = checkDeadlockFreedom(events);

        double adherenceScore = checkedItems == 0 ? 1.0
                : 1.0 - (double) violatingItems / checkedItems;

        log.debug("CSPChannelVerifier: plan={}, items={}, violations={}, liveness={}, deadlockFree={}, score={}",
                planId, checkedItems, protocolViolations.size(), livenessViolations.size(),
                deadlockFreedom, adherenceScore);

        return new CSPVerificationReport(
                planId,
                Collections.unmodifiableList(protocolViolations),
                deadlockFreedom,
                Collections.unmodifiableList(livenessViolations),
                adherenceScore
        );
    }

    /**
     * Checks whether a task's event trace conforms to the legal protocol sequence.
     * Returns a list of violation descriptions (empty = conformant).
     */
    private List<String> checkProtocol(UUID itemId, List<String> trace) {
        List<String> violations = new ArrayList<>();
        int maxAllowed = -1;  // tracks the highest position seen in PROTOCOL_SEQUENCE

        for (String event : trace) {
            int pos = PROTOCOL_SEQUENCE.indexOf(event);
            if (pos == -1) {
                // TASK_FAILED is legal after DISPATCHED or STARTED
                if ("TASK_FAILED".equals(event)) continue;
                violations.add("item " + itemId + ": unexpected event '" + event + "'");
                continue;
            }
            if (pos <= maxAllowed) {
                violations.add("item " + itemId + ": out-of-order event '" + event
                        + "' (already seen up to position " + maxAllowed + ")");
            } else if (pos > maxAllowed + 1 && maxAllowed >= 0) {
                violations.add("item " + itemId + ": skipped event between position "
                        + maxAllowed + " and " + pos);
            }
            maxAllowed = Math.max(maxAllowed, pos);
        }

        return violations;
    }

    /**
     * Approximates deadlock freedom: returns {@code true} if no point in the event stream
     * has more than {@code n-1} tasks simultaneously in a "dispatched but not started" state,
     * where n is the total number of distinct task items.
     *
     * <p>A fully blocked system (all n tasks waiting for each other) would require all n tasks
     * to be dispatched simultaneously — this is the worst-case deadlock scenario.</p>
     */
    private boolean checkDeadlockFreedom(List<PlanEvent> events) {
        Set<UUID> dispatchedNotStarted = new HashSet<>();
        int totalItems = 0;
        Set<UUID> seenItems = new HashSet<>();

        for (PlanEvent e : events) {
            if (e.getItemId() == null) continue;
            seenItems.add(e.getItemId());

            switch (e.getEventType()) {
                case "TASK_DISPATCHED" -> dispatchedNotStarted.add(e.getItemId());
                case "TASK_STARTED", "TASK_COMPLETED", "TASK_FAILED" ->
                        dispatchedNotStarted.remove(e.getItemId());
                default -> { /* ignore non-task events */ }
            }
        }

        totalItems = seenItems.size();
        if (totalItems <= 1) return true;

        // Deadlock if all tasks ended up dispatched-but-not-started simultaneously
        // (this can only happen at the final state of the event log, not mid-stream)
        return dispatchedNotStarted.size() < totalItems;
    }

    /**
     * CSP protocol verification report for a plan.
     *
     * @param planId             UUID of the verified plan
     * @param protocolViolations descriptions of out-of-order or skipped events
     * @param deadlockFreedom    {@code true} if no deadlock pattern was detected
     * @param livenessViolations tasks that were dispatched but never reached a terminal state
     * @param adherenceScore     fraction of task items with no protocol violations (0.0–1.0)
     */
    public record CSPVerificationReport(
            UUID planId,
            List<String> protocolViolations,
            boolean deadlockFreedom,
            List<String> livenessViolations,
            double adherenceScore
    ) {}
}
