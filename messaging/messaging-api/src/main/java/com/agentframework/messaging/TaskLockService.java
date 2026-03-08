package com.agentframework.messaging;

/**
 * Provider-agnostic distributed lock for task processing.
 *
 * <p>Prevents double processing when a worker restarts (PEL reclaim) while
 * AutoRetryScheduler has already re-dispatched the same task to another instance.
 *
 * <p>Implementations must be thread-safe. The Redis implementation uses SETNX
 * with a per-JVM consumer ID and an atomic Lua release script.
 */
public interface TaskLockService {

    /**
     * Acquires an exclusive lock for the given task key.
     *
     * @param taskKey unique task identifier (e.g. "PLAN-001_BE-002")
     * @return true if the lock was acquired, false if already held by another consumer
     */
    boolean acquire(String taskKey);

    /**
     * Releases the lock for the given task key.
     * No-op if the lock is not held by this consumer.
     *
     * @param taskKey unique task identifier
     */
    void release(String taskKey);

    /**
     * Renews the TTL on the lock for the given task key.
     * Called periodically for long-running tasks to prevent premature expiry.
     *
     * @param taskKey unique task identifier
     */
    void heartbeat(String taskKey);
}
