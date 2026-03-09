package com.agentframework.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Self-Organized Criticality monitor (#56).
 *
 * <p>Controls the Bak-Tang-Wiesenfeld sandpile model parameters used to detect
 * failure cascades and system-level overload. The monitor runs on a scheduled
 * interval, computes per-WorkerType load, and triggers alerts when the
 * criticality index exceeds configured thresholds.</p>
 *
 * <pre>
 * criticality:
 *   enabled: true
 *   interval-ms: 30000
 *   target-inventory: 5
 *   spillover-ratio: 0.3
 *   max-topple-iterations: 50
 *   threshold-stable: 0.5
 *   threshold-warning: 0.8
 * </pre>
 *
 * @see com.agentframework.orchestrator.orchestration.CriticalityMonitor
 * @see com.agentframework.orchestrator.orchestration.SandpileSimulator
 */
@ConfigurationProperties(prefix = "criticality")
public record CriticalityProperties(

    /** Master switch. When false, the scheduled evaluation is a no-op. */
    boolean enabled,

    /** Scheduled evaluation interval in milliseconds (default 30000 = 30s). */
    long intervalMs,

    /** Target inventory per WorkerType. Threshold = targetInventory × 3. */
    int targetInventory,

    /**
     * Fraction of threshold spilled to each neighbour during a topple event.
     * Higher values increase cascade propagation speed.
     */
    double spilloverRatio,

    /** Maximum topple iterations before halting (prevents infinite loops in cyclic graphs). */
    int maxToppleIterations,

    /** Criticality index below this value → STABLE (debug log only). */
    double thresholdStable,

    /** Criticality index at or above this value → ALERT (publishes SYSTEM_CRITICALITY event). */
    double thresholdWarning

) {
    /** Compact constructor with defaults for unset fields. */
    public CriticalityProperties {
        if (intervalMs <= 0) intervalMs = 30_000L;
        if (targetInventory <= 0) targetInventory = 5;
        if (spilloverRatio <= 0) spilloverRatio = 0.3;
        if (maxToppleIterations <= 0) maxToppleIterations = 50;
        if (thresholdStable <= 0) thresholdStable = 0.5;
        if (thresholdWarning <= 0) thresholdWarning = 0.8;
    }
}
