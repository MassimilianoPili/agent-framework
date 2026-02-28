package com.agentframework.rag.ingestion.chunking;

import com.agentframework.rag.config.RagProperties;
import com.agentframework.rag.model.CodeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecursiveCodeChunkerTest {

    private RecursiveCodeChunker chunker;

    @BeforeEach
    void setUp() {
        var properties = new RagProperties(true,
                new RagProperties.Ingestion(512, 100, true, 500,
                        List.of("java"), false),
                new RagProperties.Search(true, true, "cascade", 20, 8, 0.5, 60),
                new RagProperties.Ollama("mxbai-embed-large", "qwen2.5:1.5b", "http://localhost:11434"),
                new RagProperties.Cache(5, 24, 60));
        chunker = new RecursiveCodeChunker(properties);
    }

    @Test
    void shouldSupportCodeExtensions() {
        assertTrue(chunker.supports("java"));
        assertTrue(chunker.supports("go"));
        assertTrue(chunker.supports("py"));
        assertTrue(chunker.supports("ts"));
        assertFalse(chunker.supports("md"));
        assertFalse(chunker.supports("yml"));
    }

    @Test
    void shouldReturnEmptyForBlankContent() {
        List<CodeChunk> chunks = chunker.chunk("", "Test.java", "java");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void shouldReturnEmptyForNullContent() {
        List<CodeChunk> chunks = chunker.chunk(null, "Test.java", "java");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void shouldChunkSimpleClass() {
        String code = """
                package com.example;

                public class HelloWorld {
                    public void sayHello() {
                        System.out.println("Hello");
                    }

                    public void sayGoodbye() {
                        System.out.println("Goodbye");
                    }
                }
                """;
        List<CodeChunk> chunks = chunker.chunk(code, "HelloWorld.java", "java");
        assertFalse(chunks.isEmpty());
        // Every chunk should have filePath metadata
        chunks.forEach(c -> assertEquals("HelloWorld.java", c.metadata().filePath()));
    }

    @Test
    void shouldPreserveLanguageMetadata() {
        String code = "func main() {\n    fmt.Println(\"hello\")\n}\n";
        List<CodeChunk> chunks = chunker.chunk(code, "main.go", "go");
        assertFalse(chunks.isEmpty());
        assertEquals("go", chunks.get(0).metadata().language());
    }

    @Test
    void shouldSplitLargeFileIntoMultipleChunks() {
        // Generate a large file (~4000 tokens)
        StringBuilder sb = new StringBuilder("package com.example;\n\n");
        for (int i = 0; i < 50; i++) {
            sb.append("    public void method").append(i).append("() {\n");
            sb.append("        // This is method ").append(i).append(" with some implementation\n");
            sb.append("        String value = \"").append("x".repeat(80)).append("\";\n");
            sb.append("        System.out.println(value);\n");
            sb.append("    }\n\n");
        }
        List<CodeChunk> chunks = chunker.chunk(sb.toString(), "BigClass.java", "java");
        assertTrue(chunks.size() > 1, "Large file should produce multiple chunks");
    }

    @Test
    void shouldExtractSectionNameFromMethodBoundary() {
        String block = "    public void processData() {\n        // impl\n    }";
        String section = RecursiveCodeChunker.extractSectionName(block);
        assertNotNull(section);
        assertTrue(section.contains("public void processData"));
    }

    @Test
    void shouldEstimateTokensRoughly() {
        assertEquals(5, RecursiveCodeChunker.estimateTokens("01234567890123456789"));
        assertEquals(0, RecursiveCodeChunker.estimateTokens(""));
    }
}
