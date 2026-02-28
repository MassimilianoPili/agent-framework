package com.agentframework.worker.cache;

/**
 * ThreadLocal carrier for context cache hits.
 *
 * <p>{@link ContextCacheInterceptor#beforeExecute} stores a cached result here when a
 * cache hit is detected. {@link com.agentframework.worker.AbstractWorker#process} reads it
 * immediately after the interceptor pipeline and, if non-null, skips the LLM call entirely.
 *
 * <p>Uses the same ThreadLocal pattern as {@code AbstractWorker.TOKEN_USAGE}: the worker bean
 * is a singleton, so per-task state must be held in ThreadLocals. Always cleared in the
 * {@code finally} block of {@code process()} to prevent leaks in thread pools.
 */
public final class ContextCacheHolder {

    private static final ThreadLocal<String> CACHED_RESULT = new ThreadLocal<>();

    private ContextCacheHolder() {}

    public static void set(String result) {
        CACHED_RESULT.set(result);
    }

    /** Returns the cached result for the current task, or null if no cache hit. */
    public static String get() {
        return CACHED_RESULT.get();
    }

    /** Must be called in the finally block of process() to prevent ThreadLocal leaks. */
    public static void clear() {
        CACHED_RESULT.remove();
    }
}
