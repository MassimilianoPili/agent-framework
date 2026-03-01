package com.agentframework.rag.tool;

import com.agentframework.rag.graph.GraphRagService;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.ScoredChunk;
import com.agentframework.rag.model.SearchFilters;
import com.agentframework.rag.model.SearchResult;
import com.agentframework.rag.search.RagSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SemanticSearchToolTest {

    private SemanticSearchTool tool;
    private RagSearchService mockSearch;
    private GraphRagService mockGraph;

    @BeforeEach
    void setUp() {
        mockSearch = mock(RagSearchService.class);
        mockGraph = mock(GraphRagService.class);
        tool = new SemanticSearchTool(mockSearch, mockGraph);
    }

    @Test
    void shouldCombineSearchAndGraphResults() {
        var chunk = new ScoredChunk(
                new CodeChunk("public class Foo {}", null,
                        new ChunkMetadata("src/Foo.java", "java", "Foo", List.of(), ChunkMetadata.DocType.CODE, List.of())),
                0.92, "cosine");
        var searchResult = new SearchResult(List.of(chunk), "hybrid+hyde+cascade", 15);
        when(mockSearch.search(eq("find Foo"), any(SearchFilters.class))).thenReturn(searchResult);
        when(mockGraph.findRelatedInsights("find Foo"))
                .thenReturn(List.of("Found 2 related code entities: Foo, FooService"));

        Map<String, Object> result = tool.search("find Foo", null, null).block();

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("chunks");
        assertEquals(1, chunks.size());
        assertEquals("public class Foo {}", chunks.get(0).get("content"));
        assertEquals(0.92, chunks.get(0).get("score"));
        assertEquals("src/Foo.java", chunks.get(0).get("filePath"));

        @SuppressWarnings("unchecked")
        List<String> insights = (List<String>) result.get("graphInsights");
        assertEquals(1, insights.size());
        assertTrue(insights.get(0).contains("Foo"));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
        assertEquals("hybrid+hyde+cascade", metadata.get("searchMode"));
        assertEquals(15, metadata.get("totalCandidates"));
    }

    @Test
    void shouldApplyMaxResultsAndLanguageFilters() {
        when(mockSearch.search(anyString(), any(SearchFilters.class)))
                .thenReturn(new SearchResult(List.of(), "hybrid", 0));
        when(mockGraph.findRelatedInsights(anyString())).thenReturn(List.of());

        tool.search("query", 5, "java").block();

        verify(mockSearch).search(eq("query"), argThat(f ->
                f.maxResults() == 5 && "java".equals(f.language())));
    }

    @Test
    void shouldDefaultMaxResultsToEight() {
        when(mockSearch.search(anyString(), any(SearchFilters.class)))
                .thenReturn(new SearchResult(List.of(), "hybrid", 0));
        when(mockGraph.findRelatedInsights(anyString())).thenReturn(List.of());

        tool.search("query", null, null).block();

        verify(mockSearch).search(eq("query"), argThat(f -> f.maxResults() == 8));
    }

    @Test
    void shouldHandleChunkWithMinimalMetadata() {
        var chunk = new ScoredChunk(
                new CodeChunk("some code", null, new ChunkMetadata("file.py", "python")),
                0.75, "rrf");
        var searchResult = new SearchResult(List.of(chunk), "vector", 1);
        when(mockSearch.search(anyString(), any())).thenReturn(searchResult);
        when(mockGraph.findRelatedInsights(anyString())).thenReturn(List.of());

        Map<String, Object> result = tool.search("query", null, null).block();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("chunks");
        assertEquals("file.py", chunks.get(0).get("filePath"));
        assertEquals("python", chunks.get(0).get("language"));
        assertNull(chunks.get(0).get("sectionTitle"));
    }
}
