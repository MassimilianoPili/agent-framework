package com.agentframework.rag.ingestion;

import com.agentframework.rag.ingestion.chunking.RecursiveCodeChunker;
import com.agentframework.rag.ingestion.enrichment.ContextualEnricher;
import com.agentframework.rag.ingestion.enrichment.MetadataEnricher;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.IngestionReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestionPipelineTest {

    private IngestionPipeline pipeline;
    private VectorStore mockVectorStore;
    private ContextualEnricher mockEnricher;
    private RecursiveCodeChunker mockStrategy;

    @BeforeEach
    void setUp() {
        mockVectorStore = mock(VectorStore.class);
        mockEnricher = mock(ContextualEnricher.class);

        // Use concrete class (sealed interface cannot be mocked)
        mockStrategy = mock(RecursiveCodeChunker.class);
        when(mockStrategy.supports("java")).thenReturn(true);
        when(mockStrategy.supports("md")).thenReturn(true);
        when(mockStrategy.chunk(anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        new CodeChunk("chunk content", null,
                                new ChunkMetadata("Test.java", "java"))
                ));

        when(mockEnricher.enrich(anyList(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        var metadataEnricher = new MetadataEnricher();
        pipeline = new IngestionPipeline(
                List.of(mockStrategy), mockEnricher, metadataEnricher, mockVectorStore);
    }

    @Test
    void shouldIngestDocumentsSuccessfully() {
        var doc = new Document("public class Test {}", Map.of(
                "filePath", "Test.java", "language", "java"));

        IngestionReport report = pipeline.ingest(List.of(doc));

        assertEquals(1, report.filesProcessed());
        assertEquals(1, report.chunksCreated());
        assertFalse(report.hasErrors());
        verify(mockVectorStore, times(1)).add(anyList());
    }

    @Test
    void shouldHandleEmptyDocumentList() {
        IngestionReport report = pipeline.ingest(List.of());
        assertEquals(0, report.filesProcessed());
        assertEquals(0, report.chunksCreated());
    }

    @Test
    void shouldCatchAndRecordErrors() {
        when(mockStrategy.chunk(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Chunking failed"));

        var doc = new Document("bad content", Map.of(
                "filePath", "Bad.java", "language", "java"));

        IngestionReport report = pipeline.ingest(List.of(doc));
        assertTrue(report.hasErrors());
        assertTrue(report.errors().get(0).contains("Bad.java"));
    }

    @Test
    void shouldCallContextualEnricher() {
        var doc = new Document("code here", Map.of(
                "filePath", "App.java", "language", "java"));

        pipeline.ingest(List.of(doc));
        verify(mockEnricher, times(1)).enrich(anyList(), eq("code here"));
    }

    @Test
    void shouldExtractExtensionFromFilePath() {
        assertEquals("java", IngestionPipeline.extractExtension("src/main/App.java"));
        assertEquals("yml", IngestionPipeline.extractExtension("config/app.yml"));
        assertEquals("dockerfile", IngestionPipeline.extractExtension("docker/Dockerfile"));
        assertEquals("", IngestionPipeline.extractExtension("Makefile"));
    }

    @Test
    void shouldProcessMultipleDocuments() {
        var doc1 = new Document("class A {}", Map.of("filePath", "A.java", "language", "java"));
        var doc2 = new Document("class B {}", Map.of("filePath", "B.java", "language", "java"));

        IngestionReport report = pipeline.ingest(List.of(doc1, doc2));
        assertEquals(2, report.filesProcessed());
        assertEquals(2, report.chunksCreated());
    }
}
