package com.agentframework.orchestrator.council;

import com.agentframework.rag.graph.GraphRagService;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.ScoredChunk;
import com.agentframework.rag.model.SearchResult;
import com.agentframework.rag.search.RagSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouncilRagEnricherTest {

    private CouncilRagEnricher enricher;
    private RagSearchService mockSearch;
    private GraphRagService mockGraph;

    @BeforeEach
    void setUp() {
        mockSearch = mock(RagSearchService.class);
        mockGraph = mock(GraphRagService.class);
        enricher = new CouncilRagEnricher(mockSearch, mockGraph);
    }

    @Test
    void enrichSpec_withResults_appendsRagSection() {
        var chunk = new ScoredChunk(
                new CodeChunk("public class UserService {}", null,
                        new ChunkMetadata("src/UserService.java", "java")),
                0.88, "cosine");
        when(mockSearch.search(anyString(), any()))
                .thenReturn(new SearchResult(List.of(chunk), "hybrid", 10));
        when(mockGraph.findRelatedInsights(anyString()))
                .thenReturn(List.of("Found 3 related code entities: UserService, UserRepository, User"));

        String result = enricher.enrichSpec("Build a user management API");

        assertThat(result).contains("Build a user management API");
        assertThat(result).contains("## Relevant Context from RAG");
        assertThat(result).contains("src/UserService.java");
        assertThat(result).contains("0.88");
        assertThat(result).contains("### Structural Insights");
        assertThat(result).contains("UserService, UserRepository, User");
    }

    @Test
    void enrichSpec_noResults_returnsOriginalSpec() {
        when(mockSearch.search(anyString(), any()))
                .thenReturn(new SearchResult(List.of(), "hybrid", 0));
        when(mockGraph.findRelatedInsights(anyString())).thenReturn(List.of());

        String result = enricher.enrichSpec("Completely new feature");

        assertThat(result).isEqualTo("Completely new feature");
    }

    @Test
    void enrichSpec_searchThrows_returnsOriginalSpec() {
        when(mockSearch.search(anyString(), any()))
                .thenThrow(new RuntimeException("Database unavailable"));

        String result = enricher.enrichSpec("Some spec");

        assertThat(result).isEqualTo("Some spec");
    }

    @Test
    void enrichSpec_blankSpec_returnsAsIs() {
        assertThat(enricher.enrichSpec("")).isEqualTo("");
        assertThat(enricher.enrichSpec(null)).isNull();
    }

    @Test
    void extractKeywords_takesFirstLine() {
        String spec = "Implement JWT authentication\nWith refresh tokens and RBAC";
        String keywords = enricher.extractKeywords(spec);
        assertThat(keywords).isEqualTo("Implement JWT authentication");
    }

    @Test
    void extractKeywords_truncatesLongFirstLine() {
        String longSpec = "A".repeat(150);
        String keywords = enricher.extractKeywords(longSpec);
        assertThat(keywords).hasSize(100);
    }

    @Test
    void buildRagSection_onlyChunks_noInsightsSection() {
        var chunk = new ScoredChunk(
                new CodeChunk("code here", null, new ChunkMetadata("File.java", "java")),
                0.75, "rrf");

        String section = enricher.buildRagSection(List.of(chunk), List.of());

        assertThat(section).contains("### Related Code/Documentation");
        assertThat(section).doesNotContain("### Structural Insights");
    }

    @Test
    void buildRagSection_onlyInsights_noChunksSection() {
        String section = enricher.buildRagSection(List.of(),
                List.of("Found 2 related concepts"));

        assertThat(section).doesNotContain("### Related Code/Documentation");
        assertThat(section).contains("### Structural Insights");
        assertThat(section).contains("- Found 2 related concepts");
    }
}
