package com.agentframework.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Configures the Ollama embedding model for RAG.
 *
 * <p>The actual OllamaEmbeddingModel bean is created by Spring AI's
 * {@code spring-ai-starter-model-ollama} auto-configuration using standard
 * {@code spring.ai.ollama.*} properties. This class just logs the RAG-specific
 * configuration for diagnostics.</p>
 *
 * <p>Required application.yml properties (bridged from rag.ollama.*):</p>
 * <pre>
 * spring.ai.ollama.base-url: ${rag.ollama.base-url}
 * spring.ai.ollama.embedding.options.model: ${rag.ollama.embedding-model}
 * </pre>
 */
@Configuration
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OllamaEmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingConfig.class);

    private final RagProperties properties;

    public OllamaEmbeddingConfig(RagProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void logConfig() {
        var ollama = properties.ollama();
        log.info("[RAG] Ollama embedding model: '{}', reranking model: '{}', base URL: {}",
                ollama.embeddingModel(), ollama.rerankingModel(), ollama.baseUrl());
    }
}
