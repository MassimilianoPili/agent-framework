package com.agentframework.rag.ingestion;

import com.agentframework.rag.ingestion.chunking.ChunkingStrategy;
import com.agentframework.rag.ingestion.enrichment.ContextualEnricher;
import com.agentframework.rag.ingestion.enrichment.MetadataEnricher;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.IngestionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 5-phase ingestion pipeline: extract → chunk → enrich → embed → index.
 *
 * <p>Phases:</p>
 * <ol>
 *   <li><b>Extract</b>: CodeDocumentReader scans files, produces Spring AI Documents</li>
 *   <li><b>Chunk</b>: ChunkingStrategy splits documents (code or doc chunker)</li>
 *   <li><b>Enrich</b>: ContextualEnricher adds context prefix + MetadataEnricher adds entities</li>
 *   <li><b>Embed</b>: VectorStore.add() calls EmbeddingModel internally</li>
 *   <li><b>Index</b>: pgvector stores vectors + metadata (HNSW + GIN indexes)</li>
 * </ol>
 */
@Component
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final List<ChunkingStrategy> chunkingStrategies;
    private final ContextualEnricher contextualEnricher;
    private final MetadataEnricher metadataEnricher;
    private final VectorStore vectorStore;

    public IngestionPipeline(List<ChunkingStrategy> chunkingStrategies,
                             ContextualEnricher contextualEnricher,
                             MetadataEnricher metadataEnricher,
                             VectorStore vectorStore) {
        this.chunkingStrategies = chunkingStrategies;
        this.contextualEnricher = contextualEnricher;
        this.metadataEnricher = metadataEnricher;
        this.vectorStore = vectorStore;
    }

    /**
     * Execute the full ingestion pipeline on a list of documents.
     *
     * @param documents the source documents from CodeDocumentReader
     * @return report with stats and errors
     */
    public IngestionReport ingest(List<Document> documents) {
        Instant start = Instant.now();
        var errors = new ArrayList<String>();
        int totalChunks = 0;

        log.info("[RAG Ingestion] Starting pipeline for {} documents", documents.size());

        for (Document doc : documents) {
            try {
                String filePath = (String) doc.getMetadata().get("filePath");
                String language = (String) doc.getMetadata().get("language");
                String content = doc.getText();

                // Phase 2: Chunk
                List<CodeChunk> chunks = chunkDocument(content, filePath, language);
                if (chunks.isEmpty()) continue;

                // Phase 3a: Contextual enrichment (prepend context summary)
                chunks = contextualEnricher.enrich(chunks, content);

                // Phase 3b: Metadata enrichment (entities, keyphrases, docType)
                chunks = metadataEnricher.enrich(chunks);

                // Phase 4+5: Embed + Index (VectorStore handles both)
                List<Document> vectorDocs = toVectorDocuments(chunks);
                vectorStore.add(vectorDocs);

                totalChunks += chunks.size();
                log.debug("[RAG Ingestion] {} → {} chunks indexed", filePath, chunks.size());
            } catch (Exception e) {
                String filePath = (String) doc.getMetadata().getOrDefault("filePath", "unknown");
                errors.add(filePath + ": " + e.getMessage());
                log.warn("[RAG Ingestion] Error processing {}: {}", filePath, e.getMessage());
            }
        }

        Duration duration = Duration.between(start, Instant.now());
        log.info("[RAG Ingestion] Pipeline completed: {} files, {} chunks, {}ms, {} errors",
                documents.size(), totalChunks, duration.toMillis(), errors.size());

        return new IngestionReport(documents.size(), totalChunks, duration, errors);
    }

    private List<CodeChunk> chunkDocument(String content, String filePath, String language) {
        String extension = extractExtension(filePath);

        for (ChunkingStrategy strategy : chunkingStrategies) {
            if (strategy.supports(extension)) {
                return strategy.chunk(content, filePath, language);
            }
        }

        // Fallback: treat as documentation
        log.debug("[RAG Ingestion] No chunker for extension '{}', falling back to PropositionChunker", extension);
        for (ChunkingStrategy strategy : chunkingStrategies) {
            if (strategy.supports("md")) {
                return strategy.chunk(content, filePath, language);
            }
        }
        return List.of();
    }

    private List<Document> toVectorDocuments(List<CodeChunk> chunks) {
        return chunks.stream()
                .map(chunk -> {
                    // Use enriched content (with contextual prefix) for embedding
                    String textToEmbed = chunk.enrichedContent();
                    var meta = chunk.metadata();
                    return new Document(textToEmbed, Map.of(
                            "filePath", meta.filePath(),
                            "language", meta.language(),
                            "sectionTitle", meta.sectionTitle() != null ? meta.sectionTitle() : "",
                            "docType", meta.docType().name(),
                            "entities", String.join(",", meta.entities()),
                            "keyphrases", String.join(",", meta.keyphrases())
                    ));
                })
                .toList();
    }

    static String extractExtension(String filePath) {
        if (filePath.toLowerCase().endsWith("dockerfile")) return "dockerfile";
        int dot = filePath.lastIndexOf('.');
        return dot > 0 ? filePath.substring(dot + 1).toLowerCase() : "";
    }
}
