package com.agentframework.rag.model;

import java.util.List;

/**
 * Result of a RAG search operation.
 *
 * @param chunks         ranked list of scored chunks
 * @param searchMode     description of the search pipeline used (e.g. "hybrid+hyde+cascade")
 * @param totalCandidates total candidates before reranking
 * @param graphInsights  insights from graph traversal (nullable, populated in Session 2)
 */
public record SearchResult(
        List<ScoredChunk> chunks,
        String searchMode,
        int totalCandidates,
        List<String> graphInsights
) {
    public SearchResult(List<ScoredChunk> chunks, String searchMode, int totalCandidates) {
        this(chunks, searchMode, totalCandidates, List.of());
    }
}
