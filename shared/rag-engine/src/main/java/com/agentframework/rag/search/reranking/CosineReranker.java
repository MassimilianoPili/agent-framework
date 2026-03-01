package com.agentframework.rag.search.reranking;

import com.agentframework.rag.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Comparator;
import java.util.List;

/**
 * Stage 1 reranker — recalculates cosine similarity between query embedding
 * and each chunk's content embedding. Pure in-memory computation (~1ms).
 *
 * <p>Uses the same embedding model as VectorStore for consistency.</p>
 */
public class CosineReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(CosineReranker.class);

    private final EmbeddingModel embeddingModel;

    public CosineReranker(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        float[] queryEmbedding = embeddingModel.embed(query);

        List<ScoredChunk> rescored = candidates.stream()
                .map(sc -> {
                    float[] chunkEmbedding = embeddingModel.embed(sc.chunk().enrichedContent());
                    double similarity = cosineSimilarity(queryEmbedding, chunkEmbedding);
                    return new ScoredChunk(sc.chunk(), similarity, "cosine");
                })
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .toList();

        log.debug("[RAG Reranker] Cosine rescored {} candidates → top {} (best={:.3f})",
                candidates.size(), rescored.size(),
                rescored.isEmpty() ? 0.0 : rescored.getFirst().score());
        return rescored;
    }

    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0 : dot / denom;
    }
}
