package com.agentframework.worker.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedisContextCacheStore}.
 */
@ExtendWith(MockitoExtension.class)
class RedisContextCacheStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisContextCacheStore store;

    private static final String KEY = "agentfw:ctx-cache:abc123def456";
    private static final String VALUE = "{\"files\":[\"src/Main.java\"]}";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = new RedisContextCacheStore(redisTemplate);
    }

    @Test
    void get_returnsValueOnHit() {
        when(valueOps.get(KEY)).thenReturn(VALUE);

        String result = store.get(KEY);

        assertThat(result).isEqualTo(VALUE);
        verify(valueOps).get(KEY);
    }

    @Test
    void get_returnsNullOnMiss() {
        when(valueOps.get(KEY)).thenReturn(null);

        String result = store.get(KEY);

        assertThat(result).isNull();
    }

    @Test
    void put_setsValueWithTtl() {
        store.put(KEY, VALUE);

        verify(valueOps).set(KEY, VALUE, Duration.ofMinutes(30));
    }

    @Test
    void evict_deletesKey() {
        when(redisTemplate.delete(KEY)).thenReturn(true);

        store.evict(KEY);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    void evict_noErrorOnMissingKey() {
        when(redisTemplate.delete(KEY)).thenReturn(false);

        store.evict(KEY);

        verify(redisTemplate).delete(KEY);
    }
}
