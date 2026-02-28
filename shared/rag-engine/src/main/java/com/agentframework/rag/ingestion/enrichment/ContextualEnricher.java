package com.agentframework.rag.ingestion.enrichment;

import com.agentframework.rag.config.RagProperties;
import com.agentframework.rag.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Contextual Retrieval enricher (Anthropic pattern).
 *
 * <p>For each chunk, generates a 50-100 token context summary from the full document
 * and prepends it to the chunk content before embedding. This reduces retrieval
 * failure rate by ~35% (Anthropic benchmark).</p>
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

    public ContextualEnricher(ChatClient.Builder chatClientBuilder, RagProperties properties) {
        this.chatClientBuilder = chatClientBuilder;
        this.enabled = properties.ingestion().contextualEnrichment();
    }

    /**
     * Enrich chunks with contextual prefixes generated from the full document.
     *
     * @param chunks       the chunks to enrich
     * @param fullDocument the complete document text (for context generation)
     * @return enriched chunks with contextPrefix set
     */
    public List<CodeChunk> enrich(List<CodeChunk> chunks, String fullDocument) {
        if (!enabled || chunks.isEmpty()) {
            return chunks;
        }

        // Truncate document if too long (avoid exceeding context window)
        String truncatedDoc = truncateIfNeeded(fullDocument, 8000);
        var enriched = new ArrayList<CodeChunk>(chunks.size());

        for (CodeChunk chunk : chunks) {
            try {
                String context = generateContext(truncatedDoc, chunk.content());
                enriched.add(new CodeChunk(chunk.content(), context, chunk.metadata()));
            } catch (Exception e) {
                log.warn("[RAG Enricher] Failed to generate context for chunk in {}: {}",
                        chunk.metadata().filePath(), e.getMessage());
                enriched.add(chunk); // Keep original chunk without context
            }
        }

        log.debug("[RAG Enricher] Enriched {}/{} chunks with contextual prefix",
                enriched.stream().filter(c -> c.contextPrefix() != null).count(), chunks.size());
        return enriched;
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
