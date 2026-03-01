package com.agentframework.rag.ingestion;

import com.agentframework.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeDocumentReaderTest {

    private CodeDocumentReader reader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var properties = new RagProperties(true,
                new RagProperties.Ingestion(512, 100, true, 500,
                        List.of("java", "md", "yml"), false),
                new RagProperties.Search(true, true, "cascade", 20, 8, 0.5, 60),
                new RagProperties.Ollama("mxbai-embed-large", "qwen2.5:1.5b", "http://localhost:11434"),
                new RagProperties.Cache(5, 24, 60));
        reader = new CodeDocumentReader(properties);
    }

    @Test
    void shouldReadJavaFiles() throws IOException {
        Path javaFile = tempDir.resolve("App.java");
        Files.writeString(javaFile, "public class App { }");

        List<Document> docs = reader.readDocuments(tempDir);
        assertEquals(1, docs.size());
        assertEquals("java", docs.getFirst().getMetadata().get("language"));
        assertEquals("App.java", docs.getFirst().getMetadata().get("filePath"));
    }

    @Test
    void shouldSkipUnsupportedExtensions() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "class App {}");
        Files.writeString(tempDir.resolve("image.png"), "binary data");
        Files.writeString(tempDir.resolve("notes.txt"), "some notes");

        List<Document> docs = reader.readDocuments(tempDir);
        assertEquals(1, docs.size()); // Only .java
    }

    @Test
    void shouldSkipGitDirectory() throws IOException {
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectory(gitDir);
        Files.writeString(gitDir.resolve("config"), "git config");
        Files.writeString(tempDir.resolve("App.java"), "class App {}");

        List<Document> docs = reader.readDocuments(tempDir);
        assertEquals(1, docs.size()); // .git skipped
    }

    @Test
    void shouldSkipLargeFiles() throws IOException {
        // Create a file > 500KB
        Path bigFile = tempDir.resolve("BigFile.java");
        Files.writeString(bigFile, "x".repeat(600 * 1024));

        List<Document> docs = reader.readDocuments(tempDir);
        assertTrue(docs.isEmpty());
    }

    @Test
    void shouldMapExtensionToLanguage() {
        assertEquals("java", CodeDocumentReader.extensionToLanguage("java"));
        assertEquals("go", CodeDocumentReader.extensionToLanguage("go"));
        assertEquals("markdown", CodeDocumentReader.extensionToLanguage("md"));
        assertEquals("yaml", CodeDocumentReader.extensionToLanguage("yml"));
        assertEquals("typescript", CodeDocumentReader.extensionToLanguage("ts"));
        assertEquals("python", CodeDocumentReader.extensionToLanguage("py"));
    }

    @Test
    void shouldHandleDockerfile() {
        assertEquals("dockerfile", CodeDocumentReader.getExtension(Path.of("Dockerfile")));
    }
}
