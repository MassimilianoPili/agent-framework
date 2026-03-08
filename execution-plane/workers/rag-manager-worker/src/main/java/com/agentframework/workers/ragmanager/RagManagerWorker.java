package com.agentframework.workers.ragmanager;

import com.agentframework.rag.graph.GraphRagService;
import com.agentframework.rag.model.ScoredChunk;
import com.agentframework.rag.model.SearchFilters;
import com.agentframework.rag.model.SearchResult;
import com.agentframework.rag.search.RagSearchService;
import com.agentframework.worker.AbstractWorker;
import com.agentframework.worker.WorkerExecutionException;
import com.agentframework.worker.WorkerMetadata;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.interceptor.WorkerInterceptor;
import com.agentframework.worker.messaging.WorkerResultProducer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG Manager Worker — retrieves semantic context from the RAG pipeline.
 *
 * <p>This is a <b>programmatic</b> worker: it does not use the LLM (ChatClient).
 * Instead, it queries {@link RagSearchService} for semantic search results and
 * {@link GraphRagService} for structural insights from the knowledge and code graphs.</p>
 *
 * <p>The result is a structured JSON object published as a dependency result,
 * consumed by downstream domain workers (BE, FE, AI_TASK) for context enrichment.</p>
 *
 * <p>Dispatched as any other worker via messaging. Typically runs as a dependency
 * alongside CONTEXT_MANAGER before domain workers execute.</p>
 */
@Component
@WorkerMetadata(
    workerType = "RAG_MANAGER",
    systemPromptFile = "prompts/rag-manager.agent.md"
)
public class RagManagerWorker extends AbstractWorker {

    private static final Logger log = LoggerFactory.getLogger(RagManagerWorker.class);
    private static final int MAX_SEARCH_RESULTS = 10;

    private final RagSearchService ragSearchService;
    private final GraphRagService graphRagService;
    private final ObjectMapper objectMapper;

    public RagManagerWorker(AgentContextBuilder contextBuilder,
                            WorkerChatClientFactory chatClientFactory,
                            WorkerResultProducer resultProducer,
                            List<WorkerInterceptor> interceptors,
                            RagSearchService ragSearchService,
                            GraphRagService graphRagService,
                            ObjectMapper objectMapper) {
        super(contextBuilder, chatClientFactory, resultProducer, interceptors);
        this.ragSearchService = ragSearchService;
        this.graphRagService = graphRagService;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes the RAG search pipeline and assembles the result.
     *
     * <p>The ChatClient parameter is ignored — this worker does not use the LLM.
     * It calls RagSearchService and GraphRagService directly.</p>
     */
    @Override
    protected String execute(AgentContext context, ChatClient chatClient)
            throws WorkerExecutionException {

        String query = buildSearchQuery(context);
        log.info("[RAG_MANAGER] Searching for task '{}', query length: {} chars",
                context.taskKey(), query.length());

        try {
            // Vector + BM25 hybrid search with cascade reranking
            SearchResult searchResult = ragSearchService.search(query,
                    new SearchFilters(null, null, null, MAX_SEARCH_RESULTS));

            // Graph structural insights (parallel knowledge + code graph queries)
            List<String> graphInsights = graphRagService.findRelatedInsights(
                    extractGraphQuery(context));

            // Assemble result
            Map<String, Object> result = assembleResult(searchResult, graphInsights);
            String json = objectMapper.writeValueAsString(result);

            log.info("[RAG_MANAGER] Task '{}' completed: {} chunks, {} graph insights",
                    context.taskKey(), searchResult.chunks().size(), graphInsights.size());

            return json;

        } catch (JsonProcessingException e) {
            throw new WorkerExecutionException(
                    "RAG_MANAGER failed to serialize result for task " + context.taskKey(), e);
        } catch (Exception e) {
            throw new WorkerExecutionException(
                    "RAG_MANAGER search failed for task " + context.taskKey(), e);
        }
    }

    /**
     * Builds the search query from the task context (title + description).
     */
    String buildSearchQuery(AgentContext context) {
        StringBuilder query = new StringBuilder();
        if (context.title() != null) {
            query.append(context.title());
        }
        if (context.description() != null && !context.description().isBlank()) {
            if (!query.isEmpty()) query.append(". ");
            query.append(context.description());
        }
        return query.toString();
    }

    /**
     * Extracts a shorter query for graph keyword search (title only, first 100 chars).
     */
    String extractGraphQuery(AgentContext context) {
        String title = context.title() != null ? context.title() : "";
        return title.length() <= 100 ? title : title.substring(0, 100);
    }

    Map<String, Object> assembleResult(SearchResult searchResult, List<String> graphInsights) {
        Map<String, Object> result = new LinkedHashMap<>();

        // semantic_chunks
        List<Map<String, Object>> chunks = searchResult.chunks().stream()
                .map(this::chunkToMap)
                .toList();
        result.put("semantic_chunks", chunks);

        // graph_insights
        result.put("graph_insights", graphInsights);

        // related_files — deduplicated file paths from chunks
        List<String> relatedFiles = searchResult.chunks().stream()
                .map(sc -> sc.chunk().metadata() != null ? sc.chunk().metadata().filePath() : null)
                .filter(fp -> fp != null && !fp.isBlank())
                .distinct()
                .collect(Collectors.toList());
        result.put("related_files", relatedFiles);

        // search_metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", searchResult.searchMode());
        metadata.put("totalCandidates", searchResult.totalCandidates());
        metadata.put("resultCount", chunks.size());
        result.put("search_metadata", metadata);

        return result;
    }

    private Map<String, Object> chunkToMap(ScoredChunk sc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", sc.chunk().content());
        if (sc.chunk().metadata() != null) {
            m.put("filePath", sc.chunk().metadata().filePath());
        }
        m.put("score", sc.score());
        if (sc.chunk().contextPrefix() != null) {
            m.put("context", sc.chunk().contextPrefix());
        }
        return m;
    }
}
