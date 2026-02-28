package com.agentframework.rag.ingestion.chunking;

import com.agentframework.rag.config.RagProperties;
import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Proposition chunker for documentation and configuration files.
 * Splits by headers/sections first, then into atomic self-contained propositions.
 *
 * <p>For markdown: splits at headings (# ## ###). For YAML/XML: splits at
 * top-level keys/elements. Falls back to paragraph-based splitting.</p>
 *
 * <p>Supported extensions: md, yml, yaml, xml, json, Dockerfile</p>
 */
@Component
public final class PropositionChunker implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(PropositionChunker.class);

    private static final Set<String> DOC_EXTENSIONS = Set.of(
            "md", "yml", "yaml", "xml", "json", "dockerfile", "toml", "txt"
    );

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,4}\\s+.+", Pattern.MULTILINE);
    private static final Pattern BLANK_LINE = Pattern.compile("\\n\\s*\\n");

    private final int chunkSize;
    private final int chunkOverlap;

    public PropositionChunker(RagProperties properties) {
        this.chunkSize = properties.ingestion().chunkSize();
        this.chunkOverlap = properties.ingestion().chunkOverlap();
    }

    @Override
    public boolean supports(String fileExtension) {
        return DOC_EXTENSIONS.contains(fileExtension.toLowerCase());
    }

    @Override
    public List<CodeChunk> chunk(String content, String filePath, String language) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        var docType = classifyDocType(filePath);
        var chunks = new ArrayList<CodeChunk>();

        // Split by headers for markdown, by blank lines for other formats
        List<String> sections = "md".equals(language)
                ? splitByHeadings(content)
                : splitByParagraphs(content);

        for (String section : sections) {
            if (section.isBlank()) continue;

            String heading = extractHeading(section);
            var metadata = new ChunkMetadata(filePath, language, heading,
                    List.of(), docType, List.of());

            if (estimateTokens(section) <= chunkSize) {
                chunks.add(new CodeChunk(section.strip(), null, metadata));
            } else {
                // Split large sections into overlapping chunks
                chunks.addAll(splitLargeSection(section, metadata));
            }
        }

        log.debug("[RAG Chunker] {} → {} chunks (doc, {} tokens/chunk)",
                filePath, chunks.size(), chunkSize);
        return chunks;
    }

    private List<String> splitByHeadings(String content) {
        var sections = new ArrayList<String>();
        var matcher = MARKDOWN_HEADING.matcher(content);
        int lastStart = 0;

        while (matcher.find()) {
            if (matcher.start() > lastStart) {
                sections.add(content.substring(lastStart, matcher.start()));
            }
            lastStart = matcher.start();
        }
        if (lastStart < content.length()) {
            sections.add(content.substring(lastStart));
        }
        return sections.isEmpty() ? List.of(content) : sections;
    }

    private List<String> splitByParagraphs(String content) {
        String[] paragraphs = BLANK_LINE.split(content);
        var sections = new ArrayList<String>();
        var current = new StringBuilder();

        for (String para : paragraphs) {
            if (estimateTokens(current.toString() + para) > chunkSize && !current.isEmpty()) {
                sections.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(para);
        }
        if (!current.isEmpty()) {
            sections.add(current.toString());
        }
        return sections;
    }

    private List<CodeChunk> splitLargeSection(String section, ChunkMetadata metadata) {
        var chunks = new ArrayList<CodeChunk>();
        String[] lines = section.split("\n");
        int start = 0;

        while (start < lines.length) {
            var sb = new StringBuilder();
            int i = start;
            while (i < lines.length && estimateTokens(sb.toString()) < chunkSize) {
                sb.append(lines[i]).append("\n");
                i++;
            }
            String chunkContent = sb.toString().strip();
            if (!chunkContent.isEmpty()) {
                chunks.add(new CodeChunk(chunkContent, null, metadata));
            }
            int linesConsumed = i - start;
            int overlapLines = Math.max(1, (int) (linesConsumed * ((double) chunkOverlap / chunkSize)));
            start += Math.max(1, linesConsumed - overlapLines);
        }
        return chunks;
    }

    static String extractHeading(String section) {
        var matcher = MARKDOWN_HEADING.matcher(section);
        if (matcher.find()) {
            return matcher.group().replaceFirst("^#+\\s+", "").strip();
        }
        // For non-markdown, use first non-blank line
        for (String line : section.split("\n")) {
            if (!line.isBlank()) {
                return line.strip().length() > 80 ? line.strip().substring(0, 80) : line.strip();
            }
        }
        return null;
    }

    static ChunkMetadata.DocType classifyDocType(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.contains("adr") || lower.contains("decision")) return ChunkMetadata.DocType.ADR;
        if (lower.endsWith("readme.md")) return ChunkMetadata.DocType.README;
        if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".xml")
                || lower.endsWith(".json") || lower.endsWith(".toml")) return ChunkMetadata.DocType.CONFIG;
        if (lower.contains("api") || lower.contains("swagger") || lower.contains("openapi"))
            return ChunkMetadata.DocType.API_DOC;
        return ChunkMetadata.DocType.OTHER;
    }

    static int estimateTokens(String text) {
        return text.length() / 4;
    }
}
