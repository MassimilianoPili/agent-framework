package com.agentframework.rag.cache;

import com.agentframework.rag.config.RagProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Redis-based cache for embedding vectors and search results.
 * Uses Redis DB 5 (separate from messaging DB 3 and app cache DB 4).
 *
 * <p>Cache keys:
 * <ul>
 *   <li>{@code emb:<sha256>} — cached embedding vector (TTL: 24h)</li>
 *   <li>{@code search:<sha256>} — cached search result JSON (TTL: 1h)</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddingCacheService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCacheService.class);

    private static final String EMBEDDING_PREFIX = "emb:";
    private static final String SEARCH_PREFIX = "search:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration embeddingTtl;
    private final Duration searchResultTtl;

    public EmbeddingCacheService(
            @Qualifier("ragRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RagProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.embeddingTtl = Duration.ofHours(properties.cache().embeddingTtlHours());
        this.searchResultTtl = Duration.ofMinutes(properties.cache().searchResultTtlMinutes());
    }

    /** Cache an embedding vector for a text hash. */
    public void putEmbedding(String text, float[] embedding) {
        String key = EMBEDDING_PREFIX + sha256(text);
        try {
            String json = objectMapper.writeValueAsString(embedding);
            redisTemplate.opsForValue().set(key, json, embeddingTtl);
        } catch (JsonProcessingException e) {
            log.warn("[RAG Cache] Failed to serialize embedding: {}", e.getMessage());
        }
    }

    /** Retrieve a cached embedding vector. */
    public Optional<float[]> getEmbedding(String text) {
        String key = EMBEDDING_PREFIX + sha256(text);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, float[].class));
        } catch (JsonProcessingException e) {
            log.warn("[RAG Cache] Failed to deserialize embedding: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Cache a search result JSON string. */
    public void putSearchResult(String query, String resultJson) {
        String key = SEARCH_PREFIX + sha256(query);
        redisTemplate.opsForValue().set(key, resultJson, searchResultTtl);
    }

    /** Retrieve a cached search result. */
    public Optional<String> getSearchResult(String query) {
        String key = SEARCH_PREFIX + sha256(query);
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    /** Evict all RAG cache entries (embedding + search). */
    public void evictAll() {
        var embKeys = redisTemplate.keys(EMBEDDING_PREFIX + "*");
        var searchKeys = redisTemplate.keys(SEARCH_PREFIX + "*");
        int count = 0;
        if (embKeys != null && !embKeys.isEmpty()) {
            redisTemplate.delete(embKeys);
            count += embKeys.size();
        }
        if (searchKeys != null && !searchKeys.isEmpty()) {
            redisTemplate.delete(searchKeys);
            count += searchKeys.size();
        }
        log.info("[RAG Cache] Evicted {} cache entries", count);
    }

    static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
