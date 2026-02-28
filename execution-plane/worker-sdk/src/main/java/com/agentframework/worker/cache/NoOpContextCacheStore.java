package com.agentframework.worker.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default no-op cache store — caching is disabled unless the application declares
 * a {@link ContextCacheStore} bean (e.g., backed by Redis).
 *
 * <p>Registered with {@code @ConditionalOnMissingBean} so it is replaced automatically
 * when any other {@code ContextCacheStore} bean is present in the application context.
 */
@Component
@ConditionalOnMissingBean(value = ContextCacheStore.class, ignored = NoOpContextCacheStore.class)
public class NoOpContextCacheStore implements ContextCacheStore {

    @Override
    public String get(String key) {
        return null; // always a cache miss
    }

    @Override
    public void put(String key, String value) {
        // no-op
    }

    @Override
    public void evict(String key) {
        // no-op
    }
}
