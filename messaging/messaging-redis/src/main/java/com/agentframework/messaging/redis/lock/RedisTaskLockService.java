package com.agentframework.messaging.redis.lock;

import com.agentframework.messaging.TaskLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed distributed task lock.
 *
 * <p>Uses SETNX with a per-JVM consumer ID stored as the lock value.
 * Release is atomic via a Lua script that DELetes only if the value matches
 * the current consumer ID — preventing a slow consumer from releasing a lock
 * that has already expired and been acquired by another instance.
 *
 * <p>Active locks are renewed every 60 s via {@link #renewActiveLocks()} so
 * long-running tasks never expire their lock prematurely.
 */
public class RedisTaskLockService implements TaskLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskLockService.class);

    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final String LOCK_PREFIX = "task-lock:";

    /**
     * Lua script: DEL the key only if its value equals ARGV[1] (our consumer ID).
     * Returns 1 if deleted, 0 if not owned by us.
     */
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final String consumerId = UUID.randomUUID().toString();
    private final Set<String> activeLocks = ConcurrentHashMap.newKeySet();

    public RedisTaskLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        log.info("RedisTaskLockService initialized — consumerId={}", consumerId);
    }

    @Override
    public boolean acquire(String taskKey) {
        String lockKey = LOCK_PREFIX + taskKey;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, consumerId, LOCK_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            activeLocks.add(taskKey);
            log.debug("Lock acquired for task {}", taskKey);
            return true;
        }
        log.debug("Lock NOT acquired for task {} — held by another consumer", taskKey);
        return false;
    }

    @Override
    public void release(String taskKey) {
        String lockKey = LOCK_PREFIX + taskKey;
        Long deleted = redisTemplate.execute(RELEASE_SCRIPT, List.of(lockKey), consumerId);
        activeLocks.remove(taskKey);
        if (deleted != null && deleted > 0) {
            log.debug("Lock released for task {}", taskKey);
        } else {
            log.debug("Lock for task {} was not ours or already expired — no action taken", taskKey);
        }
    }

    @Override
    public void heartbeat(String taskKey) {
        Boolean renewed = redisTemplate.expire(LOCK_PREFIX + taskKey, LOCK_TTL);
        log.trace("Heartbeat for task {} — TTL renewed={}", taskKey, renewed);
    }

    /**
     * Renews the TTL for all locks currently held by this consumer.
     * Runs every 60 seconds to keep long-running tasks from losing their lock.
     */
    @Scheduled(fixedDelay = 60_000)
    public void renewActiveLocks() {
        if (activeLocks.isEmpty()) {
            return;
        }
        activeLocks.forEach(this::heartbeat);
        log.debug("Heartbeat renewed {} active task lock(s)", activeLocks.size());
    }
}
