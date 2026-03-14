package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory semantic cache for GP worker selection predictions.
 *
 * <p>Traditional caches use exact key matching, but task descriptions are free-form
 * text with high variance. Semantic caching uses <b>cosine similarity</b> on task
 * embeddings to match "close enough" queries — avoiding redundant GP inference
 * for tasks that are semantically equivalent to previously seen ones.</p>
 *
 * <p>Architecture:
 * <ul>
 *   <li>Per-workerType cache partitions (ConcurrentHashMap)</li>
 *   <li>Cosine similarity threshold for hit detection (default 0.92)</li>
 *   <li>LRU eviction: oldest-access entries removed when partition exceeds max size</li>
 *   <li>TTL-based expiry: entries older than {@code ttlMinutes} are skipped on lookup</li>
 * </ul></p>
 *
 * <p>Thread safety: all operations are synchronized per partition. The cache is
 * designed for moderate concurrency (orchestrator dispatch loop, not high-throughput).</p>
 *
 * @see <a href="https://arxiv.org/abs/2303.11898">
 *     GPTCache: Semantic Caching for LLM Queries (Bang et al., 2023)</a>
 */
@Service
@ConditionalOnProperty(prefix = "semantic-cache", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final ConcurrentHashMap<String, List<CacheEntry>> partitions = new ConcurrentHashMap<>();

    @Value("${semantic-cache.similarity-threshold:0.92}")
    private double similarityThreshold;

    @Value("${semantic-cache.max-entries-per-type:200}")
    private int maxEntriesPerType;

    @Value("${semantic-cache.ttl-minutes:60}")
    private long ttlMinutes;

    /**
     * Looks up a cached prediction for the given query embedding and worker type.
     *
     * @param queryEmbedding the task embedding vector
     * @param workerType     the worker type partition to search
     * @return cache lookup result (hit or miss)
     */
    public CacheLookupResult lookup(double[] queryEmbedding, String workerType) {
        List<CacheEntry> partition = partitions.get(workerType);
        if (partition == null || partition.isEmpty()) {
            return CacheLookupResult.miss();
        }

        Instant cutoff = Instant.now().minusSeconds(ttlMinutes * 60);

        synchronized (partition) {
            CacheEntry bestMatch = null;
            double bestSimilarity = -1;

            for (CacheEntry entry : partition) {
                if (entry.createdAt().isBefore(cutoff)) {
                    continue; // expired
                }

                double similarity = cosineSimilarity(queryEmbedding, entry.embedding());
                if (similarity >= similarityThreshold && similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestMatch = entry;
                }
            }

            if (bestMatch != null) {
                log.debug("Semantic cache HIT for workerType={}: similarity={:.4f}, profile={}",
                          workerType, bestSimilarity, bestMatch.profile());
                return CacheLookupResult.hit(bestMatch.profile(), bestMatch.reward(), bestSimilarity);
            }
        }

        return CacheLookupResult.miss();
    }

    /**
     * Stores a new entry in the semantic cache.
     *
     * @param embedding  the task embedding vector
     * @param workerType the worker type partition
     * @param profile    the selected worker profile
     * @param reward     the actual reward achieved
     */
    public void store(double[] embedding, String workerType, String profile, double reward) {
        List<CacheEntry> partition = partitions.computeIfAbsent(workerType,
                k -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (partition) {
            partition.add(new CacheEntry(embedding, profile, reward, Instant.now()));

            // LRU eviction: remove oldest entries if over capacity
            while (partition.size() > maxEntriesPerType) {
                partition.remove(0);
            }
        }

        log.debug("Semantic cache STORE for workerType={}: profile={}, reward={:.4f}, size={}",
                  workerType, profile, reward, partition.size());
    }

    /**
     * Returns cache statistics across all partitions.
     */
    public CacheStats getStats() {
        int totalEntries = 0;
        int totalPartitions = partitions.size();
        Map<String, Integer> perType = new HashMap<>();

        for (Map.Entry<String, List<CacheEntry>> e : partitions.entrySet()) {
            int size = e.getValue().size();
            totalEntries += size;
            perType.put(e.getKey(), size);
        }

        return new CacheStats(totalEntries, totalPartitions, maxEntriesPerType, similarityThreshold, ttlMinutes, perType);
    }

    /**
     * Clears all cache entries. Useful for testing or manual cache invalidation.
     */
    public void clear() {
        partitions.clear();
    }

    /**
     * Computes cosine similarity between two vectors.
     *
     * @return similarity in [-1, 1], where 1 = identical direction
     */
    static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length || a.length == 0) return 0.0;

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0.0 ? 0.0 : dotProduct / denominator;
    }

    /**
     * A single cached entry: embedding vector + prediction result.
     */
    public record CacheEntry(
            double[] embedding,
            String profile,
            double reward,
            Instant createdAt
    ) {}

    /**
     * Result of a cache lookup.
     *
     * @param hit         true if a sufficiently similar entry was found
     * @param profile     the cached worker profile (null if miss)
     * @param reward      the cached reward (NaN if miss)
     * @param similarity  the cosine similarity of the best match (NaN if miss)
     */
    public record CacheLookupResult(
            boolean hit,
            String profile,
            double reward,
            double similarity
    ) {
        static CacheLookupResult hit(String profile, double reward, double similarity) {
            return new CacheLookupResult(true, profile, reward, similarity);
        }

        static CacheLookupResult miss() {
            return new CacheLookupResult(false, null, Double.NaN, Double.NaN);
        }
    }

    /**
     * Cache-wide statistics.
     *
     * @param totalEntries       total cached entries across all partitions
     * @param totalPartitions    number of worker type partitions
     * @param maxEntriesPerType  configured max entries per partition
     * @param similarityThreshold cosine similarity threshold for hits
     * @param ttlMinutes         configured TTL in minutes
     * @param entriesPerType     per-workerType entry counts
     */
    public record CacheStats(
            int totalEntries,
            int totalPartitions,
            int maxEntriesPerType,
            double similarityThreshold,
            long ttlMinutes,
            Map<String, Integer> entriesPerType
    ) {}
}
