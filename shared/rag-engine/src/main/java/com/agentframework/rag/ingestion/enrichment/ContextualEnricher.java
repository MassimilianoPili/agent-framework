package com.agentframework.rag.ingestion.enrichment;

import com.agentframework.rag.config.RagProperties;
import com.agentframework.rag.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Contextual Retrieval enricher (Anthropic pattern).
 *
 * <p>For each chunk, generates a 50-100 token context summary from the full document
 * and prepends it to the chunk content before embedding. This reduces retrieval
 * failure rate by ~35% (Anthropic benchmark).</p>
 *
 * <p>Uses virtual threads (Java 21) via {@code ragParallelExecutor} for parallel
 * LLM calls — each chunk is enriched independently, achieving ~Nx speedup
 * where N = number of chunks (up to carrier thread pool capacity).</p>
 *
 * <p>Falls back gracefully if the ChatClient call fails — returns the chunk unchanged.</p>
 */
@Component
@ConditionalOnProperty(prefix = "rag.ingestion", name = "contextual-enrichment", havingValue = "true", matchIfMissing = true)
public class ContextualEnricher {

    private static final Logger log = LoggerFactory.getLogger(ContextualEnricher.class);

    private static final String CONTEXT_PROMPT = """
            Here is the full document:
            <document>
            %s
            </document>

            Here is a chunk from that document:
            <chunk>
            %s
            </chunk>

            Give a short succinct context (50-100 tokens) to situate this chunk within the overall document.
            Focus on what this chunk is about and how it relates to the rest of the document.
            Answer only with the context, nothing else.""";

    private final ChatClient.Builder chatClientBuilder;
    private final boolean enabled;
    private final ExecutorService executor;

    public ContextualEnricher(ChatClient.Builder chatClientBuilder,
                              RagProperties properties,
                              @Qualifier("ragParallelExecutor") ExecutorService ragParallelExecutor) {
        this.chatClientBuilder = chatClientBuilder;
        this.enabled = properties.ingestion().contextualEnrichment();
        this.executor = ragParallelExecutor;
    }

    /**
     * Enrich chunks with contextual prefixes generated from the full document.
     * Chunks are enriched in parallel using virtual threads.
     *
     * @param chunks       the chunks to enrich
     * @param fullDocument the complete document text (for context generation)
     * @return enriched chunks with contextPrefix set
     */
    public List<CodeChunk> enrich(List<CodeChunk> chunks, String fullDocument) {
        if (!enabled || chunks.isEmpty()) {
            return chunks;
        }

        String truncatedDoc = truncateIfNeeded(fullDocument, 8000);

        // Fan-out: each chunk enriched in parallel via virtual threads
        List<CompletableFuture<CodeChunk>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(
                        () -> enrichSingle(chunk, truncatedDoc), executor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[RAG Enricher] Parallel enrichment timed out or was interrupted: {}", e.getMessage());
        }

        List<CodeChunk> enriched = futures.stream()
                .map(f -> f.getNow(null))
                .filter(c -> c != null)
                .toList();

        log.debug("[RAG Enricher] Enriched {}/{} chunks with contextual prefix",
                enriched.stream().filter(c -> c.contextPrefix() != null).count(), chunks.size());
        return enriched;
    }

    private CodeChunk enrichSingle(CodeChunk chunk, String truncatedDoc) {
        try {
            String context = generateContext(truncatedDoc, chunk.content());
            return new CodeChunk(chunk.content(), context, chunk.metadata());
        } catch (Exception e) {
            log.warn("[RAG Enricher] Failed to generate context for chunk in {}: {}",
                    chunk.metadata().filePath(), e.getMessage());
            return chunk;
        }
    }

    private String generateContext(String document, String chunkContent) {
        String prompt = String.format(CONTEXT_PROMPT, document, chunkContent);
        return chatClientBuilder.build()
                .prompt()
                .user(prompt)
                .call()
                .content();
    }

    static String truncateIfNeeded(String text, int maxTokens) {
        int maxChars = maxTokens * 4; // ~4 chars per token
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... [truncated]";
    }
}
