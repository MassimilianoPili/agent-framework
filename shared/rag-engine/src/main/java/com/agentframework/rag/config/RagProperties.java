package com.agentframework.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Configuration properties for the RAG pipeline.
 * Bind from {@code rag.*} in application.yml.
 */
@ConfigurationProperties("rag")
public record RagProperties(
        @DefaultValue("true") boolean enabled,
        Ingestion ingestion,
        Search search,
        Ollama ollama,
        Cache cache
) {

    public record Ingestion(
            @DefaultValue("512") int chunkSize,
            @DefaultValue("100") int chunkOverlap,
            @DefaultValue("true") boolean contextualEnrichment,
            @DefaultValue("500") int maxFileSizeKb,
            @DefaultValue("java,yml,yaml,md,xml,json,go,rs,js,ts,py,sql")
            List<String> includeExtensions,
            @DefaultValue("false") boolean fileWatcherEnabled
    ) {}

    public record Search(
            @DefaultValue("true") boolean hybridEnabled,
            @DefaultValue("true") boolean hydeEnabled,
            @DefaultValue("cascade") String rerankerType,
            @DefaultValue("20") int topK,
            @DefaultValue("8") int finalK,
            @DefaultValue("0.5") double similarityThreshold,
            @DefaultValue("60") int rrfK
    ) {}

    public record Ollama(
            @DefaultValue("mxbai-embed-large") String embeddingModel,
            @DefaultValue("qwen2.5:1.5b") String rerankingModel,
            @DefaultValue("http://ollama:11434") String baseUrl
    ) {}

    public record Cache(
            @DefaultValue("5") int redisDb,
            @DefaultValue("24") int embeddingTtlHours,
            @DefaultValue("60") int searchResultTtlMinutes
    ) {}
}
