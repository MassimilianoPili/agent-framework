package com.agentframework.rag.search;

import com.agentframework.rag.config.RagProperties;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.ScoredChunk;
import com.agentframework.rag.model.SearchFilters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HybridSearchServiceTest {

    private HybridSearchService service;
    private VectorStore mockVectorStore;
    private JdbcTemplate mockJdbcTemplate;

    @BeforeEach
    void setUp() {
        mockVectorStore = mock(VectorStore.class);
        mockJdbcTemplate = mock(JdbcTemplate.class);
        var properties = new RagProperties(true,
                new RagProperties.Ingestion(512, 100, true, 500, List.of("java"), false),
                new RagProperties.Search(true, true, "cascade", 20, 8, 0.5, 60),
                new RagProperties.Ollama("mxbai-embed-large", "qwen2.5:1.5b", "http://localhost:11434"),
                new RagProperties.Cache(5, 24, 60));
        service = new HybridSearchService(mockVectorStore, mockJdbcTemplate,
                Executors.newVirtualThreadPerTaskExecutor(), properties);
    }

    @Test
    void shouldReturnEmptyForBlankQuery() {
        assertTrue(service.search("", SearchFilters.defaults()).isEmpty());
        assertTrue(service.search(null, SearchFilters.defaults()).isEmpty());
    }

    @Test
    void shouldExecuteVectorSearch() {
        var doc = new Document("class Foo {}", Map.of("filePath", "Foo.java", "language", "java"));
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        List<ScoredChunk> results = service.vectorSearch("Foo class", SearchFilters.defaults());
        assertFalse(results.isEmpty());
        assertEquals("vector", results.getFirst().rerankerStage());
    }

    @Test
    void shouldHandleVectorSearchError() {
        when(mockVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("Connection failed"));

        List<ScoredChunk> results = service.vectorSearch("query", SearchFilters.defaults());
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldHandleBm25SearchError() {
        when(mockJdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(), any(), any()))
                .thenThrow(new RuntimeException("SQL error"));

        List<ScoredChunk> results = service.bm25Search("query", SearchFilters.defaults());
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldFuseResultsWithRRF() {
        var chunk1 = new CodeChunk("class A {}", null, new ChunkMetadata("A.java", "java"));
        var chunk2 = new CodeChunk("class B {}", null, new ChunkMetadata("B.java", "java"));
        var chunk3 = new CodeChunk("class C {}", null, new ChunkMetadata("C.java", "java"));

        List<ScoredChunk> vectorResults = List.of(
                new ScoredChunk(chunk1, 0.95, "vector"),
                new ScoredChunk(chunk2, 0.80, "vector"));

        List<ScoredChunk> bm25Results = List.of(
                new ScoredChunk(chunk2, 0.9, "bm25"),
                new ScoredChunk(chunk3, 0.7, "bm25"));

        List<ScoredChunk> fused = service.reciprocalRankFusion(List.of(vectorResults, bm25Results));

        assertFalse(fused.isEmpty());
        // chunk2 appears in both lists → highest RRF score
        assertEquals("class B {}", fused.getFirst().chunk().content());
        assertEquals("rrf", fused.getFirst().rerankerStage());
    }

    @Test
    void shouldHandleEmptyFusionInputs() {
        List<ScoredChunk> fused = service.reciprocalRankFusion(List.of(List.of(), List.of()));
        assertTrue(fused.isEmpty());
    }

    @Test
    void shouldFuseThreeSourcesWithRRF() {
        var chunkA = new CodeChunk("class A {}", null, new ChunkMetadata("A.java", "java"));
        var chunkB = new CodeChunk("class B {}", null, new ChunkMetadata("B.java", "java"));
        var chunkC = new CodeChunk("class C {}", null, new ChunkMetadata("C.java", "java"));
        var chunkD = new CodeChunk("class D {}", null, new ChunkMetadata("D.java", "java"));

        // vector: [A(rank 1), B(rank 2)]
        List<ScoredChunk> vectorResults = List.of(
                new ScoredChunk(chunkA, 0.95, "vector"),
                new ScoredChunk(chunkB, 0.80, "vector"));
        // bm25: [B(rank 1), C(rank 2)]
        List<ScoredChunk> bm25Results = List.of(
                new ScoredChunk(chunkB, 0.9, "bm25"),
                new ScoredChunk(chunkC, 0.7, "bm25"));
        // serendipity: [D(rank 1), A(rank 2)]
        List<ScoredChunk> serendipityResults = List.of(
                new ScoredChunk(chunkD, 0.6, "serendipity"),
                new ScoredChunk(chunkA, 0.4, "serendipity"));

        List<ScoredChunk> fused = service.reciprocalRankFusion(
                List.of(vectorResults, bm25Results, serendipityResults));

        assertFalse(fused.isEmpty());
        assertEquals(4, fused.size()); // A, B, C, D all present

        // k=60, topK=20, default rank for absent = topK+1 = 21
        // B: vector(rank 2) + bm25(rank 1) + serendipity(absent=21) = 1/62 + 1/61 + 1/81
        // A: vector(rank 1) + bm25(absent=21) + serendipity(rank 2) = 1/61 + 1/81 + 1/62
        // B and A have the same RRF score formula (same rank sets, different order)
        // but both are higher than C and D which only appear in 1 list each
        double rrfB = 1.0/62 + 1.0/61 + 1.0/81;
        double rrfA = 1.0/61 + 1.0/81 + 1.0/62;
        assertEquals(rrfB, rrfA, 0.0001); // same score, different sources

        // C: bm25(rank 2) + vector(absent=21) + serendipity(absent=21) = 1/62 + 1/81 + 1/81
        double rrfC = 1.0/62 + 1.0/81 + 1.0/81;
        // D: serendipity(rank 1) + vector(absent=21) + bm25(absent=21) = 1/61 + 1/81 + 1/81
        double rrfD = 1.0/61 + 1.0/81 + 1.0/81;

        // Verify ordering: A/B tie > D > C
        assertTrue(fused.get(0).score() > rrfD - 0.0001);  // top is A or B
        assertTrue(fused.get(1).score() > rrfD - 0.0001);  // second is A or B
        assertEquals(rrfD, fused.get(2).score(), 0.0001);   // D third
        assertEquals(rrfC, fused.get(3).score(), 0.0001);   // C last

        // All tagged as "rrf"
        assertTrue(fused.stream().allMatch(sc -> "rrf".equals(sc.rerankerStage())));
    }

    @Test
    void searchWithAdditionalResults_includesThirdRRFSource() {
        var doc = new Document("hybrid content", Map.of("filePath", "H.java", "language", "java"));
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(mockJdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(), any(), any()))
                .thenReturn(List.of());

        var serendipityChunk = new CodeChunk("serendipity file", null, new ChunkMetadata("S.java", "java"));
        List<ScoredChunk> additional = List.of(new ScoredChunk(serendipityChunk, 0.8, "serendipity"));

        List<ScoredChunk> results = service.search("hybrid query", SearchFilters.defaults(), additional);
        assertNotNull(results);
        assertFalse(results.isEmpty());
        // Serendipity chunk should appear in fused results
        assertTrue(results.stream()
                .anyMatch(sc -> sc.chunk().content().equals("serendipity file")));
    }

    @Test
    void searchWithEmptyAdditionalResults_behavesSameAsTwoSources() {
        var doc = new Document("same as before", Map.of("filePath", "X.java", "language", "java"));
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(mockJdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(), any(), any()))
                .thenReturn(List.of());

        List<ScoredChunk> withoutAdditional = service.search("test query", SearchFilters.defaults());
        List<ScoredChunk> withEmptyAdditional = service.search("test query", SearchFilters.defaults(), List.of());

        assertEquals(withoutAdditional.size(), withEmptyAdditional.size());
    }

    @Test
    void shouldExecuteHybridSearchInParallel() {
        var doc = new Document("parallel test", Map.of("filePath", "P.java", "language", "java"));
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(mockJdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(), any(), any()))
                .thenReturn(List.of());

        List<ScoredChunk> results = service.search("parallel test", SearchFilters.defaults());
        assertNotNull(results);
    }
}
