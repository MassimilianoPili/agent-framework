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
 * Recursive code chunker for source code files.
 * Splits hierarchically: class → method → block → line.
 * Preserves method boundaries when possible.
 *
 * <p>Supported extensions: java, go, rs, js, ts, py, sql</p>
 */
@Component
public final class RecursiveCodeChunker implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(RecursiveCodeChunker.class);

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "go", "rs", "js", "ts", "py", "sql"
    );

    // Patterns for detecting method/function boundaries
    private static final Pattern METHOD_BOUNDARY = Pattern.compile(
            "^\\s*(public|private|protected|static|func|fn|def|function|async)\\s",
            Pattern.MULTILINE
    );

    private final int chunkSize;
    private final int chunkOverlap;

    public RecursiveCodeChunker(RagProperties properties) {
        this.chunkSize = properties.ingestion().chunkSize();
        this.chunkOverlap = properties.ingestion().chunkOverlap();
    }

    @Override
    public boolean supports(String fileExtension) {
        return CODE_EXTENSIONS.contains(fileExtension.toLowerCase());
    }

    @Override
    public List<CodeChunk> chunk(String content, String filePath, String language) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        var chunks = new ArrayList<CodeChunk>();
        var lines = content.split("\n");
        var metadata = new ChunkMetadata(filePath, language);

        // Try to split at method boundaries first
        var methodBlocks = splitAtMethodBoundaries(lines);

        for (String block : methodBlocks) {
            if (estimateTokens(block) <= chunkSize) {
                // Block fits in one chunk
                chunks.add(new CodeChunk(block.strip(), null, withSection(metadata, block)));
            } else {
                // Block too large — split with overlap
                chunks.addAll(splitWithOverlap(block, metadata));
            }
        }

        log.debug("[RAG Chunker] {} → {} chunks (code, {} tokens/chunk)",
                filePath, chunks.size(), chunkSize);
        return chunks;
    }

    private List<String> splitAtMethodBoundaries(String[] lines) {
        var blocks = new ArrayList<String>();
        var current = new StringBuilder();

        for (String line : lines) {
            if (METHOD_BOUNDARY.matcher(line).find() && !current.isEmpty()) {
                blocks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (!current.isEmpty()) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private List<CodeChunk> splitWithOverlap(String block, ChunkMetadata metadata) {
        var chunks = new ArrayList<CodeChunk>();
        var lines = block.split("\n");
        int start = 0;

        while (start < lines.length) {
            var sb = new StringBuilder();
            int lineCount = 0;
            int i = start;

            while (i < lines.length && estimateTokens(sb.toString()) < chunkSize) {
                sb.append(lines[i]).append("\n");
                i++;
                lineCount++;
            }

            String chunkContent = sb.toString().strip();
            if (!chunkContent.isEmpty()) {
                chunks.add(new CodeChunk(chunkContent, null, withSection(metadata, chunkContent)));
            }

            // Advance by (lines consumed - overlap lines)
            int overlapLines = Math.max(1, (int) (lineCount * ((double) chunkOverlap / chunkSize)));
            start += Math.max(1, lineCount - overlapLines);
        }
        return chunks;
    }

    private ChunkMetadata withSection(ChunkMetadata base, String block) {
        String section = extractSectionName(block);
        if (section != null) {
            return new ChunkMetadata(base.filePath(), base.language(), section,
                    base.entities(), base.docType(), base.keyphrases());
        }
        return base;
    }

    static String extractSectionName(String block) {
        var matcher = METHOD_BOUNDARY.matcher(block);
        if (matcher.find()) {
            // Extract the method/function signature line
            int lineStart = block.lastIndexOf('\n', matcher.start()) + 1;
            int lineEnd = block.indexOf('\n', matcher.start());
            if (lineEnd < 0) lineEnd = block.length();
            String line = block.substring(lineStart, lineEnd).strip();
            // Truncate at opening brace or parenthesis for readability
            int braceIdx = line.indexOf('{');
            int parenIdx = line.indexOf('(');
            int cutIdx = Math.min(
                    braceIdx > 0 ? braceIdx : Integer.MAX_VALUE,
                    parenIdx > 0 ? parenIdx : Integer.MAX_VALUE
            );
            return cutIdx < Integer.MAX_VALUE ? line.substring(0, cutIdx).strip() : line;
        }
        return null;
    }

    /** Rough token estimate: ~4 characters per token (common for code). */
    static int estimateTokens(String text) {
        return text.length() / 4;
    }
}
