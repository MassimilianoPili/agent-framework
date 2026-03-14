package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.SemanticCacheService.CacheLookupResult;
import com.agentframework.orchestrator.analytics.SemanticCacheService.CacheStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link SemanticCacheService}.
 *
 * <p>Verifies cosine similarity computation, cache hit/miss behaviour,
 * LRU eviction, TTL expiry, and partition isolation.</p>
 */
class SemanticCacheServiceTest {

    private SemanticCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new SemanticCacheService();
        ReflectionTestUtils.setField(cache, "similarityThreshold", 0.92);
        ReflectionTestUtils.setField(cache, "maxEntriesPerType", 5);
        ReflectionTestUtils.setField(cache, "ttlMinutes", 60L);
    }

    // --- Cosine Similarity ---

    @Test
    @DisplayName("cosineSimilarity of identical vectors is 1.0")
    void cosineSimilarity_identical() {
        double[] v = {1.0, 2.0, 3.0};
        assertThat(SemanticCacheService.cosineSimilarity(v, v)).isCloseTo(1.0, within(1e-10));
    }

    @Test
    @DisplayName("cosineSimilarity of orthogonal vectors is 0.0")
    void cosineSimilarity_orthogonal() {
        double[] a = {1.0, 0.0};
        double[] b = {0.0, 1.0};
        assertThat(SemanticCacheService.cosineSimilarity(a, b)).isCloseTo(0.0, within(1e-10));
    }

    @Test
    @DisplayName("cosineSimilarity of opposite vectors is -1.0")
    void cosineSimilarity_opposite() {
        double[] a = {1.0, 0.0};
        double[] b = {-1.0, 0.0};
        assertThat(SemanticCacheService.cosineSimilarity(a, b)).isCloseTo(-1.0, within(1e-10));
    }

    @Test
    @DisplayName("cosineSimilarity with zero vector returns 0.0")
    void cosineSimilarity_zeroVector() {
        double[] a = {1.0, 2.0};
        double[] b = {0.0, 0.0};
        assertThat(SemanticCacheService.cosineSimilarity(a, b)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("cosineSimilarity with empty vectors returns 0.0")
    void cosineSimilarity_empty() {
        assertThat(SemanticCacheService.cosineSimilarity(new double[0], new double[0])).isEqualTo(0.0);
    }

    // --- Cache Lookup ---

    @Test
    @DisplayName("lookup on empty cache returns miss")
    void lookup_emptyCache_miss() {
        CacheLookupResult result = cache.lookup(new double[]{1.0, 0.0}, "BE");
        assertThat(result.hit()).isFalse();
        assertThat(result.profile()).isNull();
    }

    @Test
    @DisplayName("lookup with identical embedding returns hit")
    void lookup_identicalEmbedding_hit() {
        double[] embedding = {0.5, 0.5, 0.5};
        cache.store(embedding, "BE", "be-java", 0.8);

        CacheLookupResult result = cache.lookup(embedding, "BE");

        assertThat(result.hit()).isTrue();
        assertThat(result.profile()).isEqualTo("be-java");
        assertThat(result.reward()).isEqualTo(0.8);
        assertThat(result.similarity()).isCloseTo(1.0, within(1e-10));
    }

    @Test
    @DisplayName("lookup with similar embedding above threshold returns hit")
    void lookup_similarAboveThreshold_hit() {
        double[] stored = {1.0, 0.0, 0.0};
        double[] query = {0.98, 0.1, 0.05}; // very close direction
        cache.store(stored, "BE", "be-java", 0.7);

        CacheLookupResult result = cache.lookup(query, "BE");

        assertThat(result.hit()).isTrue();
        assertThat(result.similarity()).isGreaterThanOrEqualTo(0.92);
    }

    @Test
    @DisplayName("lookup with dissimilar embedding returns miss")
    void lookup_dissimilar_miss() {
        double[] stored = {1.0, 0.0, 0.0};
        double[] query = {0.0, 1.0, 0.0}; // orthogonal
        cache.store(stored, "BE", "be-java", 0.7);

        CacheLookupResult result = cache.lookup(query, "BE");

        assertThat(result.hit()).isFalse();
    }

    @Test
    @DisplayName("lookup is partitioned by workerType")
    void lookup_partitionIsolation() {
        double[] embedding = {0.5, 0.5};
        cache.store(embedding, "BE", "be-java", 0.8);

        CacheLookupResult beResult = cache.lookup(embedding, "BE");
        CacheLookupResult feResult = cache.lookup(embedding, "FE");

        assertThat(beResult.hit()).isTrue();
        assertThat(feResult.hit()).isFalse();
    }

    // --- LRU Eviction ---

    @Test
    @DisplayName("store evicts oldest entries when over capacity")
    void store_evictsOldest() {
        // Fill to max (5)
        for (int i = 0; i < 5; i++) {
            cache.store(new double[]{(double) i, 0.0}, "BE", "profile-" + i, 0.5);
        }
        assertThat(cache.getStats().totalEntries()).isEqualTo(5);

        // Add one more — oldest should be evicted
        cache.store(new double[]{10.0, 0.0}, "BE", "profile-new", 0.9);

        CacheStats stats = cache.getStats();
        assertThat(stats.totalEntries()).isEqualTo(5);
        assertThat(stats.entriesPerType().get("BE")).isEqualTo(5);
    }

    // --- TTL ---

    @Test
    @DisplayName("lookup skips expired entries")
    void lookup_skipsExpired() throws Exception {
        // Set TTL to 0 minutes — everything expires immediately
        ReflectionTestUtils.setField(cache, "ttlMinutes", 0L);

        double[] embedding = {1.0, 0.0};
        cache.store(embedding, "BE", "be-java", 0.8);

        // Wait a moment so Instant.now() is after the entry's createdAt
        Thread.sleep(10);

        CacheLookupResult result = cache.lookup(embedding, "BE");
        assertThat(result.hit()).isFalse();
    }

    // --- Stats ---

    @Test
    @DisplayName("getStats returns correct counts")
    void getStats_correctCounts() {
        cache.store(new double[]{1.0}, "BE", "be-java", 0.8);
        cache.store(new double[]{2.0}, "BE", "be-spring", 0.7);
        cache.store(new double[]{3.0}, "FE", "fe-react", 0.9);

        CacheStats stats = cache.getStats();

        assertThat(stats.totalEntries()).isEqualTo(3);
        assertThat(stats.totalPartitions()).isEqualTo(2);
        assertThat(stats.entriesPerType()).containsEntry("BE", 2);
        assertThat(stats.entriesPerType()).containsEntry("FE", 1);
        assertThat(stats.similarityThreshold()).isEqualTo(0.92);
    }

    // --- Clear ---

    @Test
    @DisplayName("clear removes all entries")
    void clear_removesAll() {
        cache.store(new double[]{1.0}, "BE", "be-java", 0.8);
        cache.store(new double[]{2.0}, "FE", "fe-react", 0.9);

        cache.clear();

        assertThat(cache.getStats().totalEntries()).isZero();
        assertThat(cache.getStats().totalPartitions()).isZero();
    }

    // --- Best match selection ---

    @Test
    @DisplayName("lookup returns the best match when multiple entries qualify")
    void lookup_returnsBestMatch() {
        double[] query = {1.0, 0.0, 0.0};
        double[] close = {0.95, 0.1, 0.0};   // ~0.994 similarity
        double[] closer = {0.99, 0.05, 0.0};  // ~0.999 similarity

        cache.store(close, "BE", "profile-close", 0.7);
        cache.store(closer, "BE", "profile-closer", 0.9);

        CacheLookupResult result = cache.lookup(query, "BE");

        assertThat(result.hit()).isTrue();
        assertThat(result.profile()).isEqualTo("profile-closer");
    }
}
