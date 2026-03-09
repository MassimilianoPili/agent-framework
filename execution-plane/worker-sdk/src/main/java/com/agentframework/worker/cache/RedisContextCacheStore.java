package com.agentframework.worker.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed implementation of {@link ContextCacheStore}.
 *
 * <p>Activated automatically when a {@link StringRedisTemplate} bean is present
 * (i.e., when {@code messaging-redis} is on the classpath). Replaces the default
 * {@link NoOpContextCacheStore} via Spring's {@code @ConditionalOnMissingBean}
 * on the no-op class.</p>
 *
 * <p>Cache keys arrive from {@link ContextCacheInterceptor} in the format
 * {@code agentfw:ctx-cache:SHA256(workerType:contextJson)}. The key is
 * deterministic: identical inputs produce identical hashes, enabling cache
 * hits even across different plans.</p>
 *
 * <p>TTL is 30 minutes, consistent with the orchestrator-side
 * {@code ContextCacheService}.</p>
 */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisContextCacheStore implements ContextCacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisContextCacheStore.class);
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    public RedisContextCacheStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        log.info("RedisContextCacheStore initialized (TTL={}min)", TTL.toMinutes());
    }

    @Override
    public String get(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            log.debug("Context cache HIT key={}…", key.substring(0, Math.min(32, key.length())));
        }
        return value;
    }

    @Override
    public void put(String key, String value) {
        redisTemplate.opsForValue().set(key, value, TTL);
        log.debug("Context cache PUT key={}… (TTL={}min)", key.substring(0, Math.min(32, key.length())), TTL.toMinutes());
    }

    @Override
    public void evict(String key) {
        Boolean deleted = redisTemplate.delete(key);
        if (Boolean.TRUE.equals(deleted)) {
            log.debug("Context cache EVICT key={}…", key.substring(0, Math.min(32, key.length())));
        }
    }
}
