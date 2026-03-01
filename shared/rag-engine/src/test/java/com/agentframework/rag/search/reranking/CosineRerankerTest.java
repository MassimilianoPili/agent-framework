package com.agentframework.rag.search.reranking;

import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import com.agentframework.rag.model.ScoredChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CosineRerankerTest {

    private CosineReranker reranker;

    @BeforeEach
    void setUp() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        // Return simple vectors for testing cosine similarity
        when(mockModel.embed(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            if (text.contains("relevant")) return new float[]{1.0f, 0.0f, 0.0f};
            if (text.contains("semi")) return new float[]{0.7f, 0.7f, 0.0f};
            return new float[]{0.0f, 0.0f, 1.0f}; // irrelevant
        });
        reranker = new CosineReranker(mockModel);
    }

    @Test
    void shouldRescoreByCosineAndSort() {
        var relevant = new ScoredChunk(
                new CodeChunk("relevant code", null, new ChunkMetadata("A.java", "java")),
                0.5, "vector");
        var irrelevant = new ScoredChunk(
                new CodeChunk("unrelated code", null, new ChunkMetadata("B.java", "java")),
                0.9, "vector");
        var semi = new ScoredChunk(
                new CodeChunk("semi relevant", null, new ChunkMetadata("C.java", "java")),
                0.7, "vector");

        List<ScoredChunk> result = reranker.rerank("relevant query", List.of(irrelevant, semi, relevant), 3);

        assertEquals(3, result.size());
        assertEquals("cosine", result.getFirst().rerankerStage());
        // "relevant code" should have highest cosine to "relevant query"
        assertTrue(result.getFirst().chunk().content().contains("relevant"));
    }

    @Test
    void shouldRespectTopKLimit() {
        var chunk1 = new ScoredChunk(
                new CodeChunk("relevant A", null, new ChunkMetadata("A.java", "java")),
                0.9, "vector");
        var chunk2 = new ScoredChunk(
                new CodeChunk("relevant B", null, new ChunkMetadata("B.java", "java")),
                0.8, "vector");

        List<ScoredChunk> result = reranker.rerank("relevant query", List.of(chunk1, chunk2), 1);
        assertEquals(1, result.size());
    }

    @Test
    void shouldHandleEmptyCandidates() {
        List<ScoredChunk> result = reranker.rerank("query", List.of(), 5);
        assertTrue(result.isEmpty());
    }
}
