package com.agentframework.rag.ingestion;

import com.agentframework.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads source files from a directory, filtering by extension and size.
 * Produces Spring AI {@link Document} objects with metadata (language, filePath, sizeKb).
 */
@Component
public class CodeDocumentReader {

    private static final Logger log = LoggerFactory.getLogger(CodeDocumentReader.class);

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".gradle", ".idea",
            "__pycache__", ".venv", "vendor", "dist"
    );

    private final Set<String> includeExtensions;
    private final long maxFileSizeBytes;

    public CodeDocumentReader(RagProperties properties) {
        this.includeExtensions = Set.copyOf(properties.ingestion().includeExtensions());
        this.maxFileSizeBytes = properties.ingestion().maxFileSizeKb() * 1024L;
    }

    /**
     * Scan a directory recursively and return documents for all matching files.
     *
     * @param rootDir the root directory to scan
     * @return list of Documents with content and metadata
     */
    public List<Document> readDocuments(Path rootDir) throws IOException {
        var documents = new ArrayList<Document>();

        Files.walkFileTree(rootDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    processFile(file, rootDir, documents);
                } catch (IOException e) {
                    log.warn("[RAG Reader] Failed to read {}: {}", file, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("[RAG Reader] Read {} documents from {}", documents.size(), rootDir);
        return documents;
    }

    /**
     * Read a single file and return a Document.
     */
    public Document readSingleFile(Path file, Path rootDir) throws IOException {
        String extension = getExtension(file);
        String relativePath = rootDir.relativize(file).toString();
        String content = Files.readString(file);
        long sizeKb = Files.size(file) / 1024;

        return new Document(content, Map.of(
                "filePath", relativePath,
                "language", extensionToLanguage(extension),
                "sizeKb", sizeKb,
                "fileName", file.getFileName().toString()
        ));
    }

    private void processFile(Path file, Path rootDir, List<Document> documents) throws IOException {
        String extension = getExtension(file);
        if (!includeExtensions.contains(extension)) {
            return;
        }

        long fileSize = Files.size(file);
        if (fileSize > maxFileSizeBytes || fileSize == 0) {
            return;
        }

        documents.add(readSingleFile(file, rootDir));
    }

    static String getExtension(Path file) {
        String name = file.getFileName().toString();
        // Handle special files
        if ("Dockerfile".equals(name)) return "dockerfile";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    static String extensionToLanguage(String extension) {
        return switch (extension) {
            case "java" -> "java";
            case "go" -> "go";
            case "rs" -> "rust";
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "py" -> "python";
            case "sql" -> "sql";
            case "md" -> "markdown";
            case "yml", "yaml" -> "yaml";
            case "xml" -> "xml";
            case "json" -> "json";
            case "dockerfile" -> "dockerfile";
            case "toml" -> "toml";
            default -> extension;
        };
    }
}
