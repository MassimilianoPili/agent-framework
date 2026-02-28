package com.agentframework.rag.model;

/**
 * A chunk with its relevance score and the reranker stage that produced it.
 *
 * @param chunk         the code/doc chunk
 * @param score         relevance score (0.0 to 1.0 for cosine, 0-10 for LLM)
 * @param rerankerStage which reranker stage assigned this score (e.g. "vector", "bm25", "cosine", "llm")
 */
public record ScoredChunk(
        CodeChunk chunk,
        double score,
        String rerankerStage
) {}
