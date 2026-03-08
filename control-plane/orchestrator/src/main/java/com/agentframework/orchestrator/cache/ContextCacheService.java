package com.agentframework.orchestrator.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cache for CONTEXT_MANAGER task results across plan dispatch waves.
 *
 * <p>When multiple tasks in a plan depend on the same CONTEXT_MANAGER output,
 * without caching each one re-triggers context assembly (file discovery, relevance
 * scoring, graph traversal) — an expensive operation on large codebases.
 * This service caches the result JSON in Redis so that only the first assembly
 * is computed; subsequent tasks receive the cached value.</p>
 *
 * <p>Key scheme: {@code ctx:{planId}:{taskKey}} — hierarchically structured so
 * all keys for a plan can be found via the prefix {@code ctx:{planId}:*} and
 * bulk-invalidated at plan completion (see {@link #evictPlan(UUID)}).</p>
 *
 * <p>TTL: 30 minutes. This bounds the cache lifetime in case a plan is abandoned
 * without reaching a terminal state (crash, lost event). On plan completion,
 * {@link #evictPlan(UUID)} is called immediately for prompt cleanup.</p>
 */
@Service
public class ContextCacheService {

    private static final Logger log = LoggerFactory.getLogger(ContextCacheService.class);

    /** Cache TTL: 30 minutes to cover typical plan execution + crash recovery window. */
    private static final Duration TTL = Duration.ofMinutes(30);

    private static final String KEY_PREFIX = "ctx:";

    private final StringRedisTemplate redisTemplate;

    public ContextCacheService(@Qualifier("redisMessagingTemplate") StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Returns the cached context JSON for a CONTEXT_MANAGER task, if present.
     *
     * @param planId  the plan UUID
     * @param taskKey the task key of the CONTEXT_MANAGER item (e.g. "CM-001")
     * @return the cached result JSON, or empty if not cached
     */
    public Optional<String> get(UUID planId, String taskKey) {
        String key = buildKey(planId, taskKey);
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            log.debug("Context cache HIT for plan={} task={}", planId, taskKey);
        }
        return Optional.ofNullable(value);
    }

    /**
     * Stores the context JSON result for a CONTEXT_MANAGER task.
     *
     * <p>Called in {@code OrchestrationService.onTaskCompleted()} when a CONTEXT_MANAGER
     * task finishes successfully, before downstream tasks are dispatched.</p>
     *
     * @param planId      the plan UUID
     * @param taskKey     the task key of the CONTEXT_MANAGER item
     * @param contextJson the result JSON to cache
     */
    public void put(UUID planId, String taskKey, String contextJson) {
        String key = buildKey(planId, taskKey);
        redisTemplate.opsForValue().set(key, contextJson, TTL);
        log.debug("Context cache PUT for plan={} task={} (TTL={}min)", planId, taskKey, TTL.toMinutes());
    }

    /**
     * Evicts all cached context entries for a plan.
     *
     * <p>Called when the plan reaches a terminal state (COMPLETED, FAILED) to free
     * Redis memory promptly. The TTL acts as a safety net for unclean shutdowns.</p>
     *
     * @param planId the plan whose cache entries should be deleted
     */
    public void evictPlan(UUID planId) {
        String pattern = KEY_PREFIX + planId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.debug("Context cache EVICT for plan={}: removed {} key(s)", planId, keys.size());
        }
    }

    private String buildKey(UUID planId, String taskKey) {
        return KEY_PREFIX + planId + ":" + taskKey;
    }
}
