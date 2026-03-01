package com.agentframework.rag.config;

import com.agentframework.rag.search.reranking.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Master auto-configuration for the RAG engine.
 * Activates when {@code rag.enabled=true} (default).
 * Scans all RAG components (ingestion, search, cache, tool).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RagProperties.class)
@ComponentScan(basePackages = "com.agentframework.rag")
public class RagAutoConfiguration {

    /**
     * Virtual thread executor for RAG parallel operations (Java 21+).
     * Each virtual thread costs ~few KB vs ~1MB for platform threads.
     * Used by ContextualEnricher, HybridSearchService, LlmReranker, GraphRagService.
     */
    @Bean("ragParallelExecutor")
    @ConditionalOnMissingBean(name = "ragParallelExecutor")
    public ExecutorService ragParallelExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Reranker factory — creates the configured reranker variant.
     * Cascade (default): CosineReranker → LlmReranker (2-stage).
     */
    @Bean
    @ConditionalOnMissingBean
    public Reranker reranker(RagProperties properties,
                             EmbeddingModel embeddingModel,
                             ChatClient.Builder chatClientBuilder,
                             @Qualifier("ragParallelExecutor") ExecutorService ragParallelExecutor) {
        var cosine = new CosineReranker(embeddingModel);
        var llm = new LlmReranker(chatClientBuilder, ragParallelExecutor);

        return switch (properties.search().rerankerType()) {
            case "cascade" -> new CascadeReranker(cosine, llm, properties.search().topK());
            case "cosine" -> cosine;
            case "llm" -> llm;
            default -> new NoOpReranker();
        };
    }
}
