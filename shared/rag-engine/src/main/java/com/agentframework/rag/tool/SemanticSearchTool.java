package com.agentframework.rag.tool;

import com.agentframework.rag.graph.GraphRagService;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.ScoredChunk;
import com.agentframework.rag.model.SearchFilters;
import com.agentframework.rag.model.SearchResult;
import com.agentframework.rag.search.RagSearchService;
import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool for semantic search over the ingested codebase.
 *
 * <p>Combines vector similarity search ({@link RagSearchService}) with graph-based
 * structural insights ({@link GraphRagService}) into a single tool callable by
 * any worker that has {@code rag-engine} in its classpath.</p>
 *
 * <p>Activated only when {@code rag.enabled=true}. Uses {@code @ReactiveTool}
 * from the {@code spring-ai-reactive-tools} library for reactive auto-discovery.</p>
 */
@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class SemanticSearchTool {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchTool.class);

    private final RagSearchService ragSearchService;
    private final GraphRagService graphRagService;

    public SemanticSearchTool(RagSearchService ragSearchService,
                              GraphRagService graphRagService) {
        this.ragSearchService = ragSearchService;
        this.graphRagService = graphRagService;
    }

    @ReactiveTool(name = "semantic_search",
            description = "Search the codebase semantically using vector embeddings and graph relationships. "
                    + "Returns ranked code/doc chunks with relevance scores and structural insights from "
                    + "the knowledge and code graphs.")
    public Mono<Map<String, Object>> search(
            @ToolParam(description = "Natural language query describing what to find") String query,
            @ToolParam(description = "Maximum number of results to return (default 8)", required = false) Integer maxResults,
            @ToolParam(description = "Filter by programming language (java, go, rs, etc.)", required = false) String language) {

        return Mono.fromCallable(() -> {
            log.debug("[SemanticSearchTool] query='{}', maxResults={}, language={}",
                    query, maxResults, language);

            // Build search filters
            SearchFilters filters = new SearchFilters(
                    language,
                    null,   // filePathPattern — not exposed to tool callers
                    null,   // docType — not exposed to tool callers
                    maxResults != null ? maxResults : 8
            );

            // Vector + BM25 hybrid search with cascade reranking
            SearchResult result = ragSearchService.search(query, filters);

            // Graph structural insights (parallel knowledge + code graph queries)
            List<String> insights = graphRagService.findRelatedInsights(query);

            return buildResponse(result, insights);
        });
    }

    Map<String, Object> buildResponse(SearchResult result, List<String> graphInsights) {
        Map<String, Object> response = new LinkedHashMap<>();

        // Chunks: simplified view for tool consumers
        List<Map<String, Object>> chunks = result.chunks().stream()
                .map(this::chunkToMap)
                .toList();
        response.put("chunks", chunks);

        // Graph insights
        response.put("graphInsights", graphInsights);

        // Metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("searchMode", result.searchMode());
        metadata.put("totalCandidates", result.totalCandidates());
        metadata.put("resultCount", chunks.size());
        response.put("metadata", metadata);

        return response;
    }

    private Map<String, Object> chunkToMap(ScoredChunk sc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", sc.chunk().content());
        m.put("score", sc.score());
        m.put("rerankerStage", sc.rerankerStage());

        ChunkMetadata meta = sc.chunk().metadata();
        if (meta != null) {
            m.put("filePath", meta.filePath());
            m.put("language", meta.language());
            if (meta.sectionTitle() != null) {
                m.put("sectionTitle", meta.sectionTitle());
            }
        }
        return m;
    }
}
