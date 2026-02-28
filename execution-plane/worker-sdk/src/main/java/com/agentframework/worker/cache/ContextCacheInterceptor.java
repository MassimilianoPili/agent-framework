package com.agentframework.worker.cache;

import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.dto.AgentTask;
import com.agentframework.worker.interceptor.WorkerInterceptor;
import com.agentframework.worker.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * WorkerInterceptor that implements context-level result caching.
 *
 * <p>Cache key: {@code sha256(workerType + ":" + contextJson)}.
 * This key captures all inputs to the worker's LLM call — task content, dependency
 * results (TASK_MANAGER issue snapshot, CONTEXT_MANAGER file list, etc.) — so identical
 * inputs across different plans produce identical cache keys and retrieve cached results.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@code beforeExecute}: compute key, check {@link ContextCacheStore}.
 *       On hit → store result in {@link ContextCacheHolder} (signals process() to skip LLM).</li>
 *   <li>{@code afterExecute}: on miss (ContextCacheHolder empty), store fresh result in cache.
 *       Always clears the ThreadLocal (cleanup for both hit and miss paths).</li>
 * </ol>
 *
 * <p>Order: runs at {@code LOWEST_PRECEDENCE - 100}, just before
 * {@code ResultSchemaValidationInterceptor} ({@code LOWEST_PRECEDENCE}).
 * Runs after metrics interceptor so MDC context is set when cache operations are logged.
 *
 * <p>If the cache store is a {@link NoOpContextCacheStore} (default), this interceptor
 * runs but is effectively transparent.
 */
@Component
public class ContextCacheInterceptor implements WorkerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ContextCacheInterceptor.class);

    private final ContextCacheStore cacheStore;

    public ContextCacheInterceptor(ContextCacheStore cacheStore) {
        this.cacheStore = cacheStore;
    }

    @Override
    public AgentContext beforeExecute(AgentContext ctx, AgentTask task) {
        String cacheKey = buildCacheKey(task);
        String cached = cacheStore.get(cacheKey);
        if (cached != null) {
            ContextCacheHolder.set(cached);
            log.info("[CACHE HIT] workerType={} task={} key={}…",
                     task.workerType(), task.taskKey(), cacheKey.substring(0, 16));
        }
        return ctx;
    }

    @Override
    public String afterExecute(AgentContext ctx, String result, AgentTask task) {
        boolean wasCacheHit = ContextCacheHolder.get() != null;
        // Always clear the ThreadLocal — process() may also clear it in finally, but
        // clearing here is safe and makes the interceptor self-contained.
        ContextCacheHolder.clear();

        if (!wasCacheHit && result != null && !result.isBlank()) {
            String cacheKey = buildCacheKey(task);
            cacheStore.put(cacheKey, result);
            log.debug("[CACHE STORE] workerType={} task={} key={}…",
                      task.workerType(), task.taskKey(), cacheKey.substring(0, 16));
        }
        return result;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    /**
     * Builds a deterministic cache key from the task's worker type and full context JSON.
     * {@code contextJson} includes all upstream dependency results, so any change in inputs
     * (different issue, different commit) changes the hash and causes a cache miss.
     */
    private String buildCacheKey(AgentTask task) {
        String input = task.workerType() + ":" + (task.contextJson() != null ? task.contextJson() : "");
        return "agentfw:ctx-cache:" + HashUtil.sha256(input);
    }
}
