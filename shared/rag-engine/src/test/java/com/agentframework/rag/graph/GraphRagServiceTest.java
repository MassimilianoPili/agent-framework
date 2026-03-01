package com.agentframework.rag.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GraphRagServiceTest {

    private GraphRagService service;
    private KnowledgeGraphService mockKnowledge;
    private CodeGraphService mockCode;

    @BeforeEach
    void setUp() {
        mockKnowledge = mock(KnowledgeGraphService.class);
        mockCode = mock(CodeGraphService.class);
        service = new GraphRagService(mockKnowledge, mockCode,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @Test
    void shouldQueryBothGraphsInParallel() {
        when(mockKnowledge.findConceptsByKeyword("Auth"))
                .thenReturn(List.of(Map.of("result", "Authentication concept")));
        when(mockCode.findClassesByName("Auth"))
                .thenReturn(List.of(Map.of("result", "AuthService")));

        List<String> insights = service.findRelatedInsights("Auth");

        assertFalse(insights.isEmpty());
        assertTrue(insights.stream().anyMatch(s -> s.contains("concepts")));
        assertTrue(insights.stream().anyMatch(s -> s.contains("code entities")));
    }

    @Test
    void shouldHandleEmptyResults() {
        when(mockKnowledge.findConceptsByKeyword(anyString())).thenReturn(List.of());
        when(mockCode.findClassesByName(anyString())).thenReturn(List.of());

        List<String> insights = service.findRelatedInsights("NonExistent");

        assertEquals(1, insights.size());
        assertTrue(insights.getFirst().contains("No graph insights"));
    }

    @Test
    void shouldHandleNullOrBlankQuery() {
        assertTrue(service.findRelatedInsights(null).isEmpty());
        assertTrue(service.findRelatedInsights("").isEmpty());
        assertTrue(service.findRelatedInsights("   ").isEmpty());
    }

    @Test
    void shouldCorrelateResults() {
        var concepts = List.<Map<String, Object>>of(Map.of("name", "AuthConcept"));
        var classes = List.<Map<String, Object>>of(
                Map.of("result", "AuthService"),
                Map.of("result", "TokenValidator"));

        List<String> insights = service.correlate(concepts, classes, "auth");

        assertEquals(2, insights.size());
        assertTrue(insights.getFirst().contains("1 related concepts"));
        assertTrue(insights.get(1).contains("2 related code entities"));
    }
}
