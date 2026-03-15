package com.agentframework.orchestrator.council;

import com.agentframework.orchestrator.analytics.InformationForagingService;
import com.agentframework.orchestrator.analytics.InformationForagingService.ForagingPatch;
import com.agentframework.rag.graph.GraphRagService;
import com.agentframework.rag.model.ScoredChunk;
import com.agentframework.rag.model.SearchResult;
import com.agentframework.rag.search.RagSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Enriches council session context with relevant knowledge from the RAG pipeline.
 *
 * <p>Queries both the vector store (semantic search) and the graph databases
 * (knowledge + code graphs) to find past decisions, related code, and structural
 * relationships. The results are appended to the spec/context so council members
 * have full awareness of existing codebase patterns.</p>
 *
 * <p>Activated only when {@code rag.enabled=true}. Injected as
 * {@code Optional<CouncilRagEnricher>} into {@link CouncilService}.</p>
 */
@Component
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class CouncilRagEnricher {

    private static final Logger log = LoggerFactory.getLogger(CouncilRagEnricher.class);
    private static final int MAX_CHUNKS = 5;

    private final RagSearchService ragSearchService;
    private final GraphRagService graphRagService;
    // A2 Fase 4: RAG analytics
    private final @Nullable InformationForagingService informationForagingService;

    public CouncilRagEnricher(RagSearchService ragSearchService,
                              GraphRagService graphRagService,
                              @Nullable InformationForagingService informationForagingService) {
        this.ragSearchService = ragSearchService;
        this.graphRagService = graphRagService;
        this.informationForagingService = informationForagingService;
    }

    /**
     * Enriches a specification with relevant RAG context.
     *
     * <p>Appends a {@code "## Relevant Context from RAG"} section containing
     * top-scoring code/doc chunks and graph insights. Returns the original spec
     * unchanged if no relevant results are found.</p>
     *
     * @param spec the original specification text
     * @return enriched spec with RAG context appended, or original if no results
     */
    public String enrichSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            return spec;
        }

        try {
            SearchResult searchResult = ragSearchService.search(spec,
                    new com.agentframework.rag.model.SearchFilters(null, null, null, MAX_CHUNKS));
            List<String> graphInsights = graphRagService.findRelatedInsights(extractKeywords(spec));

            if (searchResult.chunks().isEmpty() && graphInsights.isEmpty()) {
                log.debug("[CouncilRagEnricher] No RAG results for spec");
                return spec;
            }

            // A2: Information Foraging — budget chunks per source using Marginal Value Theorem
            List<ScoredChunk> budgetedChunks = applyForagingBudget(searchResult.chunks());

            String ragSection = buildRagSection(budgetedChunks, graphInsights);
            log.info("[CouncilRagEnricher] Enriched spec with {} chunks (from {} raw) and {} graph insights",
                    budgetedChunks.size(), searchResult.chunks().size(), graphInsights.size());

            return spec + "\n\n" + ragSection;
        } catch (Exception e) {
            log.warn("[CouncilRagEnricher] Failed to enrich spec, proceeding without RAG: {}",
                    e.getMessage());
            return spec;
        }
    }

    String buildRagSection(List<ScoredChunk> chunks, List<String> graphInsights) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Relevant Context from RAG\n\n");

        if (!chunks.isEmpty()) {
            sb.append("### Related Code/Documentation\n\n");
            for (ScoredChunk sc : chunks) {
                String filePath = sc.chunk().metadata() != null
                        ? sc.chunk().metadata().filePath() : "unknown";
                sb.append("**").append(filePath).append("** (score: ")
                        .append(String.format("%.2f", sc.score())).append(")\n");
                sb.append("```\n").append(truncate(sc.chunk().content(), 300)).append("\n```\n\n");
            }
        }

        if (!graphInsights.isEmpty()) {
            sb.append("### Structural Insights\n\n");
            for (String insight : graphInsights) {
                sb.append("- ").append(insight).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Extracts the first meaningful phrase from the spec for graph keyword search.
     * Takes the first line (typically the title) or first 100 characters.
     */
    String extractKeywords(String spec) {
        String firstLine = spec.lines().findFirst().orElse(spec);
        return firstLine.length() <= 100 ? firstLine : firstLine.substring(0, 100);
    }

    /**
     * Applies Information Foraging Theory to budget chunks by source.
     * Groups chunks by file path (patch), computes optimal chunk count per patch,
     * and returns only the budgeted chunks in scent-trail order.
     */
    private List<ScoredChunk> applyForagingBudget(List<ScoredChunk> chunks) {
        if (informationForagingService == null || chunks.size() <= 1) {
            return chunks;
        }
        try {
            // Group chunks by file path (each file = one foraging patch)
            Map<String, List<ScoredChunk>> byPath = new java.util.LinkedHashMap<>();
            for (ScoredChunk sc : chunks) {
                String path = sc.chunk().metadata() != null
                        ? sc.chunk().metadata().filePath() : "unknown";
                byPath.computeIfAbsent(path, k -> new ArrayList<>()).add(sc);
            }

            // Build foraging patches
            List<ForagingPatch> patches = new ArrayList<>();
            for (var entry : byPath.entrySet()) {
                double maxScore = entry.getValue().stream()
                        .mapToDouble(ScoredChunk::score).max().orElse(0.0);
                patches.add(new ForagingPatch(
                        entry.getKey(), maxScore, 1.0, entry.getValue().size()));
            }

            var report = informationForagingService.forage(patches);
            log.debug("[CouncilRagEnricher] Foraging budget: globalIR={}, {} patches ranked",
                    String.format("%.4f", report.globalInformationRate()),
                    report.patchRankings().size());

            // Collect budgeted chunks in scent-trail order
            List<ScoredChunk> result = new ArrayList<>();
            for (String patchId : report.patchRankings()) {
                int budget = report.optimalChunks().getOrDefault(patchId, 0);
                if (budget > 0 && byPath.containsKey(patchId)) {
                    List<ScoredChunk> patchChunks = byPath.get(patchId);
                    // Take top-scoring chunks up to budget
                    patchChunks.stream()
                            .sorted((a, b) -> Double.compare(b.score(), a.score()))
                            .limit(budget)
                            .forEach(result::add);
                }
            }
            return result.isEmpty() ? chunks : result;
        } catch (Exception e) {
            log.debug("[CouncilRagEnricher] Information foraging failed (non-blocking): {}", e.getMessage());
            return chunks;
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
