package com.agentframework.rag.ingestion.enrichment;

import com.agentframework.rag.model.ChunkMetadata;
import com.agentframework.rag.model.CodeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataEnricherTest {

    private final MetadataEnricher enricher = new MetadataEnricher();

    @Test
    void shouldExtractJavaEntities() {
        String javaCode = """
                package com.agentframework.worker;

                import org.springframework.stereotype.Component;

                @Component
                public class MyService {
                    private final UserRepository repo;
                }
                """;
        List<String> entities = MetadataEnricher.extractEntities(javaCode, "java");
        assertTrue(entities.contains("com.agentframework.worker"));
        assertTrue(entities.contains("MyService"));
        assertTrue(entities.contains("@Component"));
    }

    @Test
    void shouldExtractKeyphrases() {
        String content = "The ContextManagerWorker uses buildStandardUserPrompt to create prompts";
        List<String> keyphrases = MetadataEnricher.extractKeyphrases(content);
        assertTrue(keyphrases.stream().anyMatch(k -> k.contains("Context")));
    }

    @Test
    void shouldClassifyCodeFiles() {
        assertEquals(ChunkMetadata.DocType.CODE,
                MetadataEnricher.classifyDocType("some code content", "Service.java"));
        assertEquals(ChunkMetadata.DocType.CODE,
                MetadataEnricher.classifyDocType("func main()", "main.go"));
    }

    @Test
    void shouldClassifyAdrDocuments() {
        assertEquals(ChunkMetadata.DocType.ADR,
                MetadataEnricher.classifyDocType("# Decision\n## Status\nAccepted", "docs/decision-001.md"));
    }

    @Test
    void shouldEnrichChunkList() {
        var metadata = new ChunkMetadata("App.java", "java");
        var chunk = new CodeChunk(
                "package com.example;\npublic class App { }",
                null, metadata);

        List<CodeChunk> enriched = enricher.enrich(List.of(chunk));
        assertEquals(1, enriched.size());
        assertFalse(enriched.getFirst().metadata().entities().isEmpty());
        assertEquals(ChunkMetadata.DocType.CODE, enriched.getFirst().metadata().docType());
    }
}
