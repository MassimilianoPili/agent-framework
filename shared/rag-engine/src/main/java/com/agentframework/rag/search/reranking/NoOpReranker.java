package com.agentframework.rag.search.reranking;

import com.agentframework.rag.model.ScoredChunk;

import java.util.List;

/**
 * No-op reranker — returns candidates unchanged. Useful for dev/test.
 */
public class NoOpReranker implements Reranker {

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }
}
