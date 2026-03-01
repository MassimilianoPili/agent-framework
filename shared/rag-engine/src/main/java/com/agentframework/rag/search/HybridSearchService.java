package com.agentframework.rag.search;

import com.agentframework.rag.config.RagProperties;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.ScoredChunk;
import com.agentframework.rag.model.SearchFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hybrid search combining pgvector similarity search and PostgreSQL full-text search (BM25).
 *
 * <p>Executes vector and BM25 searches in parallel using virtual threads,
 * then fuses results via Reciprocal Rank Fusion (RRF) for improved recall
 * over either method alone.</p>
 *
 * <p>RRF formula: score(d) = Σ 1/(k + rank_i) for each ranked source, k=60 (default).
 * Supports 2 sources (vector + BM25) by default, extendable to N sources via the
 * overloaded {@link #search(String, SearchFilters, List)} method (e.g., serendipity).</p>
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executor;
    private final int topK;
    private final double similarityThreshold;
    private final int rrfK;
    private final boolean hybridEnabled;

    public HybridSearchService(VectorStore vectorStore,
                                JdbcTemplate jdbcTemplate,
                                @Qualifier("ragParallelExecutor") ExecutorService ragParallelExecutor,
                                RagProperties properties) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.executor = ragParallelExecutor;
        this.topK = properties.search().topK();
        this.similarityThreshold = properties.search().similarityThreshold();
        this.rrfK = properties.search().rrfK();
        this.hybridEnabled = properties.search().hybridEnabled();
    }

    /**
     * Execute hybrid search: vector + BM25 in parallel, fused via RRF.
     *
     * @param query   the search query (or HyDE-transformed query)
     * @param filters optional filters (language, filePath, docType)
     * @return fused and ranked list of scored chunks
     */
    public List<ScoredChunk> search(String query, SearchFilters filters) {
        return search(query, filters, List.of());
    }

    /**
     * Execute hybrid search with an optional additional ranked list (e.g., serendipity hints).
     *
     * <p>When {@code additionalResults} is non-empty, it is included as a third RRF source
     * alongside vector and BM25, extending the score to:
     * {@code score(d) = 1/(k+r_vector) + 1/(k+r_bm25) + 1/(k+r_additional)}.</p>
     *
     * @param query             the search query (or HyDE-transformed query)
     * @param filters           optional filters (language, filePath, docType)
     * @param additionalResults extra ranked list to include in RRF (empty = standard 2-source)
     * @return fused and ranked list of scored chunks
     */
    public List<ScoredChunk> search(String query, SearchFilters filters,
                                     List<ScoredChunk> additionalResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        if (!hybridEnabled) {
            // Vector-only mode
            return vectorSearch(query, filters);
        }

        // Fan-out: vector + BM25 in parallel
        var vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearch(query, filters), executor);
        var bm25Future = CompletableFuture.supplyAsync(
                () -> bm25Search(query, filters), executor);

        try {
            CompletableFuture.allOf(vectorFuture, bm25Future)
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[RAG Search] Hybrid search timed out: {}", e.getMessage());
        }

        List<ScoredChunk> vectorResults = vectorFuture.getNow(List.of());
        List<ScoredChunk> bm25Results = bm25Future.getNow(List.of());

        // Build N-source ranked list for RRF
        List<List<ScoredChunk>> rankedLists = new ArrayList<>();
        rankedLists.add(vectorResults);
        rankedLists.add(bm25Results);
        if (additionalResults != null && !additionalResults.isEmpty()) {
            rankedLists.add(additionalResults);
        }

        List<ScoredChunk> fused = reciprocalRankFusion(rankedLists);

        log.debug("[RAG Search] Hybrid: vector={}, bm25={}, additional={}, fused={}",
                vectorResults.size(), bm25Results.size(),
                additionalResults != null ? additionalResults.size() : 0,
                fused.size());
        return fused;
    }

    List<ScoredChunk> vectorSearch(String query, SearchFilters filters) {
        try {
            var requestBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold);

            List<Document> docs = vectorStore.similaritySearch(requestBuilder.build());
            return docs.stream()
                    .map(doc -> toScoredChunk(doc, "vector"))
                    .toList();
        } catch (Exception e) {
            log.warn("[RAG Search] Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    List<ScoredChunk> bm25Search(String query, SearchFilters filters) {
        try {
            String sql = """
                    SELECT id, content, metadata
                    FROM vector_store
                    WHERE to_tsvector('english', content) @@ plainto_tsquery('english', ?)
                    ORDER BY ts_rank(to_tsvector('english', content), plainto_tsquery('english', ?)) DESC
                    LIMIT ?""";

            return jdbcTemplate.query(sql,
                    (rs, rowNum) -> {
                        String content = rs.getString("content");
                        var chunk = new CodeChunk(content, null, new ChunkMetadata("", ""));
                        return new ScoredChunk(chunk, 1.0 / (rowNum + 1), "bm25");
                    },
                    query, query, topK);
        } catch (Exception e) {
            log.warn("[RAG Search] BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Reciprocal Rank Fusion over N ranked lists.
     *
     * <p>For each document, the score is: {@code Σ 1/(k + rank_i)} where rank_i is the
     * document's position in the i-th list (1-indexed). Documents absent from a list
     * receive a default rank of {@code topK + 1} (gentle penalty, not exclusion).</p>
     *
     * @param rankedLists N ranked lists of scored chunks (at least 1)
     * @return fused results sorted by RRF score, limited to topK
     */
    List<ScoredChunk> reciprocalRankFusion(List<List<ScoredChunk>> rankedLists) {
        // Build rank map for each source
        List<Map<String, Integer>> rankMaps = rankedLists.stream()
                .map(this::buildRankMap)
                .toList();

        // Collect all unique chunks across all lists
        Map<String, ScoredChunk> allChunks = new LinkedHashMap<>();
        for (List<ScoredChunk> list : rankedLists) {
            list.forEach(sc -> allChunks.putIfAbsent(sc.chunk().content(), sc));
        }

        // RRF score = Σ 1/(k + rank_i) for each source
        return allChunks.entrySet().stream()
                .map(entry -> {
                    String content = entry.getKey();
                    double rrfScore = 0.0;
                    for (Map<String, Integer> rankMap : rankMaps) {
                        int rank = rankMap.getOrDefault(content, topK + 1);
                        rrfScore += 1.0 / (rrfK + rank);
                    }
                    return new ScoredChunk(entry.getValue().chunk(), rrfScore, "rrf");
                })
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    private Map<String, Integer> buildRankMap(List<ScoredChunk> results) {
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            ranks.putIfAbsent(results.get(i).chunk().content(), i + 1);
        }
        return ranks;
    }

    private ScoredChunk toScoredChunk(Document doc, String stage) {
        Map<String, Object> meta = doc.getMetadata();
        String filePath = meta.getOrDefault("filePath", "").toString();
        String language = meta.getOrDefault("language", "").toString();
        var chunk = new CodeChunk(doc.getText(), null, new ChunkMetadata(filePath, language));
        double score = meta.containsKey("distance")
                ? 1.0 - ((Number) meta.get("distance")).doubleValue()
                : 0.5;
        return new ScoredChunk(chunk, score, stage);
    }
}
