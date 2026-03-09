package com.agentframework.worker.cache;

import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.dto.AgentTask;
import com.agentframework.worker.util.HashUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.Ordered;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContextCacheInterceptor} — verifies the cache hit/miss
 * flow with the {@link ContextCacheStore} SPI and {@link ContextCacheHolder} ThreadLocal.
 */
@ExtendWith(MockitoExtension.class)
class ContextCacheInterceptorTest {

    @Mock
    private ContextCacheStore cacheStore;

    @Mock
    private AgentContext context;

    private ContextCacheInterceptor interceptor;

    private static final String WORKER_TYPE = "CONTEXT_MANAGER";
    private static final String CONTEXT_JSON = "{\"dep-1\":\"{\\\"files\\\":[\\\"src/Main.java\\\"]}\"}";
    private static final String RESULT_JSON = "{\"relevant_files\":[\"src/Main.java\"]}";

    @BeforeEach
    void setUp() {
        interceptor = new ContextCacheInterceptor(cacheStore);
        ContextCacheHolder.clear();
    }

    @AfterEach
    void tearDown() {
        ContextCacheHolder.clear();
    }

    // ── Cache miss ──────────────────────────────────────────────────────────────

    @Test
    void beforeExecute_cacheMiss_holderRemainsNull() {
        when(cacheStore.get(anyString())).thenReturn(null);
        AgentTask task = buildTask(WORKER_TYPE, CONTEXT_JSON);

        interceptor.beforeExecute(context, task);

        assertThat(ContextCacheHolder.get()).isNull();
    }

    @Test
    void afterExecute_cacheMiss_storesResult() {
        AgentTask task = buildTask(WORKER_TYPE, CONTEXT_JSON);
        // Simulate miss path: ContextCacheHolder is null

        interceptor.afterExecute(context, RESULT_JSON, task);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheStore).put(keyCaptor.capture(), eq(RESULT_JSON));
        assertThat(keyCaptor.getValue()).startsWith("agentfw:ctx-cache:");
    }

    @Test
    void afterExecute_cacheMiss_blankResult_doesNotStore() {
        AgentTask task = buildTask(WORKER_TYPE, CONTEXT_JSON);

        interceptor.afterExecute(context, "  ", task);

        verify(cacheStore, never()).put(anyString(), anyString());
    }

    @Test
    void afterExecute_cacheMiss_nullResult_doesNotStore() {
        AgentTask task = buildTask(WORKER_TYPE, CONTEXT_JSON);

        interceptor.afterExecute(context, null, task);

        verify(cacheStore, never()).put(anyString(), anyString());
    }

    // ── Cache hit ───────────────────────────────────────────────────────────────

    @Test
    void beforeExecute_cacheHit_setsHolder() {
        String expectedKey = buildExpectedKey(WORKER_TYPE, CONTEXT_JSON);
        when(cacheStore.get(expectedKey)).thenReturn(RESULT_JSON);
        AgentTask task = buildTask(WORKER_TYPE, CONTEXT_JSON);

        interceptor.beforeExecute(context, task);

        assertThat(ContextCacheHolder.get()).isEqualTo(RESULT_JSON);
    }

    @Test
    void afterExecute_cacheHit_doesNotStoreAgain() {
        // Simulate hit: ContextCacheHolder is set
        ContextCacheHolder.set(RESULT_JSON);
        AgentTask task = buildTask(WORKER_TYPE, CONTEXT_JSON);

        interceptor.afterExecute(context, RESULT_JSON, task);

        verify(cacheStore, never()).put(anyString(), anyString());
    }

    @Test
    void afterExecute_cacheHit_clearsHolder() {
        ContextCacheHolder.set(RESULT_JSON);
        AgentTask task = buildTask(WORKER_TYPE, CONTEXT_JSON);

        interceptor.afterExecute(context, RESULT_JSON, task);

        assertThat(ContextCacheHolder.get()).isNull();
    }

    // ── Cache key determinism ───────────────────────────────────────────────────

    @Test
    void cacheKey_isDeterministic() {
        AgentTask task = buildTask(WORKER_TYPE, CONTEXT_JSON);
        String expectedKey = buildExpectedKey(WORKER_TYPE, CONTEXT_JSON);

        when(cacheStore.get(expectedKey)).thenReturn(null);
        interceptor.beforeExecute(context, task);

        verify(cacheStore).get(expectedKey);
    }

    @Test
    void cacheKey_nullContextJson_usesEmptyString() {
        AgentTask task = buildTask(WORKER_TYPE, null);
        String expectedKey = buildExpectedKey(WORKER_TYPE, null);

        when(cacheStore.get(expectedKey)).thenReturn(null);
        interceptor.beforeExecute(context, task);

        verify(cacheStore).get(expectedKey);
    }

    @Test
    void cacheKey_differentWorkerType_differentKey() {
        String key1 = buildExpectedKey("CONTEXT_MANAGER", CONTEXT_JSON);
        String key2 = buildExpectedKey("BE", CONTEXT_JSON);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void cacheKey_differentContext_differentKey() {
        String key1 = buildExpectedKey(WORKER_TYPE, "{\"a\":1}");
        String key2 = buildExpectedKey(WORKER_TYPE, "{\"a\":2}");

        assertThat(key1).isNotEqualTo(key2);
    }

    // ── Order ───────────────────────────────────────────────────────────────────

    @Test
    void order_isBeforeResultValidation() {
        assertThat(interceptor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 100);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static AgentTask buildTask(String workerType, String contextJson) {
        return new AgentTask(
                UUID.randomUUID(), UUID.randomUUID(), "CM-001", "Context scan",
                "Scan project files", workerType, "context-manager",
                "Build a REST API", contextJson,
                1, UUID.randomUUID(), UUID.randomUUID(), "2026-03-09T00:00:00Z",
                null, null, null, null, null, null, null);
    }

    private static String buildExpectedKey(String workerType, String contextJson) {
        String input = workerType + ":" + (contextJson != null ? contextJson : "");
        return "agentfw:ctx-cache:" + HashUtil.sha256(input);
    }
}
