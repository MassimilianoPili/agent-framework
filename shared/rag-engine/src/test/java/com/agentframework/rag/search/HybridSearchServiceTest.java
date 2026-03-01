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

        List<ScoredChunk> fused = service.reciprocalRankFusion(vectorResults, bm25Results);

        assertFalse(fused.isEmpty());
        // chunk2 appears in both lists → highest RRF score
        assertEquals("class B {}", fused.getFirst().chunk().content());
        assertEquals("rrf", fused.getFirst().rerankerStage());
    }

    @Test
    void shouldHandleEmptyFusionInputs() {
        List<ScoredChunk> fused = service.reciprocalRankFusion(List.of(), List.of());
        assertTrue(fused.isEmpty());
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
