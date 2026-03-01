package com.agentframework.rag.search;

import com.agentframework.rag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HydeQueryTransformerTest {

    private HydeQueryTransformer createTransformer(boolean hydeEnabled, String llmResponse) {
        ChatClient.Builder mockBuilder = mock(ChatClient.Builder.class);
        ChatClient mockClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequest = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec mockResponse = mock(ChatClient.CallResponseSpec.class);

        when(mockBuilder.build()).thenReturn(mockClient);
        when(mockClient.prompt()).thenReturn(mockRequest);
        when(mockRequest.user(any(String.class))).thenReturn(mockRequest);
        when(mockRequest.call()).thenReturn(mockResponse);
        when(mockResponse.content()).thenReturn(llmResponse);

        var properties = new RagProperties(true,
                new RagProperties.Ingestion(512, 100, true, 500, List.of("java"), false),
                new RagProperties.Search(true, hydeEnabled, "cascade", 20, 8, 0.5, 60),
                new RagProperties.Ollama("mxbai-embed-large", "qwen2.5:1.5b", "http://localhost:11434"),
                new RagProperties.Cache(5, 24, 60));
        return new HydeQueryTransformer(mockBuilder, properties);
    }

    @Test
    void shouldTransformQueryWithHypotheticalAnswer() {
        var transformer = createTransformer(true,
                "The UserService class handles authentication via JWT tokens.");

        String result = transformer.transform("How does authentication work?");
        assertEquals("The UserService class handles authentication via JWT tokens.", result);
    }

    @Test
    void shouldReturnOriginalQueryOnError() {
        ChatClient.Builder mockBuilder = mock(ChatClient.Builder.class);
        when(mockBuilder.build()).thenThrow(new RuntimeException("LLM unavailable"));

        var properties = new RagProperties(true,
                new RagProperties.Ingestion(512, 100, true, 500, List.of("java"), false),
                new RagProperties.Search(true, true, "cascade", 20, 8, 0.5, 60),
                new RagProperties.Ollama("mxbai-embed-large", "qwen2.5:1.5b", "http://localhost:11434"),
                new RagProperties.Cache(5, 24, 60));
        var transformer = new HydeQueryTransformer(mockBuilder, properties);

        String result = transformer.transform("test query");
        assertEquals("test query", result);
    }

    @Test
    void shouldPassThroughWhenDisabled() {
        var transformer = createTransformer(false, "should not be called");

        String result = transformer.transform("my query");
        assertEquals("my query", result);
    }

    @Test
    void shouldHandleEmptyQuery() {
        var transformer = createTransformer(true, "response");

        assertNull(transformer.transform(null));
        assertEquals("", transformer.transform(""));
    }
}
