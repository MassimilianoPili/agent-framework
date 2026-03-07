package com.agentframework.orchestrator.orchestration;

import java.util.Map;

/**
 * Inventory risk model for market-making dispatch prioritization.
 *
 * <p>Adapts the Avellaneda-Stoikov bid-ask spread model: the "spread" for a worker type
 * widens when its DISPATCHED inventory diverges from the target, signaling over-saturation
 * or under-utilization.</p>
 *
 * <p>Priority combines inverse spread (favor under-utilized workers), urgency
 * (how long a task has been waiting), and critical path bonus.</p>
 *
 * @see <a href="https://doi.org/10.1080/14697680802341587">
 *     Avellaneda &amp; Stoikov (2008), High-frequency trading in a limit order book</a>
 */
public class InventoryTracker {

    /** Base spread when inventory exactly matches target. */
    public static final double BASE_SPREAD = 0.1;

    private final Map<String, Integer> currentInventory;
    private final Map<String, Double> targetInventory;

    /**
     * @param currentInventory count of currently DISPATCHED tasks per worker type
     * @param targetInventory  target inventory level per worker type
     */
    public InventoryTracker(Map<String, Integer> currentInventory,
                              Map<String, Double> targetInventory) {
        this.currentInventory = currentInventory;
        this.targetInventory = targetInventory;
    }

    /**
     * Computes the bid-ask spread for a worker type.
     *
     * <p>{@code spread = BASE_SPREAD × (1 + |inventory - target| / max(target, 1))}</p>
     *
     * <p>Wider spread = worker type is overloaded or under-utilized relative to target.</p>
     *
     * @param workerType the worker type name
     * @return spread value (always ≥ BASE_SPREAD)
     */
    public double spread(String workerType) {
        int inv = currentInventory.getOrDefault(workerType, 0);
        double target = targetInventory.getOrDefault(workerType, 0.0);
        double safeTarget = Math.max(target, 1.0);
        return BASE_SPREAD * (1.0 + Math.abs(inv - target) / safeTarget);
    }

    /**
     * Computes dispatch priority for a task.
     *
     * <p>{@code priority = (1 / spread) × urgency × cpBonus}</p>
     *
     * <p>Higher priority = should be dispatched first.</p>
     *
     * @param workerType       the worker type name
     * @param hoursSinceReady  hours since the task became ready (WAITING with deps satisfied)
     * @param onCriticalPath   whether the task is on the plan's critical path
     * @return priority score (higher = dispatch sooner)
     */
    public double priority(String workerType, double hoursSinceReady, boolean onCriticalPath) {
        double s = spread(workerType);
        return (1.0 / s) * urgency(hoursSinceReady) * cpBonus(onCriticalPath);
    }

    /**
     * Urgency increases linearly with wait time.
     *
     * <p>{@code urgency = 1.0 + (hoursSinceReady / 4.0)}</p>
     *
     * @param hoursSinceReady hours since the task became ready
     * @return urgency factor (≥ 1.0)
     */
    static double urgency(double hoursSinceReady) {
        return 1.0 + (hoursSinceReady / 4.0);
    }

    /**
     * Critical path bonus: 2.0 if on critical path, 1.0 otherwise.
     */
    static double cpBonus(boolean onCriticalPath) {
        return onCriticalPath ? 2.0 : 1.0;
    }
}
