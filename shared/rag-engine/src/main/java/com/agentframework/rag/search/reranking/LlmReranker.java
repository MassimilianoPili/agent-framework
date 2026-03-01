package com.agentframework.rag.search.reranking;

import com.agentframework.rag.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stage 2 reranker — LLM-based relevance scoring via a small model (e.g. qwen2.5:1.5b).
 *
 * <p>Scores each (query, chunk) pair from 0-10 using a fast model via Ollama.
 * Candidates are scored in parallel using virtual threads for ~Nx speedup.</p>
 *
 * <p>Falls back to score 5 if the LLM response is not parseable as a number.</p>
 */
public class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

    private static final String SCORING_PROMPT = """
            Rate the relevance of this code chunk to the query on a scale of 0-10.
            Answer with ONLY a single number, nothing else.

            Query: %s

            Chunk: %s""";

    private final ChatClient.Builder chatClientBuilder;
    private final ExecutorService executor;

    public LlmReranker(ChatClient.Builder chatClientBuilder, ExecutorService executor) {
        this.chatClientBuilder = chatClientBuilder;
        this.executor = executor;
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        // Fan-out: score all candidates in parallel via virtual threads
        List<CompletableFuture<ScoredChunk>> futures = candidates.stream()
                .map(sc -> CompletableFuture.supplyAsync(
                        () -> scoreSingle(query, sc), executor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[RAG Reranker] LLM scoring timed out: {}", e.getMessage());
        }

        List<ScoredChunk> scored = futures.stream()
                .map(f -> f.getNow(null))
                .filter(sc -> sc != null)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .toList();

        log.debug("[RAG Reranker] LLM scored {} candidates → top {} (best={})",
                candidates.size(), scored.size(),
                scored.isEmpty() ? 0.0 : scored.getFirst().score());
        return scored;
    }

    private ScoredChunk scoreSingle(String query, ScoredChunk candidate) {
        try {
            String prompt = String.format(SCORING_PROMPT, query,
                    truncate(candidate.chunk().enrichedContent(), 500));
            String response = chatClientBuilder.build()
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();
            double score = parseScore(response);
            return new ScoredChunk(candidate.chunk(), score, "llm");
        } catch (Exception e) {
            log.warn("[RAG Reranker] LLM scoring failed for chunk {}: {}",
                    candidate.chunk().metadata().filePath(), e.getMessage());
            return new ScoredChunk(candidate.chunk(), 5.0, "llm-fallback");
        }
    }

    static double parseScore(String response) {
        if (response == null || response.isBlank()) {
            return 5.0;
        }
        try {
            // Extract first number from response
            String cleaned = response.strip().replaceAll("[^0-9.]", "");
            if (cleaned.isEmpty()) {
                return 5.0;
            }
            double score = Double.parseDouble(cleaned);
            return Math.max(0.0, Math.min(10.0, score));
        } catch (NumberFormatException e) {
            return 5.0;
        }
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }
}
