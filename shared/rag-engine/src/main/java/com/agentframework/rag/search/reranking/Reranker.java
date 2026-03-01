package com.agentframework.rag.search.reranking;

import com.agentframework.rag.model.ScoredChunk;

import java.util.List;

/**
 * Reranker interface — re-scores and re-orders search candidates for relevance.
 * Implementations range from fast in-memory (cosine) to precise LLM-based scoring.
 */
public interface Reranker {

    /**
     * Rerank candidates by relevance to the query.
     *
     * @param query      the original user query
     * @param candidates search candidates to rerank
     * @param topK       maximum results to return
     * @return reranked and truncated list of scored chunks
     */
    List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK);
}
