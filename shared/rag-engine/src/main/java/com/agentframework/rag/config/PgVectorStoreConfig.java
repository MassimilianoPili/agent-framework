package com.agentframework.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Configures the pgvector-based VectorStore for RAG.
 * Uses the existing PostgreSQL datasource (Flyway V15 creates the table and indexes).
 */
@Configuration
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PgVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreConfig.class);

    private static final int EMBEDDING_DIMENSIONS = 1024; // mxbai-embed-large

    @Bean
    @ConditionalOnMissingBean(PgVectorStore.class)
    public PgVectorStore ragVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        log.info("[RAG] Configuring PgVectorStore with {} dimensions", EMBEDDING_DIMENSIONS);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(EMBEDDING_DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false) // Flyway V15 handles schema
                .build();
    }
}
