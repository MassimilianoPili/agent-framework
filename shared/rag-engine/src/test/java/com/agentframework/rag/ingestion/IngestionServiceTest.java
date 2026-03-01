package com.agentframework.rag.ingestion;

import com.agentframework.rag.model.IngestionReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IngestionServiceTest {

    private IngestionService service;
    private CodeDocumentReader mockReader;
    private IngestionPipeline mockPipeline;

    @BeforeEach
    void setUp() {
        mockReader = mock(CodeDocumentReader.class);
        mockPipeline = mock(IngestionPipeline.class);
        service = new IngestionService(mockReader, mockPipeline);
    }

    @Test
    void ingestDirectory_happyPath_delegatesToPipeline() throws IOException {
        Path rootDir = Path.of("/project/src");
        List<Document> docs = List.of(new Document("file content"));
        var report = new IngestionReport(5, 12, Duration.ofSeconds(3), List.of());

        when(mockReader.readDocuments(rootDir)).thenReturn(docs);
        when(mockPipeline.ingest(docs)).thenReturn(report);

        IngestionReport result = service.ingestDirectory(rootDir);

        assertThat(result.filesProcessed()).isEqualTo(5);
        assertThat(result.chunksCreated()).isEqualTo(12);
        assertThat(result.hasErrors()).isFalse();
        verify(mockReader).readDocuments(rootDir);
        verify(mockPipeline).ingest(docs);
    }

    @Test
    void ingestDirectory_readerThrows_returnsErrorReport() throws IOException {
        when(mockReader.readDocuments(any())).thenThrow(new IOException("Permission denied"));

        IngestionReport result = service.ingestDirectory(Path.of("/forbidden"));

        assertThat(result.filesProcessed()).isZero();
        assertThat(result.chunksCreated()).isZero();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors().get(0)).contains("Permission denied");
        verify(mockPipeline, never()).ingest(any());
    }

    @Test
    void ingestFile_happyPath_delegatesToPipeline() throws IOException {
        Path file = Path.of("/project/src/Main.java");
        Path rootDir = Path.of("/project");
        var doc = new Document("public class Main {}");
        var report = new IngestionReport(1, 3, Duration.ofMillis(500), List.of());

        when(mockReader.readSingleFile(file, rootDir)).thenReturn(doc);
        when(mockPipeline.ingest(List.of(doc))).thenReturn(report);

        IngestionReport result = service.ingestFile(file, rootDir);

        assertThat(result.filesProcessed()).isEqualTo(1);
        assertThat(result.chunksCreated()).isEqualTo(3);
        verify(mockReader).readSingleFile(file, rootDir);
    }

    @Test
    void ingestFile_readerThrows_returnsErrorReport() throws IOException {
        when(mockReader.readSingleFile(any(), any())).thenThrow(new IOException("File not found"));

        IngestionReport result = service.ingestFile(Path.of("/missing.java"), Path.of("/project"));

        assertThat(result.filesProcessed()).isZero();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors().get(0)).contains("File not found");
    }

    @Test
    void ingestDirectory_emptyDirectory_returnsEmptyReport() throws IOException {
        when(mockReader.readDocuments(any())).thenReturn(List.of());
        when(mockPipeline.ingest(List.of()))
                .thenReturn(new IngestionReport(0, 0, Duration.ZERO, List.of()));

        IngestionReport result = service.ingestDirectory(Path.of("/empty"));

        assertThat(result.filesProcessed()).isZero();
        assertThat(result.chunksCreated()).isZero();
        assertThat(result.hasErrors()).isFalse();
    }
}
