package com.agentframework.rag.ingestion.enrichment;

import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enriches chunk metadata with extracted entities, keyphrases, and doc type classification.
 *
 * <p>Uses regex-based extraction (no LLM needed):</p>
 * <ul>
 *   <li>Java: class/interface/record names, package declarations, annotations</li>
 *   <li>General: import statements, function/method names</li>
 *   <li>Keyphrases: significant multi-word terms from the chunk</li>
 * </ul>
 */
@Component
public class MetadataEnricher {

    private static final Logger log = LoggerFactory.getLogger(MetadataEnricher.class);

    private static final Pattern JAVA_CLASS = Pattern.compile(
            "(?:class|interface|record|enum)\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern JAVA_ANNOTATION = Pattern.compile(
            "@(\\w+)", Pattern.MULTILINE);
    private static final Pattern IMPORT_STMT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    /**
     * Enrich metadata for each chunk with extracted entities and keyphrases.
     */
    public List<CodeChunk> enrich(List<CodeChunk> chunks) {
        return chunks.stream()
                .map(this::enrichChunk)
                .collect(Collectors.toList());
    }

    private CodeChunk enrichChunk(CodeChunk chunk) {
        var meta = chunk.metadata();
        var entities = extractEntities(chunk.content(), meta.language());
        var keyphrases = extractKeyphrases(chunk.content());
        var docType = meta.docType() == ChunkMetadata.DocType.OTHER
                ? classifyDocType(chunk.content(), meta.filePath())
                : meta.docType();

        var enrichedMeta = new ChunkMetadata(
                meta.filePath(), meta.language(), meta.sectionTitle(),
                entities, docType, keyphrases
        );
        return new CodeChunk(chunk.content(), chunk.contextPrefix(), enrichedMeta);
    }

    static List<String> extractEntities(String content, String language) {
        var entities = new ArrayList<String>();

        // Java-specific
        if ("java".equals(language)) {
            addMatches(entities, JAVA_PACKAGE, content);
            addMatches(entities, JAVA_CLASS, content);
            addAnnotations(entities, content);
        }

        // Generic imports
        addMatches(entities, IMPORT_STMT, content);

        return entities.stream().distinct().collect(Collectors.toList());
    }

    static List<String> extractKeyphrases(String content) {
        var keyphrases = new ArrayList<String>();

        // Extract significant identifiers: CamelCase and snake_case words
        Pattern camelCase = Pattern.compile("\\b([A-Z][a-z]+(?:[A-Z][a-z]+)+)\\b");
        Matcher matcher = camelCase.matcher(content);
        while (matcher.find()) {
            keyphrases.add(matcher.group(1));
        }

        Pattern snakeCase = Pattern.compile("\\b([a-z]+(?:_[a-z]+)+)\\b");
        matcher = snakeCase.matcher(content);
        while (matcher.find()) {
            keyphrases.add(matcher.group(1));
        }

        return keyphrases.stream().distinct().limit(20).collect(Collectors.toList());
    }

    static ChunkMetadata.DocType classifyDocType(String content, String filePath) {
        if (filePath.toLowerCase().endsWith(".java")
                || filePath.toLowerCase().endsWith(".go")
                || filePath.toLowerCase().endsWith(".py")) {
            return ChunkMetadata.DocType.CODE;
        }
        if (content.contains("openapi") || content.contains("swagger")) {
            return ChunkMetadata.DocType.API_DOC;
        }
        if (content.toLowerCase().contains("# decision") || content.toLowerCase().contains("## status")) {
            return ChunkMetadata.DocType.ADR;
        }
        return ChunkMetadata.DocType.OTHER;
    }

    private static void addMatches(List<String> entities, Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            entities.add(matcher.group(1));
        }
    }

    private static void addAnnotations(List<String> entities, String content) {
        Matcher matcher = JAVA_ANNOTATION.matcher(content);
        while (matcher.find()) {
            String annotation = matcher.group(1);
            // Filter out common noise annotations
            if (!"Override".equals(annotation) && !"SuppressWarnings".equals(annotation)) {
                entities.add("@" + annotation);
            }
        }
    }
}
