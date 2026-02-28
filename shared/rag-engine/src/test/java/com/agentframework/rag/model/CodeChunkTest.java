package com.agentframework.rag.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeChunkTest {

    @Test
    void shouldReturnContentWhenNoPrefix() {
        var chunk = new CodeChunk("some code", null, new ChunkMetadata("A.java", "java"));
        assertEquals("some code", chunk.enrichedContent());
    }

    @Test
    void shouldPrependContextPrefix() {
        var chunk = new CodeChunk("some code", "This is context", new ChunkMetadata("A.java", "java"));
        String enriched = chunk.enrichedContent();
        assertTrue(enriched.startsWith("This is context"));
        assertTrue(enriched.contains("some code"));
    }

    @Test
    void shouldIgnoreBlankPrefix() {
        var chunk = new CodeChunk("some code", "  ", new ChunkMetadata("A.java", "java"));
        assertEquals("some code", chunk.enrichedContent());
    }

    @Test
    void shouldCreateChunkMetadataWithMinimalConstructor() {
        var meta = new ChunkMetadata("App.java", "java");
        assertNull(meta.sectionTitle());
        assertTrue(meta.entities().isEmpty());
        assertEquals(ChunkMetadata.DocType.OTHER, meta.docType());
    }

    @Test
    void shouldDetectIngestionReportErrors() {
        var report = new IngestionReport(5, 20, Duration.ofSeconds(3), List.of("error1"));
        assertTrue(report.hasErrors());

        var noErrors = new IngestionReport(5, 20, Duration.ofSeconds(3), List.of());
        assertFalse(noErrors.hasErrors());
    }

    @Test
    void shouldCreateSearchResultWithDefaults() {
        var result = new SearchResult(List.of(), "hybrid", 0);
        assertTrue(result.graphInsights().isEmpty());
    }

    @Test
    void shouldCreateDefaultSearchFilters() {
        var filters = SearchFilters.defaults();
        assertEquals(8, filters.maxResults());
        assertNull(filters.language());
        assertNull(filters.filePathPattern());
    }

    @Test
    void shouldOverrideMaxResults() {
        var filters = SearchFilters.defaults().withMaxResults(20);
        assertEquals(20, filters.maxResults());
    }
}
