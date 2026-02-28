package com.agentframework.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = RagPropertiesTest.Config.class)
@TestPropertySource(properties = {
        "rag.enabled=true",
        "rag.ingestion.chunk-size=256",
        "rag.ingestion.chunk-overlap=50",
        "rag.ingestion.contextual-enrichment=false",
        "rag.ingestion.max-file-size-kb=100",
        "rag.ingestion.include-extensions=java,go",
        "rag.search.hybrid-enabled=false",
        "rag.search.reranker-type=none",
        "rag.search.top-k=10",
        "rag.search.final-k=5",
        "rag.ollama.embedding-model=nomic-embed-text",
        "rag.ollama.base-url=http://localhost:11434",
        "rag.cache.redis-db=7",
        "rag.cache.embedding-ttl-hours=12"
})
class RagPropertiesTest {

    @EnableConfigurationProperties(RagProperties.class)
    static class Config {}

    @Autowired
    private RagProperties properties;

    @Test
    void shouldBindEnabled() {
        assertTrue(properties.enabled());
    }

    @Test
    void shouldBindIngestionProperties() {
        var ingestion = properties.ingestion();
        assertEquals(256, ingestion.chunkSize());
        assertEquals(50, ingestion.chunkOverlap());
        assertFalse(ingestion.contextualEnrichment());
        assertEquals(100, ingestion.maxFileSizeKb());
        assertEquals(2, ingestion.includeExtensions().size());
        assertTrue(ingestion.includeExtensions().contains("java"));
        assertTrue(ingestion.includeExtensions().contains("go"));
    }

    @Test
    void shouldBindSearchProperties() {
        var search = properties.search();
        assertFalse(search.hybridEnabled());
        assertEquals("none", search.rerankerType());
        assertEquals(10, search.topK());
        assertEquals(5, search.finalK());
    }

    @Test
    void shouldBindOllamaProperties() {
        var ollama = properties.ollama();
        assertEquals("nomic-embed-text", ollama.embeddingModel());
        assertEquals("http://localhost:11434", ollama.baseUrl());
    }

    @Test
    void shouldBindCacheProperties() {
        var cache = properties.cache();
        assertEquals(7, cache.redisDb());
        assertEquals(12, cache.embeddingTtlHours());
    }
}
