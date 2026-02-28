package com.agentframework.rag.ingestion;

import com.agentframework.rag.model.IngestionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * High-level ingestion service — entry point for indexing a codebase directory.
 *
 * <p>Triggers:</p>
 * <ul>
 *   <li>Manual: via REST API or programmatic call</li>
 *   <li>Event: PlanCompletedEvent listener (Sessione 3)</li>
 *   <li>File watcher: incremental re-ingestion on file change (Sessione 3)</li>
 * </ul>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final CodeDocumentReader documentReader;
    private final IngestionPipeline pipeline;

    public IngestionService(CodeDocumentReader documentReader, IngestionPipeline pipeline) {
        this.documentReader = documentReader;
        this.pipeline = pipeline;
    }

    /**
     * Ingest an entire directory — full scan.
     *
     * @param rootDir the root directory to scan
     * @return ingestion report
     */
    public IngestionReport ingestDirectory(Path rootDir) {
        log.info("[RAG] Starting full ingestion of {}", rootDir);
        try {
            List<Document> documents = documentReader.readDocuments(rootDir);
            return pipeline.ingest(documents);
        } catch (IOException e) {
            log.error("[RAG] Failed to read directory {}: {}", rootDir, e.getMessage());
            return new IngestionReport(0, 0, java.time.Duration.ZERO,
                    List.of("Failed to read directory: " + e.getMessage()));
        }
    }

    /**
     * Re-ingest a single file (incremental update).
     *
     * @param file    the file to re-index
     * @param rootDir the project root (for relative path computation)
     * @return ingestion report
     */
    public IngestionReport ingestFile(Path file, Path rootDir) {
        log.info("[RAG] Incremental ingestion of {}", file);
        try {
            Document doc = documentReader.readSingleFile(file, rootDir);
            return pipeline.ingest(List.of(doc));
        } catch (IOException e) {
            log.warn("[RAG] Failed to read file {}: {}", file, e.getMessage());
            return new IngestionReport(0, 0, java.time.Duration.ZERO,
                    List.of("Failed to read file: " + e.getMessage()));
        }
    }
}
