package com.agentframework.rag.search.reranking;

import com.agentframework.rag.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Two-stage cascade reranker: fast cosine filtering → precise LLM scoring.
 *
 * <p>Stage 1 (CosineReranker): narrows from topK candidates to a smaller set using
 * embedding cosine similarity (~1ms, pure in-memory computation).</p>
 *
 * <p>Stage 2 (LlmReranker): scores the filtered set using a small LLM for precise
 * relevance assessment (~100ms per candidate, parallelized via virtual threads).</p>
 */
public class CascadeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(CascadeReranker.class);

    private final CosineReranker cosineReranker;
    private final LlmReranker llmReranker;
    private final int stage1TopK;

    public CascadeReranker(CosineReranker cosineReranker, LlmReranker llmReranker, int stage1TopK) {
        this.cosineReranker = cosineReranker;
        this.llmReranker = llmReranker;
        this.stage1TopK = stage1TopK;
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        // Stage 1: cosine similarity filtering (fast, ~1ms)
        List<ScoredChunk> stage1 = cosineReranker.rerank(query, candidates, stage1TopK);
        log.debug("[RAG Reranker] Cascade stage 1 (cosine): {} → {} candidates", candidates.size(), stage1.size());

        // Stage 2: LLM scoring (precise, parallel via virtual threads)
        List<ScoredChunk> stage2 = llmReranker.rerank(query, stage1, topK);
        log.debug("[RAG Reranker] Cascade stage 2 (LLM): {} → {} candidates", stage1.size(), stage2.size());

        return stage2;
    }
}
