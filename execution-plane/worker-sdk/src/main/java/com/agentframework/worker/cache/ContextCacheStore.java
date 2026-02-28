package com.agentframework.worker.cache;

/**
 * SPI for context-cache storage.
 *
 * <p>The default implementation ({@link NoOpContextCacheStore}) is a no-op: every
 * {@code get} returns null and every {@code put} is discarded. Applications that need
 * cross-plan caching should declare a Redis (or other) implementation as a Spring bean,
 * which will replace the default via {@code @ConditionalOnMissingBean}.
 *
 * <p>Implementations must be thread-safe (multiple tasks may execute concurrently).
 */
public interface ContextCacheStore {

    /**
     * Returns the cached value for the given key, or null if not present / expired.
     */
    String get(String key);

    /**
     * Stores a value under the given key.
     *
     * @param key   cache key (typically a SHA-256 hex string)
     * @param value the serialized result to cache
     */
    void put(String key, String value);

    /**
     * Removes the entry for the given key (no-op if absent).
     */
    void evict(String key);
}
