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
import org.springframework.stereotype.Service;

import java.util.*;

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
 *   <li><b>File Relevance</b> (weight 0.6): ratio of CM-selected files that appear
 *       in the worker's result or file modifications (proxy for Mutual Information)</li>
 *   <li><b>Context Entropy penalty</b> (weight 0.4): penalises contexts that are
 *       either too broad (many large dependencies) or too narrow (single tiny dep)</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(prefix = "gp", name = "enabled", havingValue = "true")
public class ContextQualityService {

    private static final Logger log = LoggerFactory.getLogger(ContextQualityService.class);

    private static final double FILE_RELEVANCE_WEIGHT = 0.6;
    private static final double ENTROPY_WEIGHT = 0.4;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final PlanItemRepository planItemRepository;
    private final ObjectMapper objectMapper;

    public ContextQualityService(TaskOutcomeRepository taskOutcomeRepository,
                                  PlanItemRepository planItemRepository,
                                  ObjectMapper objectMapper) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.planItemRepository = planItemRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Computes context quality for a completed task, persists it in task_outcomes,
     * and returns the composite score for reward integration.
     *
     * <p>Reconstructs the context from the item's completed dependencies
     * (the same data that was sent at dispatch time via {@code buildContextJson}).</p>
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

        double fileRelevance = computeFileRelevance(depResults, result);
        double entropyScore = computeEntropyScore(depResults);
        double composite = FILE_RELEVANCE_WEIGHT * fileRelevance
                         + ENTROPY_WEIGHT * entropyScore;

        // Clamp to [0, 1]
        composite = Math.max(0.0, Math.min(1.0, composite));

        // Persist to task_outcomes
        taskOutcomeRepository.updateContextQualityScore(item.getId(), composite);

        log.debug("Context quality: task={} fileRelevance={} entropy={} composite={}",
                  item.getTaskKey(),
                  String.format("%.3f", fileRelevance),
                  String.format("%.3f", entropyScore),
                  String.format("%.3f", composite));

        return composite;
    }

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
     * Files are extracted from {@code relevant_files} arrays in dependency results
     * and matched against file paths in the result JSON and file modifications.</p>
     *
     * @return score in [0, 1], or 0.5 if no file selection data available
     */
    double computeFileRelevance(Map<String, String> depResults, AgentResult result) {
        Set<String> selectedFiles = extractSelectedFiles(depResults);
        if (selectedFiles.isEmpty()) {
            return 0.5; // neutral — no CM dependency with relevant_files
        }

        Set<String> usedFiles = extractUsedFiles(result);
        if (usedFiles.isEmpty()) {
            return 0.5; // worker didn't report file usage
        }

        long hits = selectedFiles.stream()
                .filter(f -> usedFiles.stream().anyMatch(u -> u.contains(f) || f.contains(u)))
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

    private Set<String> extractSelectedFiles(Map<String, String> depResults) {
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

    private Set<String> extractUsedFiles(AgentResult result) {
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

    private static String normalizeFilePath(String path) {
        if (path == null) return "";
        String normalized = path.replace("\\", "/");
        if (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    private static boolean isInfrastructureWorker(WorkerType wt) {
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
