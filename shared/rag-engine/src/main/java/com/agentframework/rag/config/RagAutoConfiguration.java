package com.agentframework.rag.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

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
}
