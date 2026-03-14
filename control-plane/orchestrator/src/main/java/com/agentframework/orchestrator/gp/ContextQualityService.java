package com.agentframework.orchestrator.gp;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Information-theoretic context quality scoring (#35).
 *
 * <p>Computes a composite quality score for the context passed to a worker,
 * measuring how well the CONTEXT_MANAGER-selected files match what the worker
 * actually uses. The score feeds into:
 * <ul>
 *   <li>{@code task_outcomes.context_quality_score} — GP training data</li>
 *   <li>{@link com.agentframework.orchestrator.reward.RewardComputationService} — 4th reward source</li>
 *   <li>{@link BayesianSuccessPredictor} — feature slot [1027]</li>
 * </ul>
 *
 * <h3>Metrics</h3>
 * <ol>
 *   <li><b>File Relevance</b> (weight 0.45): ratio of CM-selected files that appear
 *       in the worker's result or file modifications (proxy for Mutual Information)</li>
 *   <li><b>Context Entropy penalty</b> (weight 0.30): penalises contexts that are
 *       either too broad (many large dependencies) or too narrow (single tiny dep)</li>
 *   <li><b>KL Divergence Score</b> (weight 0.25): measures alignment between
 *       CM-selected and worker-used file distributions. Uses geometric mean of
 *       bidirectional coverage as a proxy for {@code 1 - D_KL(P_used || P_selected)}</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(prefix = "gp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ContextQualityProperties.class)
public class ContextQualityService {

    private static final Logger log = LoggerFactory.getLogger(ContextQualityService.class);

    private final double fileRelevanceWeight;
    private final double entropyWeight;
    private final double klDivergenceWeight;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final PlanItemRepository planItemRepository;
    private final ObjectMapper objectMapper;

    public ContextQualityService(TaskOutcomeRepository taskOutcomeRepository,
                                  PlanItemRepository planItemRepository,
                                  ObjectMapper objectMapper,
                                  ContextQualityProperties properties) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.planItemRepository = planItemRepository;
        this.objectMapper = objectMapper;
        this.fileRelevanceWeight = properties.weights().fileRelevance();
        this.entropyWeight = properties.weights().entropy();
        this.klDivergenceWeight = properties.weights().klDivergence();
    }

    /**
     * Computes context quality for a completed task, persists it in task_outcomes,
     * and returns the composite score for reward integration.
     *
     * <p>Reconstructs the context from the item's completed dependencies
     * (the same data that was sent at dispatch time via {@code buildContextJson}).
     * Produces a {@link ContextQualityFeedback} with detailed metrics and
     * actionable suggestions, logged at INFO level for observability.</p>
     *
     * @param item   the completed PlanItem (loaded with plan)
     * @param result the AgentResult with resultJson and fileModifications
     * @return composite score in [0, 1], or {@code null} if scoring was skipped
     */
    public Double computeAndStore(PlanItem item, AgentResult result) {
        // Skip infrastructure workers — they don't consume context in the same way
        if (isInfrastructureWorker(item.getWorkerType())) {
            return null;
        }

        // Reconstruct context from completed dependencies
        Map<String, String> depResults = reconstructDependencyResults(item);
        if (depResults.isEmpty()) {
            log.debug("No dependencies for task {} — skipping quality scoring", item.getTaskKey());
            return null;
        }

        // Extract file sets once — shared by fileRelevance and klDivergence
        Set<String> selectedFiles = extractSelectedFiles(depResults);
        Set<String> usedFiles = extractUsedFiles(result);

        double fileRelevance = computeFileRelevance(selectedFiles, usedFiles);
        double entropyScore = computeEntropyScore(depResults);
        double klScore = computeKlDivergenceScore(selectedFiles, usedFiles);

        double composite = fileRelevanceWeight * fileRelevance
                         + entropyWeight * entropyScore
                         + klDivergenceWeight * klScore;

        // Clamp to [0, 1]
        composite = Math.max(0.0, Math.min(1.0, composite));

        // Build structured feedback for observability
        Set<String> unused = computeUnusedSelectedFiles(selectedFiles, usedFiles);
        Set<String> missing = computeMissingFiles(selectedFiles, usedFiles);
        String suggestion = buildSuggestion(unused, missing);

        // Persist composite score to task_outcomes for GP training
        taskOutcomeRepository.updateContextQualityScore(item.getId(), composite);

        log.info("Context quality: task={} composite={} [fileRel={} entropy={} kl={}] "
                        + "unused={} missing={} | {}",
                item.getTaskKey(),
                String.format("%.3f", composite),
                String.format("%.3f", fileRelevance),
                String.format("%.3f", entropyScore),
                String.format("%.3f", klScore),
                unused.size(), missing.size(), suggestion);

        return composite;
    }

    /** Returns the configured file relevance weight. */
    double getFileRelevanceWeight() { return fileRelevanceWeight; }

    /** Returns the configured entropy weight. */
    double getEntropyWeight() { return entropyWeight; }

    /** Returns the configured KL divergence weight. */
    double getKlDivergenceWeight() { return klDivergenceWeight; }

    /**
     * Returns the average context quality score for a given worker type,
     * for use as a Bayesian prior in the success predictor.
     */
    public Double averageScoreForWorkerType(String workerType) {
        return taskOutcomeRepository.averageContextQualityByWorkerType(workerType);
    }

    // ── Scoring methods (package-private for testing) ────────────────────────

    /**
     * File Relevance Score — proxy for Mutual Information I(Context; Result).
     *
     * <p>Measures what fraction of CM-selected files appear in the worker's output.
     * Uses fuzzy path matching (substring containment) to handle different path
     * representations (relative vs absolute, with/without leading dirs).</p>
     *
     * @return score in [0, 1], or 0.5 if no file selection data available
     */
    double computeFileRelevance(Set<String> selectedFiles, Set<String> usedFiles) {
        if (selectedFiles.isEmpty()) {
            return 0.5; // neutral — no CM dependency with relevant_files
        }

        if (usedFiles.isEmpty()) {
            return 0.5; // worker didn't report file usage
        }

        long hits = selectedFiles.stream()
                .filter(f -> usedFiles.stream().anyMatch(u -> fuzzyMatch(f, u)))
                .count();

        return (double) hits / selectedFiles.size();
    }

    /**
     * Context Entropy Score — penalises extremes.
     *
     * <p>Uses dependency count and total result size as proxies for entropy.
     * Optimal context is moderate (3-5 dependencies, 2-8KB total).</p>
     *
     * @return score in [0, 1] — 1.0 for optimal entropy, lower for extremes
     */
    double computeEntropyScore(Map<String, String> depResults) {
        int numDeps = depResults.size();
        int totalLen = depResults.values().stream()
                .mapToInt(v -> v != null ? v.length() : 0)
                .sum();

        // Sigmoid-based proxy: peaks around 3 deps and 5000 chars
        double depFactor = 1.0 - Math.abs(sigmoid((numDeps - 3.0) / 2.0) - 0.5) * 2.0;
        double sizeFactor = 1.0 - Math.abs(sigmoid((totalLen - 5000.0) / 3000.0) - 0.5) * 2.0;

        return depFactor * 0.5 + sizeFactor * 0.5;
    }

    /**
     * KL Divergence Score — measures alignment between CM-selected and worker-used files.
     *
     * <p>Approximates the KL divergence between the two file distributions using
     * geometric mean of bidirectional coverage as a proxy:
     * {@code sqrt(coverage_used × coverage_selected)}, where:
     * <ul>
     *   <li>{@code coverage_used = overlap / |used|} — fraction of worker usage covered by CM</li>
     *   <li>{@code coverage_selected = overlap / |selected|} — fraction of CM selection relevant</li>
     * </ul>
     * Returns 1.0 when sets are identical, 0.0 when disjoint.</p>
     *
     * @return score in [0, 1], or 0.5 if either set is empty
     */
    double computeKlDivergenceScore(Set<String> selectedFiles, Set<String> usedFiles) {
        if (selectedFiles.isEmpty() || usedFiles.isEmpty()) {
            return 0.5; // neutral — insufficient data
        }

        long overlap = usedFiles.stream()
                .filter(u -> selectedFiles.stream().anyMatch(s -> fuzzyMatch(s, u)))
                .count();

        if (overlap == 0) {
            return 0.0; // complete mismatch — maximum divergence
        }

        double coverageUsed = (double) overlap / usedFiles.size();
        double coverageSelected = (double) overlap / selectedFiles.size();

        // Geometric mean: penalizes asymmetry (CM selects too many OR worker uses unexpected files)
        return Math.sqrt(coverageUsed * coverageSelected);
    }

    // ── Feedback helpers (package-private for testing) ───────────────────────

    /**
     * Files selected by CM but never referenced by the worker (wasted context).
     */
    Set<String> computeUnusedSelectedFiles(Set<String> selected, Set<String> used) {
        return selected.stream()
                .filter(s -> used.stream().noneMatch(u -> fuzzyMatch(s, u)))
                .collect(Collectors.toSet());
    }

    /**
     * Files used by the worker but not in CM selection (missing context).
     */
    Set<String> computeMissingFiles(Set<String> selected, Set<String> used) {
        return used.stream()
                .filter(u -> selected.stream().noneMatch(s -> fuzzyMatch(s, u)))
                .collect(Collectors.toSet());
    }

    static String buildSuggestion(Set<String> unused, Set<String> missing) {
        if (unused.isEmpty() && missing.isEmpty()) {
            return "Context well-aligned";
        }
        StringBuilder sb = new StringBuilder();
        if (!unused.isEmpty()) {
            sb.append("Consider removing ").append(unused.size()).append(" unused file(s): ")
              .append(unused.stream().sorted().limit(3).collect(Collectors.joining(", ")));
            if (unused.size() > 3) sb.append(", ...");
        }
        if (!missing.isEmpty()) {
            if (!sb.isEmpty()) sb.append(". ");
            sb.append("Consider adding ").append(missing.size()).append(" missing file(s): ")
              .append(missing.stream().sorted().limit(3).collect(Collectors.joining(", ")));
            if (missing.size() > 3) sb.append(", ...");
        }
        return sb.toString();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Reconstructs the dependency results map by querying completed sibling PlanItems.
     * Mirrors the logic of {@code OrchestrationService.buildContextJson()} but
     * at completion time rather than dispatch time.
     */
    private Map<String, String> reconstructDependencyResults(PlanItem item) {
        List<String> dependsOn = item.getDependsOn();
        if (dependsOn == null || dependsOn.isEmpty()) {
            return Collections.emptyMap();
        }

        // Load all completed items in this plan and index by taskKey
        List<PlanItem> planItems = planItemRepository.findByPlanIdAndStatus(
                item.getPlan().getId(), ItemStatus.DONE);
        Map<String, String> resultsByKey = new HashMap<>();
        for (PlanItem pi : planItems) {
            if (pi.getResult() != null) {
                resultsByKey.put(pi.getTaskKey(), pi.getResult());
            }
        }

        // Build the dependency map (same keys as buildContextJson)
        Map<String, String> depResults = new LinkedHashMap<>();
        for (String depKey : dependsOn) {
            String depResult = resultsByKey.get(depKey);
            if (depResult != null) {
                depResults.put(depKey, depResult);
            }
        }

        return depResults;
    }

    Set<String> extractSelectedFiles(Map<String, String> depResults) {
        Set<String> files = new HashSet<>();
        for (String depResult : depResults.values()) {
            if (depResult == null || depResult.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(depResult);
                if (node.has("relevant_files") && node.get("relevant_files").isArray()) {
                    for (JsonNode f : node.get("relevant_files")) {
                        files.add(normalizeFilePath(f.asText()));
                    }
                }
            } catch (Exception ignored) {
                // dependency result is not JSON — skip
            }
        }
        return files;
    }

    Set<String> extractUsedFiles(AgentResult result) {
        Set<String> files = new HashSet<>();

        // Extract from result JSON — look for file paths
        if (result.resultJson() != null) {
            try {
                JsonNode root = objectMapper.readTree(result.resultJson());
                extractFilePathsFromJson(root, files);
            } catch (Exception ignored) {
            }
        }

        // Extract from file modifications (G3)
        if (result.fileModifications() != null) {
            for (var fm : result.fileModifications()) {
                files.add(normalizeFilePath(fm.filePath()));
            }
        }

        return files;
    }

    private void extractFilePathsFromJson(JsonNode node, Set<String> paths) {
        if (node.isTextual()) {
            String text = node.asText();
            if (looksLikeFilePath(text)) {
                paths.add(normalizeFilePath(text));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                extractFilePathsFromJson(child, paths);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey().toLowerCase();
                if (key.contains("file") || key.contains("path") || key.contains("artifact")) {
                    extractFilePathsFromJson(entry.getValue(), paths);
                }
            });
        }
    }

    private static boolean looksLikeFilePath(String text) {
        return text != null && text.length() > 2 && text.length() < 300
                && (text.contains("/") || text.contains("\\"))
                && !text.contains(" ") && !text.startsWith("http");
    }

    static String normalizeFilePath(String path) {
        if (path == null) return "";
        String normalized = path.replace("\\", "/");
        if (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    /**
     * Fuzzy path matching: checks if either path contains the other as a substring.
     * Handles different path representations (relative vs absolute, different roots).
     */
    private static boolean fuzzyMatch(String a, String b) {
        return a.contains(b) || b.contains(a);
    }

    static boolean isInfrastructureWorker(WorkerType wt) {
        return wt == WorkerType.CONTEXT_MANAGER
                || wt == WorkerType.HOOK_MANAGER
                || wt == WorkerType.SCHEMA_MANAGER
                || wt == WorkerType.REVIEW
                || wt == WorkerType.COMPENSATOR_MANAGER
                || wt == WorkerType.TOOL_MANAGER
                || wt == WorkerType.TASK_MANAGER
                || wt == WorkerType.RAG_MANAGER;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
