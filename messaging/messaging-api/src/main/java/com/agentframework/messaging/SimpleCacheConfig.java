package com.agentframework.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default in-process cache using ConcurrentHashMap.
 * Active when cache.provider is not set or set to "simple".
 * Zero external dependencies — suitable for single-instance deployments.
 */
@Configuration
@ConditionalOnProperty(name = "cache.provider", havingValue = "simple", matchIfMissing = true)
@EnableCaching
public class SimpleCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("prompts", "skills");
    }
}
