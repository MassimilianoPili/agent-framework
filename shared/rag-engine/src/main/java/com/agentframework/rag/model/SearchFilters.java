package com.agentframework.rag.model;

/**
 * Filters for narrowing RAG search results.
 *
 * @param language        filter by programming language (nullable)
 * @param filePathPattern glob pattern for file paths (nullable, e.g. "src/main/**")
 * @param docType         filter by document type (nullable)
 * @param maxResults      maximum results to return (default: 8)
 */
public record SearchFilters(
        String language,
        String filePathPattern,
        ChunkMetadata.DocType docType,
        int maxResults
) {
    public static SearchFilters defaults() {
        return new SearchFilters(null, null, null, 8);
    }

    public SearchFilters withMaxResults(int max) {
        return new SearchFilters(language, filePathPattern, docType, max);
    }
}
