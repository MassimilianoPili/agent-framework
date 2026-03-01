package com.agentframework.rag.search.reranking;

import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.ScoredChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CascadeRerankerTest {

    private CascadeReranker cascadeReranker;
    private CosineReranker mockCosine;
    private LlmReranker mockLlm;

    @BeforeEach
    void setUp() {
        mockCosine = mock(CosineReranker.class);
        mockLlm = mock(LlmReranker.class);
        cascadeReranker = new CascadeReranker(mockCosine, mockLlm, 10);
    }

    private ScoredChunk makeChunk(String content, double score, String stage) {
        return new ScoredChunk(
                new CodeChunk(content, null, new ChunkMetadata(content + ".java", "java")),
                score, stage);
    }

    @Test
    void shouldExecuteTwoStages() {
        var candidates = List.of(makeChunk("A", 0.9, "rrf"), makeChunk("B", 0.8, "rrf"),
                makeChunk("C", 0.7, "rrf"));
        var stage1Result = List.of(makeChunk("A", 0.95, "cosine"), makeChunk("B", 0.85, "cosine"));
        var stage2Result = List.of(makeChunk("B", 9.0, "llm"), makeChunk("A", 7.0, "llm"));

        when(mockCosine.rerank(eq("query"), eq(candidates), eq(10))).thenReturn(stage1Result);
        when(mockLlm.rerank(eq("query"), eq(stage1Result), eq(3))).thenReturn(stage2Result);

        List<ScoredChunk> result = cascadeReranker.rerank("query", candidates, 3);

        assertEquals(2, result.size());
        assertEquals("B", result.getFirst().chunk().content()); // LLM ranked B higher
        verify(mockCosine).rerank("query", candidates, 10);
        verify(mockLlm).rerank("query", stage1Result, 3);
    }

    @Test
    void shouldHandleEmptyCandidates() {
        List<ScoredChunk> result = cascadeReranker.rerank("query", List.of(), 5);
        assertTrue(result.isEmpty());
        verifyNoInteractions(mockCosine, mockLlm);
    }

    @Test
    void shouldHandleSingleCandidate() {
        var single = List.of(makeChunk("only", 0.9, "rrf"));
        when(mockCosine.rerank(eq("query"), eq(single), eq(10))).thenReturn(single);
        when(mockLlm.rerank(eq("query"), eq(single), eq(1))).thenReturn(single);

        List<ScoredChunk> result = cascadeReranker.rerank("query", single, 1);
        assertEquals(1, result.size());
    }

    @Test
    void shouldPassStage1TopKToCosine() {
        var cascade = new CascadeReranker(mockCosine, mockLlm, 15);
        var candidates = List.of(makeChunk("X", 0.5, "rrf"));

        when(mockCosine.rerank(anyString(), anyList(), eq(15))).thenReturn(candidates);
        when(mockLlm.rerank(anyString(), anyList(), anyInt())).thenReturn(candidates);

        cascade.rerank("query", candidates, 5);
        verify(mockCosine).rerank("query", candidates, 15);
    }

    @Test
    void shouldPassFinalTopKToLlm() {
        var candidates = List.of(makeChunk("X", 0.5, "rrf"));
        when(mockCosine.rerank(anyString(), anyList(), anyInt())).thenReturn(candidates);
        when(mockLlm.rerank(anyString(), anyList(), eq(3))).thenReturn(candidates);

        cascadeReranker.rerank("query", candidates, 3);
        verify(mockLlm).rerank("query", candidates, 3);
    }

    @Test
    void shouldPreserveOrderFromLlmStage() {
        var candidates = List.of(makeChunk("A", 0.5, "rrf"), makeChunk("B", 0.8, "rrf"));
        var cosineResult = List.of(makeChunk("B", 0.9, "cosine"), makeChunk("A", 0.6, "cosine"));
        var llmResult = List.of(makeChunk("A", 9.5, "llm"), makeChunk("B", 3.0, "llm"));

        when(mockCosine.rerank(anyString(), anyList(), anyInt())).thenReturn(cosineResult);
        when(mockLlm.rerank(anyString(), anyList(), anyInt())).thenReturn(llmResult);

        List<ScoredChunk> result = cascadeReranker.rerank("query", candidates, 2);
        assertEquals("A", result.getFirst().chunk().content()); // LLM says A is more relevant
    }
}
