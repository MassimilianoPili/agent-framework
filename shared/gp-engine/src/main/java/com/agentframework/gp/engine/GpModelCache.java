package com.agentframework.gp.engine;

import com.agentframework.gp.model.GpPosterior;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Time-based cache for fitted GP posteriors.
 *
 * <p>Key format: "{workerType}:{workerProfile}" (e.g. "BE:be-java").
 * TTL-based expiration checked on read. Explicit invalidation on new outcome.</p>
 *
 * <p>Not a Spring bean: created and owned by the consumer service.
 * Thread-safe via {@link ConcurrentHashMap}.</p>
 */
public class GpModelCache {

    private final Duration ttl;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public GpModelCache(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * Returns the cached posterior, or {@code null} if absent or expired.
     */
    public GpPosterior get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (Instant.now().isAfter(entry.expiresAt)) {
            cache.remove(key);
            return null;
        }
        return entry.posterior;
    }

    /** Stores a fitted posterior with the configured TTL. */
    public void put(String key, GpPosterior posterior) {
        cache.put(key, new CacheEntry(posterior, Instant.now().plus(ttl)));
    }

    /** Removes a specific entry (called when new training data arrives). */
    public void invalidate(String key) {
        cache.remove(key);
    }

    /** Removes all entries. */
    public void invalidateAll() {
        cache.clear();
    }

    /** Returns the number of (possibly expired) entries. */
    public int size() {
        return cache.size();
    }

    /** Cache key factory: "WORKER_TYPE:profile". */
    public static String cacheKey(String workerType, String profile) {
        return workerType + ":" + profile;
    }

    private record CacheEntry(GpPosterior posterior, Instant expiresAt) {}
}
