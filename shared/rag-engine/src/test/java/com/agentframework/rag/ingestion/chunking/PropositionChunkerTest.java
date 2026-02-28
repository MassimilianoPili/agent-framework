package com.agentframework.rag.ingestion.chunking;

import com.agentframework.rag.config.RagProperties;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropositionChunkerTest {

    private PropositionChunker chunker;

    @BeforeEach
    void setUp() {
        var properties = new RagProperties(true,
                new RagProperties.Ingestion(512, 100, true, 500,
                        List.of("md", "yml"), false),
                new RagProperties.Search(true, true, "cascade", 20, 8, 0.5, 60),
                new RagProperties.Ollama("mxbai-embed-large", "qwen2.5:1.5b", "http://localhost:11434"),
                new RagProperties.Cache(5, 24, 60));
        chunker = new PropositionChunker(properties);
    }

    @Test
    void shouldSupportDocExtensions() {
        assertTrue(chunker.supports("md"));
        assertTrue(chunker.supports("yml"));
        assertTrue(chunker.supports("yaml"));
        assertTrue(chunker.supports("xml"));
        assertTrue(chunker.supports("json"));
        assertFalse(chunker.supports("java"));
        assertFalse(chunker.supports("go"));
    }

    @Test
    void shouldReturnEmptyForBlankContent() {
        assertTrue(chunker.chunk("", "README.md", "md").isEmpty());
        assertTrue(chunker.chunk(null, "README.md", "md").isEmpty());
    }

    @Test
    void shouldSplitMarkdownByHeadings() {
        String markdown = """
                # Introduction
                This is the intro section.

                ## Features
                Feature list here.

                ## Installation
                Install instructions here.
                """;
        List<CodeChunk> chunks = chunker.chunk(markdown, "README.md", "md");
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 2, "Should split at headings");
    }

    @Test
    void shouldExtractHeadingAsSectionTitle() {
        String markdown = "# My Title\nSome content here.";
        List<CodeChunk> chunks = chunker.chunk(markdown, "README.md", "md");
        assertFalse(chunks.isEmpty());
        assertEquals("My Title", chunks.get(0).metadata().sectionTitle());
    }

    @Test
    void shouldClassifyDocTypeFromFilePath() {
        assertEquals(ChunkMetadata.DocType.ADR, PropositionChunker.classifyDocType("docs/adr/001-use-redis.md"));
        assertEquals(ChunkMetadata.DocType.README, PropositionChunker.classifyDocType("README.md"));
        assertEquals(ChunkMetadata.DocType.CONFIG, PropositionChunker.classifyDocType("application.yml"));
        assertEquals(ChunkMetadata.DocType.CONFIG, PropositionChunker.classifyDocType("pom.xml"));
    }

    @Test
    void shouldChunkYamlByParagraphs() {
        String yaml = """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/db

                server:
                  port: 8080

                logging:
                  level:
                    root: INFO
                """;
        List<CodeChunk> chunks = chunker.chunk(yaml, "application.yml", "yaml");
        assertFalse(chunks.isEmpty());
        assertEquals("yaml", chunks.get(0).metadata().language());
    }
}
