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
 * Verifies plan execution traces against Linear Temporal Logic (LTL) formulae.
 *
 * <p>Uses <em>finite-trace LTL semantics</em> (LTLf, De Giacomo &amp; Vardi 2013), which is
 * better suited for bounded plan executions than infinite-trace classical LTL.</p>
 *
 * <p>Event alphabet: {@code TASK_DISPATCHED}, {@code TASK_COMPLETED}, {@code TASK_FAILED},
 * {@code CONTEXT_REQUESTED} / {@code MISSING_CONTEXT}.</p>
 *
 * <p>Four built-in formulae:</p>
 * <ul>
 *   <li><b>S1 Safety</b>: □(DISPATCHED(i) → ◇(COMPLETED(i) ∨ FAILED(i)))
 *       — every dispatched item eventually terminates</li>
 *   <li><b>S2 Safety</b>: □¬(COMPLETED before DISPATCHED)
 *       — no out-of-order completions</li>
 *   <li><b>L1 Liveness</b>: ◇ COMPLETED
 *       — the plan produces at least one successful result</li>
 *   <li><b>L2 Liveness</b>: □(count(CONTEXT_REQUESTED, i) ≤ maxContextRequests)
 *       — no runaway context request loops</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "ltl-verifier", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LTLPolicyVerifier {

    private static final Logger log = LoggerFactory.getLogger(LTLPolicyVerifier.class);

    private static final String TASK_DISPATCHED   = "TASK_DISPATCHED";
    private static final String TASK_COMPLETED    = "TASK_COMPLETED";
    private static final String TASK_FAILED       = "TASK_FAILED";
    private static final String CONTEXT_REQUESTED = "CONTEXT_REQUESTED";
    private static final String MISSING_CONTEXT   = "MISSING_CONTEXT";

    private final PlanEventRepository planEventRepository;

    @Value("${ltl-verifier.max-context-requests:3}")
    private int maxContextRequests;

    public LTLPolicyVerifier(PlanEventRepository planEventRepository) {
        this.planEventRepository = planEventRepository;
    }

    /**
     * Verifies the four LTL formulae on the execution trace of the given plan.
     *
     * @param planId the plan to verify
     * @return LTL verification report
     * @throws NullPointerException if planId is null
     */
    public LTLVerificationReport verify(UUID planId) {
        Objects.requireNonNull(planId, "planId must not be null");

        List<PlanEvent> events = planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId);

        // Group events by itemId for per-item temporal analysis
        Map<UUID, List<String>> perItem = new LinkedHashMap<>();
        for (PlanEvent e : events) {
            if (e.getItemId() == null) continue;
            perItem.computeIfAbsent(e.getItemId(), k -> new ArrayList<>())
                   .add(e.getEventType());
        }

        Map<String, Boolean> formulaResults  = new LinkedHashMap<>();
        List<String>         violations      = new ArrayList<>();
        Map<String, String>  counterexamples = new LinkedHashMap<>();

        // ── S1: every dispatched item eventually terminates ────────────────
        boolean s1 = true;
        for (Map.Entry<UUID, List<String>> entry : perItem.entrySet()) {
            List<String> trace = entry.getValue();
            if (trace.contains(TASK_DISPATCHED)) {
                boolean terminated = trace.contains(TASK_COMPLETED) || trace.contains(TASK_FAILED);
                if (!terminated) {
                    s1 = false;
                    counterexamples.put("S1", entry.getKey().toString());
                    violations.add("S1: item " + entry.getKey() + " dispatched but never terminated");
                    break;
                }
            }
        }
        formulaResults.put("S1_safety_dispatch_terminates", s1);

        // ── S2: COMPLETED must appear after DISPATCHED (ordering constraint) ─
        boolean s2 = true;
        for (Map.Entry<UUID, List<String>> entry : perItem.entrySet()) {
            List<String> trace    = entry.getValue();
            int completedIdx  = trace.indexOf(TASK_COMPLETED);
            int dispatchedIdx = trace.indexOf(TASK_DISPATCHED);
            if (completedIdx >= 0 && (dispatchedIdx < 0 || completedIdx < dispatchedIdx)) {
                s2 = false;
                counterexamples.put("S2", entry.getKey().toString());
                violations.add("S2: item " + entry.getKey() + " completed before being dispatched");
                break;
            }
        }
        formulaResults.put("S2_safety_order_preserved", s2);

        // ── L1: plan has at least one successful completion ───────────────
        boolean l1 = events.stream().anyMatch(e -> TASK_COMPLETED.equals(e.getEventType()));
        if (!l1 && !events.isEmpty()) {
            violations.add("L1: no TASK_COMPLETED event in plan " + planId);
            counterexamples.put("L1", planId.toString());
        }
        formulaResults.put("L1_liveness_at_least_one_completed", l1);

        // ── L2: CONTEXT_REQUESTED count per item ≤ maxContextRequests ────
        boolean l2 = true;
        for (Map.Entry<UUID, List<String>> entry : perItem.entrySet()) {
            List<String> trace = entry.getValue();
            long contextCount  = trace.stream()
                    .filter(t -> CONTEXT_REQUESTED.equals(t) || MISSING_CONTEXT.equals(t))
                    .count();
            if (contextCount > maxContextRequests) {
                l2 = false;
                counterexamples.put("L2", entry.getKey().toString());
                violations.add("L2: item " + entry.getKey() + " requested context "
                        + contextCount + " times (max=" + maxContextRequests + ")");
                break;
            }
        }
        formulaResults.put("L2_liveness_context_not_infinite", l2);

        long   satisfied      = formulaResults.values().stream().filter(v -> v).count();
        double overallAdherence = formulaResults.isEmpty() ? 1.0
                : (double) satisfied / formulaResults.size();

        log.debug("LTL verify: planId={} traceLen={} adherence={} violations={}",
                planId, events.size(), overallAdherence, violations.size());

        return new LTLVerificationReport(
                planId, events.size(),
                formulaResults, violations, counterexamples,
                overallAdherence
        );
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * LTL policy verification report.
     *
     * @param planId           the verified plan
     * @param traceLength      total number of events in the trace
     * @param formulaResults   per-formula satisfaction result (true = satisfied)
     * @param violations       human-readable violation descriptions
     * @param counterexamples  map of formula name → violating item/plan ID
     * @param overallAdherence fraction of formulae satisfied ∈ [0, 1]
     */
    public record LTLVerificationReport(
            UUID planId,
            int traceLength,
            Map<String, Boolean> formulaResults,
            List<String> violations,
            Map<String, String> counterexamples,
            double overallAdherence
    ) {}
}
