package com.agentframework.workers.ragmanager;

import com.agentframework.rag.graph.GraphRagService;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.ScoredChunk;
import com.agentframework.rag.model.SearchFilters;
import com.agentframework.rag.model.SearchResult;
import com.agentframework.rag.search.RagSearchService;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.messaging.WorkerResultProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagManagerWorkerTest {

    private RagManagerWorker worker;
    private RagSearchService mockSearch;
    private GraphRagService mockGraph;
    private ChatClient mockChatClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockSearch = mock(RagSearchService.class);
        mockGraph = mock(GraphRagService.class);
        mockChatClient = mock(ChatClient.class);
        objectMapper = new ObjectMapper();

        worker = new RagManagerWorker(
                mock(AgentContextBuilder.class),
                mock(WorkerChatClientFactory.class),
                mock(WorkerResultProducer.class),
                List.of(),
                mockSearch,
                mockGraph,
                objectMapper
        );
    }

    @Test
    void workerType_returnsRagManager() {
        assertThat(worker.workerType()).isEqualTo("RAG_MANAGER");
    }

    @Test
    void execute_happyPath_returnsStructuredJson() throws Exception {
        var chunk = new ScoredChunk(
                new CodeChunk("public class OrderService {}", "Service layer for orders",
                        new ChunkMetadata("src/OrderService.java", "java", "OrderService",
                                List.of("OrderService"), ChunkMetadata.DocType.CODE, List.of("order", "service"))),
                0.91, "cosine");
        when(mockSearch.search(anyString(), any(SearchFilters.class)))
                .thenReturn(new SearchResult(List.of(chunk), "hybrid+hyde+cascade", 20));
        when(mockGraph.findRelatedInsights(anyString()))
                .thenReturn(List.of("Found 3 related code entities: OrderService, OrderRepository, Order"));

        AgentContext context = contextWith("Implement order processing", "Build the order service CRUD");

        String json = worker.execute(context, mockChatClient);
        Map<String, Object> result = objectMapper.readValue(json, Map.class);

        assertThat(result).containsKeys("semantic_chunks", "graph_insights", "related_files", "search_metadata");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("semantic_chunks");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).get("content")).isEqualTo("public class OrderService {}");
        assertThat(chunks.get(0).get("filePath")).isEqualTo("src/OrderService.java");
        assertThat((double) chunks.get(0).get("score")).isEqualTo(0.91);
        assertThat(chunks.get(0).get("context")).isEqualTo("Service layer for orders");

        @SuppressWarnings("unchecked")
        List<String> insights = (List<String>) result.get("graph_insights");
        assertThat(insights).hasSize(1).first().asString().contains("OrderService");

        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) result.get("related_files");
        assertThat(files).containsExactly("src/OrderService.java");

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) result.get("search_metadata");
        assertThat(metadata.get("mode")).isEqualTo("hybrid+hyde+cascade");
        assertThat(metadata.get("totalCandidates")).isEqualTo(20);
    }

    @Test
    void execute_noResults_returnsEmptyStructure() throws Exception {
        when(mockSearch.search(anyString(), any(SearchFilters.class)))
                .thenReturn(new SearchResult(List.of(), "hybrid", 0));
        when(mockGraph.findRelatedInsights(anyString())).thenReturn(List.of());

        AgentContext context = contextWith("Unknown topic", "Nothing to find");

        String json = worker.execute(context, mockChatClient);
        Map<String, Object> result = objectMapper.readValue(json, Map.class);

        assertThat((List<?>) result.get("semantic_chunks")).isEmpty();
        assertThat((List<?>) result.get("graph_insights")).isEmpty();
        assertThat((List<?>) result.get("related_files")).isEmpty();
    }

    @Test
    void execute_searchThrows_wrapsInWorkerExecutionException() {
        when(mockSearch.search(anyString(), any()))
                .thenThrow(new RuntimeException("Database connection lost"));

        AgentContext context = contextWith("Some task", "description");

        assertThatThrownBy(() -> worker.execute(context, mockChatClient))
                .isInstanceOf(com.agentframework.worker.WorkerExecutionException.class)
                .hasMessageContaining("RAG_MANAGER search failed");
    }

    @Test
    void buildSearchQuery_combinesTitleAndDescription() {
        AgentContext context = contextWith("Implement auth", "JWT-based authentication");
        String query = worker.buildSearchQuery(context);
        assertThat(query).isEqualTo("Implement auth. JWT-based authentication");
    }

    @Test
    void buildSearchQuery_titleOnly_noDescription() {
        AgentContext context = contextWith("Fix bug", null);
        String query = worker.buildSearchQuery(context);
        assertThat(query).isEqualTo("Fix bug");
    }

    @Test
    void extractGraphQuery_truncatesLongTitle() {
        AgentContext context = contextWith("A".repeat(150), null);
        String graphQuery = worker.extractGraphQuery(context);
        assertThat(graphQuery).hasSize(100);
    }

    @Test
    void assembleResult_deduplicatesRelatedFiles() {
        var chunk1 = new ScoredChunk(
                new CodeChunk("code1", null, new ChunkMetadata("src/A.java", "java")), 0.9, "cosine");
        var chunk2 = new ScoredChunk(
                new CodeChunk("code2", null, new ChunkMetadata("src/A.java", "java")), 0.8, "cosine");
        var chunk3 = new ScoredChunk(
                new CodeChunk("code3", null, new ChunkMetadata("src/B.java", "java")), 0.7, "rrf");
        var searchResult = new SearchResult(List.of(chunk1, chunk2, chunk3), "hybrid", 10);

        Map<String, Object> result = worker.assembleResult(searchResult, List.of());

        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) result.get("related_files");
        assertThat(files).containsExactly("src/A.java", "src/B.java");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static AgentContext contextWith(String title, String description) {
        return new AgentContext(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "RAG-001", title, description, null, "system prompt",
                Map.of(), null, null, null, null
        );
    }
}
