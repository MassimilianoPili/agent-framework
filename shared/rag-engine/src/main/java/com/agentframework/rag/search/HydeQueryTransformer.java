package com.agentframework.rag.search;

import com.agentframework.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Hypothetical Document Embeddings (HyDE) query transformer.
 *
 * <p>Generates a hypothetical answer to the query via LLM, then uses the answer
 * as the search query. The hypothetical answer's embedding is closer to relevant
 * documents than the original question's embedding (Gao et al., 2022).</p>
 *
 * <p>Falls back to the original query if the LLM call fails or HyDE is disabled.</p>
 */
@Service
public class HydeQueryTransformer {

    private static final Logger log = LoggerFactory.getLogger(HydeQueryTransformer.class);

    private static final String HYDE_PROMPT = """
            Given this question about source code, generate a hypothetical answer
            of 3-5 sentences as if you were answering directly.
            Do not say "I don't know". Answer as if you know the code.

            Question: %s""";

    private final ChatClient.Builder chatClientBuilder;
    private final boolean enabled;

    public HydeQueryTransformer(ChatClient.Builder chatClientBuilder, RagProperties properties) {
        this.chatClientBuilder = chatClientBuilder;
        this.enabled = properties.search().hydeEnabled();
    }

    /**
     * Transform a query using HyDE — generate a hypothetical answer and return it.
     *
     * @param query the original user query
     * @return the hypothetical answer (for embedding), or the original query on failure
     */
    public String transform(String query) {
        if (!enabled || query == null || query.isBlank()) {
            return query;
        }

        try {
            String prompt = String.format(HYDE_PROMPT, query);
            String hypothetical = chatClientBuilder.build()
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();
            log.debug("[RAG HyDE] Transformed query: '{}' → '{}...'",
                    query, truncate(hypothetical, 80));
            return hypothetical;
        } catch (Exception e) {
            log.warn("[RAG HyDE] Failed to generate hypothetical answer: {}", e.getMessage());
            return query; // Fallback to original
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private static String truncate(String text, int maxLen) {
        return text == null ? "" : (text.length() <= maxLen ? text : text.substring(0, maxLen));
    }
}
