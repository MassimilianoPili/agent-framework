package com.agentframework.rag.model;

import java.util.List;

/**
 * Metadata associated with a code/documentation chunk.
 *
 * @param filePath       relative path of the source file
 * @param language       programming language or doc format (java, md, yml, ...)
 * @param sectionTitle   enclosing class/method/heading name (nullable)
 * @param entities        extracted entities (class names, interfaces, packages)
 * @param docType        classification: CODE, API_DOC, ADR, CONFIG, README, OTHER
 * @param keyphrases     extracted key phrases for search boost
 */
public record ChunkMetadata(
        String filePath,
        String language,
        String sectionTitle,
        List<String> entities,
        DocType docType,
        List<String> keyphrases
) {
    public enum DocType {
        CODE, API_DOC, ADR, CONFIG, README, OTHER
    }

    public ChunkMetadata(String filePath, String language) {
        this(filePath, language, null, List.of(), DocType.OTHER, List.of());
    }
}
