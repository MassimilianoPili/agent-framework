package com.agentframework.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the enrichment pipeline auto-injection.
 *
 * <p>Controls whether enrichment tasks (CONTEXT_MANAGER, RAG_MANAGER, SCHEMA_MANAGER,
 * TOOL_MANAGER) are automatically injected into plans after planner decomposition.</p>
 *
 * <pre>
 * enrichment:
 *   auto-inject: true
 *   include-context-manager: true
 *   include-rag: true
 *   include-schema: false
 *   include-tool-manager: false
 * </pre>
 */
@ConfigurationProperties(prefix = "enrichment")
public record EnrichmentProperties(

    /**
     * Master switch. When true, the {@code EnrichmentInjectorService} post-processes
     * plans after decomposition to inject enrichment tasks as dependencies of domain workers.
     * When false, enrichment relies entirely on the planner prompt (Level 1).
     */
    boolean autoInject,

    /**
     * Whether to inject a CONTEXT_MANAGER task (CM-001) that explores the codebase
     * and produces relevant file paths and constraints for downstream workers.
     */
    boolean includeContextManager,

    /**
     * Whether to inject a RAG_MANAGER task (RM-001) that performs semantic search
     * on vectorDB (pgvector) and graph queries (Apache AGE) to enrich worker context.
     * Depends on CM-001 when both are present.
     */
    boolean includeRag,

    /**
     * Whether to inject a SCHEMA_MANAGER task (SM-001) that extracts API interfaces,
     * DTOs, and architectural contracts. Useful when tasks touch APIs or data schemas.
     */
    boolean includeSchema,

    /**
     * Whether to inject per-task TOOL_MANAGER tasks (TM-*) that analyze each domain
     * worker's task and produce a precise {@link com.agentframework.common.policy.HookPolicy}
     * with the minimum set of MCP tools needed. Uses a lightweight Haiku model.
     *
     * <p>Opt-in: adds N Haiku-class LLM calls (one per domain task) that run in parallel.
     * Complements HOOK_MANAGER (plan-level) with per-task precision (#24 L2).</p>
     */
    boolean includeToolManager

) {}
