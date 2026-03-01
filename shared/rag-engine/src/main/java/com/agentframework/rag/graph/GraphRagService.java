package com.agentframework.rag.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Cross-graph RAG service — correlates knowledge_graph and code_graph insights.
 *
 * <p>Queries both graphs in parallel using virtual threads, then merges results
 * by common filePath for a unified view of semantic and structural relationships.</p>
 */
@Service
public class GraphRagService {

    private static final Logger log = LoggerFactory.getLogger(GraphRagService.class);

    private final KnowledgeGraphService knowledgeGraph;
    private final CodeGraphService codeGraph;
    private final ExecutorService executor;

    public GraphRagService(KnowledgeGraphService knowledgeGraph,
                           CodeGraphService codeGraph,
                           @Qualifier("ragParallelExecutor") ExecutorService ragParallelExecutor) {
        this.knowledgeGraph = knowledgeGraph;
        this.codeGraph = codeGraph;
        this.executor = ragParallelExecutor;
    }

    /**
     * Find related insights across both graphs for a given query keyword.
     *
     * @param query keyword or class name to search
     * @return list of human-readable insight strings
     */
    public List<String> findRelatedInsights(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // Fan-out: knowledge graph + code graph in parallel
        var knowledgeFuture = CompletableFuture.supplyAsync(
                () -> knowledgeGraph.findConceptsByKeyword(query), executor);
        var codeFuture = CompletableFuture.supplyAsync(
                () -> codeGraph.findClassesByName(query), executor);

        try {
            CompletableFuture.allOf(knowledgeFuture, codeFuture)
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[GraphRAG] Parallel query timed out: {}", e.getMessage());
        }

        List<Map<String, Object>> concepts = knowledgeFuture.getNow(List.of());
        List<Map<String, Object>> classes = codeFuture.getNow(List.of());

        List<String> insights = correlate(concepts, classes, query);
        log.debug("[GraphRAG] Found {} insights for query '{}' (concepts={}, classes={})",
                insights.size(), query, concepts.size(), classes.size());
        return insights;
    }

    List<String> correlate(List<Map<String, Object>> concepts,
                           List<Map<String, Object>> classes,
                           String query) {
        List<String> insights = new ArrayList<>();

        if (!concepts.isEmpty()) {
            insights.add(String.format("Found %d related concepts in knowledge graph for '%s'",
                    concepts.size(), query));
        }
        if (!classes.isEmpty()) {
            String classNames = classes.stream()
                    .map(m -> String.valueOf(m.getOrDefault("result", "unknown")))
                    .limit(5)
                    .collect(Collectors.joining(", "));
            insights.add(String.format("Found %d related code entities: %s",
                    classes.size(), classNames));
        }

        if (insights.isEmpty()) {
            insights.add(String.format("No graph insights found for '%s'", query));
        }

        return insights;
    }
}
