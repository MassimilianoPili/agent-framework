package com.agentframework.common.privacy;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe daily privacy budget tracker (#43).
 *
 * <p>Implements basic sequential composition: each query consumes one unit of
 * ε-budget. The daily limit caps total information leakage per 24-hour period.
 * The budget auto-resets at UTC midnight.</p>
 *
 * <p>Usage pattern:
 * <pre>{@code
 * if (budget.canQuery()) {
 *     double noisy = mechanism.privatize(trueValue, sensitivity, epsilon);
 *     budget.recordQuery();
 *     // use noisy value
 * } else {
 *     throw new PrivacyBudgetExhaustedException();
 * }
 * }</pre>
 */
public class PrivacyBudget {

    private final int maxQueriesPerDay;
    private final AtomicInteger queriesUsed = new AtomicInteger(0);
    private final AtomicReference<LocalDate> currentDay;

    /**
     * @param maxQueriesPerDay maximum DP queries allowed per UTC day
     */
    public PrivacyBudget(int maxQueriesPerDay) {
        if (maxQueriesPerDay <= 0) {
            throw new IllegalArgumentException("maxQueriesPerDay must be > 0");
        }
        this.maxQueriesPerDay = maxQueriesPerDay;
        this.currentDay = new AtomicReference<>(LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * Checks if a query can be executed within the remaining budget.
     * Auto-resets the counter if a new UTC day has started.
     */
    public boolean canQuery() {
        resetIfNewDay();
        return queriesUsed.get() < maxQueriesPerDay;
    }

    /**
     * Records that a DP query was executed. Call after {@link #canQuery()} returns true.
     */
    public void recordQuery() {
        resetIfNewDay();
        queriesUsed.incrementAndGet();
    }

    /**
     * Returns the number of remaining queries for today.
     */
    public int remaining() {
        resetIfNewDay();
        return Math.max(0, maxQueriesPerDay - queriesUsed.get());
    }

    /**
     * Returns the number of queries used today.
     */
    public int queriesUsedToday() {
        resetIfNewDay();
        return queriesUsed.get();
    }

    /**
     * Returns the daily query limit.
     */
    public int maxQueriesPerDay() {
        return maxQueriesPerDay;
    }

    /**
     * Resets the counter if a new UTC day has started.
     * Thread-safe via compare-and-set on the day reference.
     */
    void resetIfNewDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate stored = currentDay.get();
        if (!today.equals(stored)) {
            if (currentDay.compareAndSet(stored, today)) {
                queriesUsed.set(0);
            }
        }
    }
}
