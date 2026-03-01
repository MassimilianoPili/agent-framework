package com.agentframework.rag.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class KnowledgeGraphServiceTest {

    private KnowledgeGraphService service;
    private JdbcTemplate mockJdbc;

    @BeforeEach
    void setUp() {
        mockJdbc = mock(JdbcTemplate.class);
        service = new KnowledgeGraphService(mockJdbc);
    }

    @Test
    void shouldAddChunkNode() {
        service.addChunkNode("chunk-1", "App.java", "Main application class");
        verify(mockJdbc).execute(argThat((String sql) ->
                sql.contains("CREATE") && sql.contains("chunk-1") && sql.contains("App.java")));
    }

    @Test
    void shouldAddConceptNode() {
        service.addConceptNode("Authentication", "Handles user auth");
        verify(mockJdbc).execute(argThat((String sql) ->
                sql.contains("MERGE") && sql.contains("Concept") && sql.contains("Authentication")));
    }

    @Test
    void shouldAddEdgeBetweenNodes() {
        service.addEdge("chunk-1", "Chunk", "chunk-2", "Chunk", "REFERENCES");
        verify(mockJdbc).execute(argThat((String sql) ->
                sql.contains("MATCH") && sql.contains("REFERENCES")));
    }

    @Test
    void shouldFindRelatedChunks() {
        when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("result", "related-chunk")));

        List<Map<String, Object>> result = service.findRelatedChunks("chunk-1", 2);
        assertFalse(result.isEmpty());
        verify(mockJdbc).queryForList(argThat((String sql) ->
                sql.contains("chunk-1") && sql.contains("*1..2")));
    }

    @Test
    void shouldHandleFindRelatedChunksError() {
        when(mockJdbc.queryForList(anyString()))
                .thenThrow(new RuntimeException("AGE not available"));

        List<Map<String, Object>> result = service.findRelatedChunks("chunk-1", 2);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldEscapeSingleQuotesInCypher() {
        // Cypher injection test: single quotes should be escaped
        assertEquals("it\\'s safe", KnowledgeGraphService.escape("it's safe"));
        assertEquals("", KnowledgeGraphService.escape(null));
        assertEquals("no special", KnowledgeGraphService.escape("no special"));
        // $$ should be stripped to prevent breaking out of AGE wrapper
        assertEquals("test", KnowledgeGraphService.escape("test$$"));
    }
}
