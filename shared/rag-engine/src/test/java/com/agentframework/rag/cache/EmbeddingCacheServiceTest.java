package com.agentframework.rag.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingCacheServiceTest {

    @Test
    void shouldProduceConsistentSha256() {
        String hash1 = EmbeddingCacheService.sha256("hello world");
        String hash2 = EmbeddingCacheService.sha256("hello world");
        assertEquals(hash1, hash2);
    }

    @Test
    void shouldProduceDifferentHashForDifferentInput() {
        String hash1 = EmbeddingCacheService.sha256("hello");
        String hash2 = EmbeddingCacheService.sha256("world");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void shouldProduceHexString() {
        String hash = EmbeddingCacheService.sha256("test");
        assertTrue(hash.matches("[0-9a-f]{64}"), "SHA-256 should be 64 hex chars");
    }

    @Test
    void shouldHandleEmptyString() {
        String hash = EmbeddingCacheService.sha256("");
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    void shouldHandleUnicodeInput() {
        String hash = EmbeddingCacheService.sha256("日本語テスト");
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }
}
