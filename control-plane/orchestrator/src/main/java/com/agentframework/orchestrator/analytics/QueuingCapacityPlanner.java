package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyses Redis Stream throughput using the M/G/1 queuing model and produces
 * concrete capacity recommendations.
 *
 * <p><b>M/G/1 model.</b>
 * The Redis Stream consumer group for a given {@code workerType} is modelled as:
 * <ul>
 *   <li>M — Poisson task arrivals with rate λ (tasks/second)</li>
 *   <li>G — General service time distribution with mean E[S] and variance Var[S]</li>
 *   <li>1 — Single logical server (the consumer group acts collectively, but
 *            task ordering and back-pressure mimic a single queue)</li>
 * </ul>
 *
 * <p><b>Key formulas.</b>
 * Pollaczek–Khinchine (P-K) mean-value formula:
 * <pre>
 *   ρ   = λ · E[S]                               (utilisation)
 *   C_S = √(Var[S]) / E[S]                       (service time CV)
 *   W_q = ρ · E[S] · (1 + C_S²) / (2 · (1 − ρ))  (mean queue wait)
 *   W   = W_q + E[S]                              (mean sojourn time)
 * </pre>
 * When C_S = 1 (exponential service times), M/G/1 reduces to M/M/1.
 * Higher C_S → longer waits even at the same utilisation — this is the
 * "variability penalty" of the M/G/1 model.
 *
 * <p><b>Input data.</b>
 * Completion timestamps from {@code task_outcomes.created_at} are used to
 * estimate inter-completion intervals. In a single-server system at high
 * utilisation, inter-completion intervals approximate service times.
 *
 * <p><b>Capacity recommendation.</b>
 * The recommended consumer count k satisfies:
 * {@code W_q(k) < targetWaitSeconds} using the M/M/k Erlang-C approximation
 * scaled from the M/G/1 baseline:
 * {@code W_q(k) ≈ W_q_mm1(k) · (1 + C_S²) / 2}.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Pollaczek%E2%80%93Khinchine_formula">
 *     Pollaczek–Khinchine formula</a>
 */
@Service
@ConditionalOnProperty(prefix = "queuing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QueuingCapacityPlanner {

    private static final Logger log = LoggerFactory.getLogger(QueuingCapacityPlanner.class);

    /** Minimum completion records needed for a reliable estimate. */
    private static final int MIN_SAMPLES = 5;

    /** Maximum recommended consumers (safety bound to prevent unbounded scaling). */
    private static final int MAX_RECOMMENDED = 64;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${queuing.max-samples:1000}")
    private int maxSamples;

    @Value("${queuing.target-wait-seconds:30}")
    private double targetWaitSeconds;

    public QueuingCapacityPlanner(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Runs M/G/1 analysis for the given worker type.
     *
     * @param workerType the worker type identifier
     * @return queuing report, or {@code null} when data is insufficient
     */
    public QueuingReport analyze(String workerType) {
        List<Object[]> rows = taskOutcomeRepository
                .findCompletionTimestampsByWorkerType(workerType, maxSamples);

        if (rows.size() < MIN_SAMPLES) return null;

        // ── Extract inter-completion intervals (seconds) ───────────────────────
        List<Double> intervals = new ArrayList<>(rows.size() - 1);
        for (int i = 1; i < rows.size(); i++) {
            long prevMs = toMillis(rows.get(i - 1)[0]);
            long currMs = toMillis(rows.get(i)[0]);
            double deltaS = (currMs - prevMs) / 1000.0;
            if (deltaS > 0) intervals.add(deltaS);
        }

        if (intervals.size() < MIN_SAMPLES - 1) return null;

        double[] dt = intervals.stream().mapToDouble(Double::doubleValue).toArray();

        // ── M/G/1 parameter estimation ────────────────────────────────────────
        // E[S] ≈ mean inter-completion time (valid when server is continuously busy)
        double meanServiceTime = mean(dt);
        double varServiceTime  = variance(dt, meanServiceTime);
        double cvS             = meanServiceTime > 0
                ? Math.sqrt(varServiceTime) / meanServiceTime : 0.0;

        // λ = (n − 1) / (t_last − t_first)  [total completions / observation window]
        long firstMs = toMillis(rows.get(0)[0]);
        long lastMs  = toMillis(rows.get(rows.size() - 1)[0]);
        double windowSeconds = (lastMs - firstMs) / 1000.0;
        double arrivalRate = windowSeconds > 0
                ? (rows.size() - 1.0) / windowSeconds : 0.0;

        // ρ = λ · E[S]  (utilisation — must be < 1 for stable queue)
        double utilisation = arrivalRate * meanServiceTime;

        // ── Pollaczek–Khinchine mean wait ─────────────────────────────────────
        double meanWaitTime;
        if (utilisation >= 1.0) {
            meanWaitTime = Double.POSITIVE_INFINITY; // queue is unstable
        } else {
            meanWaitTime = utilisation * meanServiceTime * (1.0 + cvS * cvS)
                    / (2.0 * (1.0 - utilisation));
        }
        double meanSojournTime = Double.isInfinite(meanWaitTime)
                ? Double.POSITIVE_INFINITY : meanWaitTime + meanServiceTime;

        // ── Recommend minimum consumer count k to achieve W_q < target ────────
        int recommendedConsumers = recommendConsumers(arrivalRate, meanServiceTime, cvS);

        boolean saturated = utilisation >= 0.90;

        log.debug("Queuing[{}] n={} λ={}/s E[S]={}s ρ={} C_S={} W_q={}s saturated={}",
                workerType, rows.size(), arrivalRate, meanServiceTime,
                utilisation, cvS, meanWaitTime, saturated);

        return new QueuingReport(
                workerType, rows.size(),
                arrivalRate, meanServiceTime, varServiceTime,
                utilisation, cvS, meanWaitTime, meanSojournTime,
                recommendedConsumers, saturated
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private long toMillis(Object ts) {
        if (ts instanceof Timestamp t)     return t.getTime();
        if (ts instanceof java.util.Date d) return d.getTime();
        if (ts instanceof Number n)         return n.longValue();
        return Long.parseLong(ts.toString());
    }

    private double mean(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    private double variance(double[] a, double m) {
        double s = 0;
        for (double v : a) s += (v - m) * (v - m);
        return s / a.length;
    }

    /**
     * Finds the smallest consumer count k such that the scaled P-K wait
     * falls below {@code targetWaitSeconds}.
     *
     * <p>For k consumers, we approximate the M/G/k wait by scaling the M/G/1
     * wait: W_q(k) ≈ W_q_mm1_k · (1 + C_S²) / 2 where
     * W_q_mm1_k = E[S] · ρ_k / (k · (1 − ρ_k)) and ρ_k = λ · E[S] / k.
     */
    private int recommendConsumers(double lambda, double meanS, double cvS) {
        for (int k = 1; k <= MAX_RECOMMENDED; k++) {
            double rhoK = lambda * meanS / k;
            if (rhoK >= 1.0) continue; // unstable with k consumers
            // M/M/k wait approximation
            double wqMmk = meanS * rhoK / (k * (1.0 - rhoK));
            // G correction factor
            double wqMgk = wqMmk * (1.0 + cvS * cvS) / 2.0;
            if (wqMgk <= targetWaitSeconds) return k;
        }
        return MAX_RECOMMENDED;
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────

    /**
     * M/G/1 queuing analysis result.
     *
     * @param workerType           worker type identifier
     * @param sampleCount          number of completion records analysed
     * @param arrivalRate          λ: estimated task arrival rate (tasks/second)
     * @param meanServiceTime      E[S]: mean service time (seconds)
     * @param varServiceTime       Var[S]: service time variance (seconds²)
     * @param utilisation          ρ = λ·E[S]; stable if &lt; 1.0
     * @param serviceTimeCV        C_S = √(Var[S]) / E[S]; 1.0 → exponential, 0 → deterministic
     * @param meanWaitTime         W_q (P-K): mean time waiting in queue (seconds)
     * @param meanSojournTime      W = W_q + E[S]: mean time in system (seconds)
     * @param recommendedConsumers minimum consumers to achieve wait &lt; target
     * @param saturated            true when ρ ≥ 0.90
     */
    public record QueuingReport(
            String workerType,
            int sampleCount,
            double arrivalRate,
            double meanServiceTime,
            double varServiceTime,
            double utilisation,
            double serviceTimeCV,
            double meanWaitTime,
            double meanSojournTime,
            int recommendedConsumers,
            boolean saturated
    ) {}
}
