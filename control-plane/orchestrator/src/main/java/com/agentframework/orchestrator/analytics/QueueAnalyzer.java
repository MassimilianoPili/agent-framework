package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.graph.CriticalPathCalculator;
import com.agentframework.orchestrator.graph.TropicalScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Per-plan queue analysis combining Erlang C, Little's Law, and Critical Path Method (#36).
 *
 * <p>Given a {@link Plan}, produces a {@link QueueAnalysisResult} that answers:
 * "how many workers of each type are needed to complete this plan without excessive queuing?"</p>
 *
 * <h3>Three complementary models:</h3>
 * <ul>
 *   <li><b>Critical Path Method</b> (delegated to {@link CriticalPathCalculator}):
 *       makespan and critical path from the DAG — the theoretical minimum completion time
 *       assuming unlimited parallelism within dependency constraints.</li>
 *   <li><b>Erlang C</b> (M/M/c queue): for each worker type, computes the probability
 *       that a task must wait given {@code c} consumers and traffic intensity {@code a = λ/μ}.
 *       Uses Jagerman's recursion for numerical stability (avoids factorial overflow).</li>
 *   <li><b>Little's Law</b> ({@code L = λ × W_q}): translates wait time into mean
 *       queue depth — how many tasks are waiting on average per worker type.</li>
 * </ul>
 *
 * <p>Historical service times are sourced from {@link QueuingCapacityPlanner} (M/G/1 analysis
 * on {@code task_outcomes}). When insufficient data exists, a configurable default is used.</p>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Erlang_C_formula">Erlang C formula</a>
 * @see <a href="https://en.wikipedia.org/wiki/Little%27s_law">Little's Law</a>
 */
@Service
public class QueueAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(QueueAnalyzer.class);

    /** Default service time when no historical data exists: 5 minutes. */
    static final double DEFAULT_SERVICE_TIME_MS = 300_000.0;

    /** Erlang C threshold: recommend consumers until P(wait) drops below this. */
    static final double ERLANG_C_THRESHOLD = 0.30;

    /** Safety bound on recommended consumers per worker type. */
    static final int MAX_RECOMMENDED_CONSUMERS = 64;

    private final QueuingCapacityPlanner queuingCapacityPlanner;
    private final CriticalPathCalculator criticalPathCalculator;

    public QueueAnalyzer(QueuingCapacityPlanner queuingCapacityPlanner,
                         CriticalPathCalculator criticalPathCalculator) {
        this.queuingCapacityPlanner = queuingCapacityPlanner;
        this.criticalPathCalculator = criticalPathCalculator;
    }

    /**
     * Analyses a plan: CPM for makespan, then Erlang C + Little's Law per worker type.
     *
     * @param plan the plan to analyse
     * @return complete queue analysis, never null
     */
    public QueueAnalysisResult analyze(Plan plan) {
        if (plan.getItems() == null || plan.getItems().isEmpty()) {
            return QueueAnalysisResult.empty(plan.getId());
        }

        // ── CPM: makespan and critical path ─────────────────────────────────
        TropicalScheduler.ScheduleResult schedule = criticalPathCalculator.computeSchedule(plan);
        double makespanMs = schedule.makespanMs();
        List<String> criticalPath = schedule.criticalPath();

        // ── Count tasks per worker type ─────────────────────────────────────
        Map<String, List<PlanItem>> byType = new LinkedHashMap<>();
        for (PlanItem item : plan.getItems()) {
            byType.computeIfAbsent(item.getWorkerType().name(), k -> new ArrayList<>()).add(item);
        }

        // ── Per-type Erlang C analysis ──────────────────────────────────────
        Map<String, WorkerTypeAnalysis> analyses = new LinkedHashMap<>();
        String bottleneck = null;
        double maxWait = -1;

        for (var entry : byType.entrySet()) {
            String workerType = entry.getKey();
            int taskCount = entry.getValue().size();

            double meanServiceTimeMs = resolveServiceTimeMs(workerType);

            // λ = taskCount / makespanMs  (arrival rate per ms for this type in this plan)
            double arrivalRatePerMs = makespanMs > 0 ? taskCount / makespanMs : 0;

            // Traffic intensity a = λ × E[S]  (offered load in Erlangs)
            double trafficIntensity = arrivalRatePerMs * meanServiceTimeMs;

            // Single consumer analysis
            int currentConsumers = 1;
            double pWait = erlangC(currentConsumers, trafficIntensity);
            double rho = currentConsumers > 0 ? trafficIntensity / currentConsumers : 1.0;

            // W_q (Erlang C mean wait): P(wait) × E[S] / (c × (1 - ρ))
            double waitMs = (rho < 1.0 && currentConsumers > 0)
                    ? pWait * meanServiceTimeMs / (currentConsumers * (1.0 - rho))
                    : Double.POSITIVE_INFINITY;

            // Little's Law: L = λ × W_q
            double littleL = arrivalRatePerMs * waitMs;

            // Recommend consumers
            int recommended = recommendConsumers(trafficIntensity, meanServiceTimeMs);

            boolean saturated = rho >= 0.90;

            analyses.put(workerType, new WorkerTypeAnalysis(
                    workerType, taskCount, meanServiceTimeMs,
                    arrivalRatePerMs, littleL, pWait,
                    currentConsumers, recommended, waitMs, saturated
            ));

            if (waitMs > maxWait) {
                maxWait = waitMs;
                bottleneck = workerType;
            }
        }

        // Estimated completion: makespan + max queuing delay across types
        double estimatedCompletionMs = makespanMs + (Double.isInfinite(maxWait) ? makespanMs : maxWait);

        log.debug("QueueAnalysis: plan={} makespan={}ms bottleneck={} types={}",
                plan.getId(), makespanMs, bottleneck, analyses.size());

        return new QueueAnalysisResult(
                plan.getId(), makespanMs, criticalPath,
                analyses, bottleneck, estimatedCompletionMs
        );
    }

    // ── Erlang C ────────────────────────────────────────────────────────────

    /**
     * Erlang C formula: probability that an arriving task must wait in an M/M/c queue.
     *
     * <p>Uses Jagerman's recursion for Erlang B (numerically stable, no factorials):
     * {@code B(0,a) = 1;  B(k,a) = a·B(k−1,a) / (k + a·B(k−1,a))}
     * then {@code C(c,a) = B(c,a) / (1 − ρ·(1 − B(c,a)))} where {@code ρ = a/c}.</p>
     *
     * @param c number of servers (consumers)
     * @param a traffic intensity (offered load in Erlangs: λ/μ = λ × E[S])
     * @return P(wait) in [0, 1]; returns 1.0 if the system is unstable (ρ ≥ 1)
     */
    static double erlangC(int c, double a) {
        if (c <= 0 || a < 0) return 1.0;
        if (a == 0) return 0.0;

        double rho = a / c;
        if (rho >= 1.0) return 1.0; // unstable: queue grows without bound

        // Jagerman's Erlang B recursion
        double B = 1.0;
        for (int k = 1; k <= c; k++) {
            B = a * B / (k + a * B);
        }

        // Erlang C from Erlang B
        return B / (1.0 - rho * (1.0 - B));
    }

    /**
     * Mean wait time (W_q) for an M/M/c queue via Erlang C.
     *
     * @param c number of servers
     * @param a traffic intensity (Erlangs)
     * @param meanServiceTimeMs mean service time in ms
     * @return W_q in ms; POSITIVE_INFINITY if unstable
     */
    static double erlangCWaitTime(int c, double a, double meanServiceTimeMs) {
        double rho = c > 0 ? a / c : 1.0;
        if (rho >= 1.0) return Double.POSITIVE_INFINITY;
        double pWait = erlangC(c, a);
        return pWait * meanServiceTimeMs / (c * (1.0 - rho));
    }

    // ── Little's Law ────────────────────────────────────────────────────────

    /**
     * Little's Law: {@code L = λ × W}.
     *
     * @param arrivalRate λ (tasks per time unit)
     * @param waitTime    W (average time in queue, same time unit)
     * @return L (average number of tasks in queue)
     */
    static double littleLaw(double arrivalRate, double waitTime) {
        if (arrivalRate <= 0 || Double.isInfinite(waitTime) || Double.isNaN(waitTime)) return 0;
        return arrivalRate * waitTime;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Resolves mean service time (ms) for a worker type using historical data.
     * Falls back to {@link #DEFAULT_SERVICE_TIME_MS} when insufficient data.
     */
    private double resolveServiceTimeMs(String workerType) {
        try {
            QueuingCapacityPlanner.QueuingReport report = queuingCapacityPlanner.analyze(workerType);
            if (report != null && report.meanServiceTime() > 0) {
                return report.meanServiceTime() * 1000.0; // seconds → ms
            }
        } catch (Exception e) {
            log.debug("Failed to resolve service time for {}: {}", workerType, e.getMessage());
        }
        return DEFAULT_SERVICE_TIME_MS;
    }

    /**
     * Finds the minimum consumer count c such that Erlang C P(wait) < threshold.
     */
    private int recommendConsumers(double trafficIntensity, double meanServiceTimeMs) {
        for (int c = 1; c <= MAX_RECOMMENDED_CONSUMERS; c++) {
            double pWait = erlangC(c, trafficIntensity);
            if (pWait < ERLANG_C_THRESHOLD) return c;
        }
        return MAX_RECOMMENDED_CONSUMERS;
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * Complete queue analysis result for a plan.
     *
     * @param planId                 the analysed plan
     * @param makespanMs             CPM makespan (theoretical minimum, ms)
     * @param criticalPath           task keys on the critical path
     * @param byWorkerType           per-type Erlang C analysis
     * @param bottleneckWorkerType   worker type with highest estimated wait (null if empty)
     * @param estimatedCompletionMs  makespan + max queuing delay
     */
    public record QueueAnalysisResult(
            UUID planId,
            double makespanMs,
            List<String> criticalPath,
            Map<String, WorkerTypeAnalysis> byWorkerType,
            String bottleneckWorkerType,
            double estimatedCompletionMs
    ) {
        static QueueAnalysisResult empty(UUID planId) {
            return new QueueAnalysisResult(planId, 0, List.of(), Map.of(), null, 0);
        }
    }

    /**
     * Per-worker-type queue analysis.
     *
     * @param workerType           worker type name
     * @param taskCount            number of tasks of this type in the plan
     * @param meanServiceTimeMs    E[S] estimated mean service time (ms)
     * @param arrivalRatePerMs     λ estimated arrival rate (tasks/ms)
     * @param littleLInQueue       L = λ × W_q (mean tasks in queue)
     * @param erlangCProbWait      P(wait) with current consumers
     * @param currentConsumers     number of consumers currently assumed
     * @param recommendedConsumers min consumers for P(wait) < threshold
     * @param estimatedWaitMs      W_q estimated mean wait time (ms)
     * @param saturated            true when utilisation ρ ≥ 0.90
     */
    public record WorkerTypeAnalysis(
            String workerType,
            int taskCount,
            double meanServiceTimeMs,
            double arrivalRatePerMs,
            double littleLInQueue,
            double erlangCProbWait,
            int currentConsumers,
            int recommendedConsumers,
            double estimatedWaitMs,
            boolean saturated
    ) {}
}
