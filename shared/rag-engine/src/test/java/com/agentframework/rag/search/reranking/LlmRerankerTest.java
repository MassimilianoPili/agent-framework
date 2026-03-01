package com.agentframework.rag.search.reranking;

import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.ScoredChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LlmRerankerTest {

    private LlmReranker reranker;
    private ChatClient.Builder mockBuilder;

    @BeforeEach
    void setUp() {
        mockBuilder = mock(ChatClient.Builder.class);
        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequest = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec mockResponse = mock(ChatClient.CallResponseSpec.class);

        when(mockBuilder.build()).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockRequest);
        when(mockRequest.user(any(String.class))).thenReturn(mockRequest);
        when(mockRequest.call()).thenReturn(mockResponse);
        when(mockResponse.content()).thenReturn("8");

        reranker = new LlmReranker(mockBuilder, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Test
    void shouldScoreCandidatesInParallel() {
        var chunk1 = new ScoredChunk(
                new CodeChunk("class A {}", null, new ChunkMetadata("A.java", "java")),
                0.5, "vector");
        var chunk2 = new ScoredChunk(
                new CodeChunk("class B {}", null, new ChunkMetadata("B.java", "java")),
                0.3, "vector");

        List<ScoredChunk> result = reranker.rerank("find A class", List.of(chunk1, chunk2), 2);

        assertEquals(2, result.size());
        assertEquals("llm", result.getFirst().rerankerStage());
        assertEquals(8.0, result.getFirst().score());
    }

    @Test
    void shouldParseNumericScoreCorrectly() {
        assertEquals(8.0, LlmReranker.parseScore("8"));
        assertEquals(7.5, LlmReranker.parseScore("7.5"));
        assertEquals(9.0, LlmReranker.parseScore("  9  "));
        assertEquals(10.0, LlmReranker.parseScore("10/10"));
    }

    @Test
    void shouldFallbackOnUnparseableScore() {
        assertEquals(5.0, LlmReranker.parseScore("very relevant"));
        assertEquals(5.0, LlmReranker.parseScore(""));
        assertEquals(5.0, LlmReranker.parseScore(null));
    }

    @Test
    void shouldClampScoreTo0and10() {
        assertEquals(10.0, LlmReranker.parseScore("15"));
        // "-3" → regex strips non-numeric → "3" → 3.0 (within 0-10)
        assertEquals(3.0, LlmReranker.parseScore("-3"));
        assertEquals(0.0, LlmReranker.parseScore("0"));
    }

    @Test
    void shouldHandleLlmErrorGracefully() {
        when(mockBuilder.build()).thenThrow(new RuntimeException("LLM down"));

        var chunk = new ScoredChunk(
                new CodeChunk("code", null, new ChunkMetadata("X.java", "java")),
                0.5, "vector");

        List<ScoredChunk> result = reranker.rerank("query", List.of(chunk), 1);
        assertEquals(1, result.size());
        assertEquals(5.0, result.getFirst().score()); // fallback score
    }
}
