package com.agentframework.orchestrator.metrics;

import com.agentframework.orchestrator.sse.SseEmitterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central Micrometer metrics facade for the orchestrator.
 *
 * <p>All meters are pre-registered in {@link #init()} so that Prometheus sees them
 * immediately at the first scrape, even before the first event fires.
 * Per-workerType counters and timers are created on demand via
 * {@link ConcurrentHashMap#computeIfAbsent} — Micrometer deduplicates registrations
 * by name+tags, so concurrent first-calls are safe.</p>
 *
 * <h3>Exported metrics</h3>
 * <ul>
 *   <li>{@code orchestrator_plans_created_total} — plans created</li>
 *   <li>{@code orchestrator_plans_completed_total{status=COMPLETED|FAILED}} — plans terminated</li>
 *   <li>{@code orchestrator_items_dispatched_total{worker_type=…}} — items sent to workers</li>
 *   <li>{@code orchestrator_items_completed_duration_seconds{worker_type=…,status=DONE|FAILED}} — item latency</li>
 *   <li>{@code orchestrator_context_cache_hits_total} — context-cache Redis hits</li>
 *   <li>{@code orchestrator_context_cache_misses_total} — context-cache Redis misses</li>
 *   <li>{@code orchestrator_sse_active_connections} — live SSE subscriber count (gauge)</li>
 * </ul>
 */
@Component
public class OrchestratorMetrics {

    private final MeterRegistry registry;
    private final SseEmitterRegistry sseEmitterRegistry;

    private Counter plansCreated;
    private Counter plansCompleted;
    private Counter plansFailed;
    private Counter cacheHits;
    private Counter cacheMisses;

    /** Per-workerType dispatch counters — created on first call. */
    private final ConcurrentHashMap<String, Counter> itemsDispatched = new ConcurrentHashMap<>();

    /** Per-(workerType,status) completion timers — created on first call. */
    private final ConcurrentHashMap<String, Timer> itemDurations = new ConcurrentHashMap<>();

    public OrchestratorMetrics(MeterRegistry registry, SseEmitterRegistry sseEmitterRegistry) {
        this.registry = registry;
        this.sseEmitterRegistry = sseEmitterRegistry;
    }

    @PostConstruct
    public void init() {
        plansCreated = Counter.builder("orchestrator.plans.created")
                .description("Total plans created and started")
                .register(registry);

        plansCompleted = Counter.builder("orchestrator.plans.completed")
                .tag("status", "COMPLETED")
                .description("Plans that completed successfully")
                .register(registry);

        plansFailed = Counter.builder("orchestrator.plans.completed")
                .tag("status", "FAILED")
                .description("Plans that terminated with failures")
                .register(registry);

        cacheHits = Counter.builder("orchestrator.context.cache.hits")
                .description("Context-cache Redis hits (CONTEXT_MANAGER result reused)")
                .register(registry);

        cacheMisses = Counter.builder("orchestrator.context.cache.misses")
                .description("Context-cache Redis misses (fresh CONTEXT_MANAGER execution required)")
                .register(registry);

        Gauge.builder("orchestrator.sse.active.connections",
                      sseEmitterRegistry,
                      sse -> (double) sse.getActiveConnectionCount())
                .description("Number of currently active SSE subscriber connections")
                .register(registry);
    }

    // ─── Plan-level ───────────────────────────────────────────────────────────

    public void recordPlanCreated() {
        plansCreated.increment();
    }

    public void recordPlanCompleted() {
        plansCompleted.increment();
    }

    public void recordPlanFailed() {
        plansFailed.increment();
    }

    // ─── Item-level ───────────────────────────────────────────────────────────

    public void recordItemDispatched(String workerType) {
        itemsDispatched.computeIfAbsent(workerType, wt ->
                Counter.builder("orchestrator.items.dispatched")
                        .tag("worker_type", wt)
                        .description("Items dispatched to workers, by worker type")
                        .register(registry)
        ).increment();
    }

    public void recordItemCompleted(String workerType, String status, long durationMs) {
        String key = workerType + ":" + status;
        itemDurations.computeIfAbsent(key, k ->
                Timer.builder("orchestrator.items.completed.duration")
                        .tag("worker_type", workerType)
                        .tag("status", status)
                        .description("Item execution wall-clock duration (from dispatch to result receipt)")
                        .register(registry)
        ).record(Duration.ofMillis(durationMs > 0 ? durationMs : 0));
    }

    // ─── Cache ────────────────────────────────────────────────────────────────

    public void recordCacheHit() {
        cacheHits.increment();
    }

    public void recordCacheMiss() {
        cacheMisses.increment();
    }

    // ─── Token Ledger (#33) ──────────────────────────────────────────────────

    /** Per-workerType debit counters — tokens consumed. */
    private final ConcurrentHashMap<String, Counter> ledgerDebits = new ConcurrentHashMap<>();

    /** Per-(workerType:creditType) credit counters — tokens earned. */
    private final ConcurrentHashMap<String, Counter> ledgerCredits = new ConcurrentHashMap<>();

    public void recordLedgerDebit(String workerType, long amount) {
        ledgerDebits.computeIfAbsent(workerType, wt ->
                Counter.builder("orchestrator.ledger.debit")
                        .tag("worker_type", wt)
                        .description("Tokens consumed (debited) by worker type")
                        .register(registry)
        ).increment(amount);
    }

    public void recordLedgerCredit(String workerType, long amount, String creditType) {
        String key = workerType + ":" + creditType;
        ledgerCredits.computeIfAbsent(key, k ->
                Counter.builder("orchestrator.ledger.credit")
                        .tag("worker_type", workerType)
                        .tag("credit_type", creditType)
                        .description("Tokens earned (credited) by worker type and credit source")
                        .register(registry)
        ).increment(amount);
    }

    // ─── Criticality (#56) ──────────────────────────────────────────────────

    /** Backing store for the criticality index gauge — updated each evaluation cycle. */
    private final AtomicReference<Double> criticalityIndexValue = new AtomicReference<>(0.0);

    /** Per-workerType load gauges — updated each evaluation cycle. */
    private final ConcurrentHashMap<String, AtomicReference<Double>> criticalityLoads = new ConcurrentHashMap<>();

    /** Per-workerType topple counters. */
    private final ConcurrentHashMap<String, Counter> criticalityTopples = new ConcurrentHashMap<>();

    /** Flag to register the global criticality index gauge only once. */
    private volatile boolean criticalityGaugeRegistered = false;

    public void recordCriticalityIndex(double index) {
        criticalityIndexValue.set(index);
        if (!criticalityGaugeRegistered) {
            Gauge.builder("orchestrator.criticality.index", criticalityIndexValue, AtomicReference::get)
                    .description("Overall system criticality index C = max(load/threshold)")
                    .register(registry);
            criticalityGaugeRegistered = true;
        }
    }

    public void recordWorkerLoad(String workerType, double load) {
        criticalityLoads.computeIfAbsent(workerType, wt -> {
            AtomicReference<Double> ref = new AtomicReference<>(0.0);
            Gauge.builder("orchestrator.criticality.load", ref, AtomicReference::get)
                    .tag("worker_type", wt)
                    .description("Per-WorkerType load in the sandpile model")
                    .register(registry);
            return ref;
        }).set(load);
    }

    public void recordToppleEvent(String workerType) {
        criticalityTopples.computeIfAbsent(workerType, wt ->
                Counter.builder("orchestrator.criticality.topples")
                        .tag("worker_type", wt)
                        .description("Topple events per WorkerType in the sandpile model")
                        .register(registry)
        ).increment();
    }
}
