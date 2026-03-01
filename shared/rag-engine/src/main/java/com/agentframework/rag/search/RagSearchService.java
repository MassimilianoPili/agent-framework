package com.agentframework.rag.search;

import com.agentframework.rag.config.RagProperties;
import com.agentframework.rag.model.ScoredChunk;
import com.agentframework.rag.model.SearchFilters;
import com.agentframework.rag.model.SearchResult;
import com.agentframework.rag.search.reranking.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level RAG search orchestrator: query → [HyDE] → hybrid search → cascade rerank → results.
 *
 * <p>Composes the full search pipeline with configurable stages:
 * <ul>
 *   <li>HyDE (optional): generates a hypothetical answer for better embedding similarity</li>
 *   <li>Hybrid search: vector + BM25 with RRF fusion</li>
 *   <li>Cascade rerank: cosine filtering → LLM scoring</li>
 * </ul>
 * </p>
 */
@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    private final HybridSearchService hybridSearchService;
    private final HydeQueryTransformer hydeTransformer;
    private final Reranker reranker;
    private final RagProperties properties;

    public RagSearchService(HybridSearchService hybridSearchService,
                            HydeQueryTransformer hydeTransformer,
                            Reranker reranker,
                            RagProperties properties) {
        this.hybridSearchService = hybridSearchService;
        this.hydeTransformer = hydeTransformer;
        this.reranker = reranker;
        this.properties = properties;
    }

    /**
     * Execute the full search pipeline.
     *
     * @param query   the user query
     * @param filters search filters (language, filePath, docType, maxResults)
     * @return search result with ranked chunks and metadata
     */
    public SearchResult search(String query, SearchFilters filters) {
        if (query == null || query.isBlank()) {
            return new SearchResult(List.of(), searchMode(), 0);
        }

        long start = System.currentTimeMillis();

        // 1. HyDE transform (optional)
        String searchQuery = hydeTransformer.isEnabled()
                ? hydeTransformer.transform(query)
                : query;

        // 2. Hybrid search (vector + BM25 in parallel, RRF fusion)
        List<ScoredChunk> candidates = hybridSearchService.search(searchQuery, filters);
        int totalCandidates = candidates.size();

        // 3. Rerank (cascade: cosine → LLM, or configured variant)
        int maxResults = filters != null ? filters.maxResults() : properties.search().finalK();
        List<ScoredChunk> reranked = reranker.rerank(query, candidates, maxResults);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[RAG Search] Pipeline completed: query='{}', candidates={}, results={}, mode={}, {}ms",
                truncate(query, 50), totalCandidates, reranked.size(), searchMode(), elapsed);

        return new SearchResult(reranked, searchMode(), totalCandidates);
    }

    /**
     * Convenience method with default filters.
     */
    public SearchResult search(String query) {
        return search(query, SearchFilters.defaults());
    }

    String searchMode() {
        List<String> modes = new ArrayList<>();
        if (properties.search().hybridEnabled()) {
            modes.add("hybrid");
        } else {
            modes.add("vector");
        }
        if (hydeTransformer.isEnabled()) {
            modes.add("hyde");
        }
        modes.add(properties.search().rerankerType());
        return String.join("+", modes);
    }

    private static String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
