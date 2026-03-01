package com.agentframework.rag.search;

import com.agentframework.rag.config.RagProperties;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.ScoredChunk;
import com.agentframework.rag.model.SearchFilters;
import com.agentframework.rag.model.SearchResult;
import com.agentframework.rag.search.reranking.NoOpReranker;
import com.agentframework.rag.search.reranking.Reranker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagSearchServiceTest {

    private RagSearchService service;
    private HybridSearchService mockHybrid;
    private HydeQueryTransformer mockHyde;
    private Reranker mockReranker;

    @BeforeEach
    void setUp() {
        mockHybrid = mock(HybridSearchService.class);
        mockHyde = mock(HydeQueryTransformer.class);
        mockReranker = mock(Reranker.class);

        when(mockHyde.isEnabled()).thenReturn(true);
        when(mockHyde.transform(anyString())).thenReturn("hypothetical answer");

        var properties = new RagProperties(true,
                new RagProperties.Ingestion(512, 100, true, 500, List.of("java"), false),
                new RagProperties.Search(true, true, "cascade", 20, 8, 0.5, 60),
                new RagProperties.Ollama("mxbai-embed-large", "qwen2.5:1.5b", "http://localhost:11434"),
                new RagProperties.Cache(5, 24, 60));
        service = new RagSearchService(mockHybrid, mockHyde, mockReranker, properties);
    }

    @Test
    void shouldExecuteFullPipeline() {
        var candidates = List.of(
                new ScoredChunk(new CodeChunk("code A", null, new ChunkMetadata("A.java", "java")),
                        0.9, "rrf"));
        when(mockHybrid.search(eq("hypothetical answer"), any())).thenReturn(candidates);
        when(mockReranker.rerank(eq("find A"), eq(candidates), anyInt())).thenReturn(candidates);

        SearchResult result = service.search("find A", SearchFilters.defaults());

        assertFalse(result.chunks().isEmpty());
        assertEquals(1, result.totalCandidates());
        assertTrue(result.searchMode().contains("hybrid"));
        assertTrue(result.searchMode().contains("hyde"));
    }

    @Test
    void shouldReturnEmptyForBlankQuery() {
        SearchResult result = service.search("", SearchFilters.defaults());
        assertTrue(result.chunks().isEmpty());
        assertEquals(0, result.totalCandidates());
    }

    @Test
    void shouldWorkWithoutHyDE() {
        when(mockHyde.isEnabled()).thenReturn(false);
        when(mockHybrid.search(eq("raw query"), any())).thenReturn(List.of());
        when(mockReranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of());

        SearchResult result = service.search("raw query", SearchFilters.defaults());
        assertNotNull(result);
        verify(mockHyde, never()).transform(anyString());
    }

    @Test
    void shouldPassOriginalQueryToReranker() {
        when(mockHybrid.search(anyString(), any())).thenReturn(List.of());
        when(mockReranker.rerank(eq("original query"), anyList(), anyInt())).thenReturn(List.of());

        service.search("original query", SearchFilters.defaults());

        // Reranker should receive the original query, not the HyDE-transformed one
        verify(mockReranker).rerank(eq("original query"), anyList(), anyInt());
    }

    @Test
    void shouldRespectMaxResultsFromFilters() {
        when(mockHybrid.search(anyString(), any())).thenReturn(List.of());

        var filters = new SearchFilters("java", null, null, 5);
        when(mockReranker.rerank(anyString(), anyList(), eq(5))).thenReturn(List.of());

        service.search("query", filters);
        verify(mockReranker).rerank(anyString(), anyList(), eq(5));
    }

    @Test
    void shouldBuildSearchModeString() {
        String mode = service.searchMode();
        assertTrue(mode.contains("hybrid"));
        assertTrue(mode.contains("hyde"));
        assertTrue(mode.contains("cascade"));
    }
}
