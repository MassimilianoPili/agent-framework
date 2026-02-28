package com.agentframework.rag.ingestion.chunking;

import com.agentframework.rag.model.CodeChunk;

import java.util.List;

/**
 * Strategy for splitting a document into chunks.
 * Implementations are selected based on file type.
 */
public sealed interface ChunkingStrategy
        permits RecursiveCodeChunker, PropositionChunker {

    /**
     * Split the given content into chunks.
     *
     * @param content  the full text content of the document
     * @param filePath the relative path of the source file
     * @param language the programming language or doc format
     * @return list of chunks with metadata
     */
    List<CodeChunk> chunk(String content, String filePath, String language);

    /** Returns true if this strategy handles the given file extension. */
    boolean supports(String fileExtension);
}
