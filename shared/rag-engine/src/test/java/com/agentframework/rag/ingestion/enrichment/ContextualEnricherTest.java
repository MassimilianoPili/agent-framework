package com.agentframework.rag.ingestion.enrichment;

import com.agentframework.rag.config.RagProperties;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextualEnricherTest {

    private ContextualEnricher enricher;
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
        when(mockResponse.content()).thenReturn("This chunk defines the main entry point of the application.");

        var properties = new RagProperties(true,
                new RagProperties.Ingestion(512, 100, true, 500, List.of("java"), false),
                new RagProperties.Search(true, true, "cascade", 20, 8, 0.5, 60),
                new RagProperties.Ollama("mxbai-embed-large", "qwen2.5:1.5b", "http://localhost:11434"),
                new RagProperties.Cache(5, 24, 60));
        enricher = new ContextualEnricher(mockBuilder, properties,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @Test
    void shouldEnrichChunksWithContextPrefix() {
        var metadata = new ChunkMetadata("App.java", "java");
        var chunk = new CodeChunk("public static void main(String[] args) {}", null, metadata);

        List<CodeChunk> result = enricher.enrich(List.of(chunk), "Full document content here...");

        assertEquals(1, result.size());
        assertNotNull(result.getFirst().contextPrefix());
        assertTrue(result.getFirst().contextPrefix().contains("entry point"));
    }

    @Test
    void shouldReturnEmptyListForEmptyInput() {
        List<CodeChunk> result = enricher.enrich(List.of(), "document");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldFallbackGracefullyOnException() {
        // Force an exception
        when(mockBuilder.build()).thenThrow(new RuntimeException("LLM unavailable"));

        var metadata = new ChunkMetadata("App.java", "java");
        var chunk = new CodeChunk("code here", null, metadata);

        List<CodeChunk> result = enricher.enrich(List.of(chunk), "full doc");
        assertEquals(1, result.size());
        assertNull(result.getFirst().contextPrefix()); // Original chunk, no context
    }

    @Test
    void shouldTruncateLongDocuments() {
        String longDoc = "x".repeat(100_000);
        String truncated = ContextualEnricher.truncateIfNeeded(longDoc, 8000);
        assertTrue(truncated.length() < longDoc.length());
        assertTrue(truncated.endsWith("[truncated]"));
    }
}
