package com.agentframework.orchestrator.gp;

import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Serendipity file ranking: discovers files that were historically surprising useful
 * for similar tasks, based on GP residual (|actual_reward - gp_mu|).
 *
 * <p>Two responsibilities:</p>
 * <ol>
 *   <li><b>Collection</b> ({@link #collectFileOutcomes}): when a domain worker completes,
 *       extracts file paths from its Context Manager dependency results and stores them
 *       with the task's GP residual.</li>
 *   <li><b>Query</b> ({@link #getSerendipityHints}): at dispatch time, finds similar
 *       past task outcomes via HNSW cosine similarity and returns files ranked by
 *       similarity × residual.</li>
 * </ol>
 *
 * <p>Only created when GP is enabled ({@code TaskOutcomeService} bean exists).</p>
 */
@Service
@ConditionalOnProperty(prefix = "gp", name = "enabled", havingValue = "true")
public class SerendipityService {

    private static final Logger log = LoggerFactory.getLogger(SerendipityService.class);

    static final float MIN_RESIDUAL = 0.15f;
    static final int MAX_SIMILAR_OUTCOMES = 20;
    static final int MAX_SERENDIPITY_FILES = 5;

    /** Domain worker types that produce meaningful task outcomes for serendipity tracking. */
    private static final Set<WorkerType> DOMAIN_WORKER_TYPES = Set.of(
            WorkerType.BE, WorkerType.FE, WorkerType.AI_TASK, WorkerType.CONTRACT);

    private final TaskOutcomeService taskOutcomeService;
    private final TaskOutcomeRepository taskOutcomeRepository;
    private final ContextFileOutcomeRepository fileOutcomeRepository;
    private final PlanItemRepository planItemRepository;
    private final ObjectMapper objectMapper;

    public SerendipityService(TaskOutcomeService taskOutcomeService,
                               TaskOutcomeRepository taskOutcomeRepository,
                               ContextFileOutcomeRepository fileOutcomeRepository,
                               PlanItemRepository planItemRepository,
                               ObjectMapper objectMapper) {
        this.taskOutcomeService = taskOutcomeService;
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.fileOutcomeRepository = fileOutcomeRepository;
        this.planItemRepository = planItemRepository;
        this.objectMapper = objectMapper;
    }

    // ── Collection (on task completion) ─────────────────────────────────────────

    /**
     * Collects file-task associations from a completed domain worker's Context Manager
     * dependency results. Only stores associations where the GP residual exceeds
     * {@link #MIN_RESIDUAL}.
     *
     * <p>Called from {@code OrchestrationService.onTaskCompleted()} after reward update.</p>
     */
    @Transactional
    public void collectFileOutcomes(PlanItem domainItem) {
        if (!DOMAIN_WORKER_TYPES.contains(domainItem.getWorkerType())) {
            return;
        }
        if (domainItem.getAggregatedReward() == null) {
            return;
        }

        UUID planItemId = domainItem.getId();
        UUID planId = domainItem.getPlan().getId();

        // Find the task_outcome for this domain item
        List<Object[]> outcomeRows = taskOutcomeRepository.findOutcomeByPlanItemId(planItemId);
        if (outcomeRows.isEmpty()) {
            return; // no GP outcome recorded (GP might have been disabled at dispatch)
        }

        Object[] outcomeRow = outcomeRows.get(0);
        UUID taskOutcomeId = (UUID) outcomeRow[0];
        Number gpMu = (Number) outcomeRow[1];
        Number actualReward = (Number) outcomeRow[2];

        if (gpMu == null || actualReward == null) {
            return;
        }

        // Already collected? (idempotency guard for redelivered events)
        if (fileOutcomeRepository.existsByTaskOutcomeId(taskOutcomeId)) {
            return;
        }

        float residual = (float) Math.abs(actualReward.doubleValue() - gpMu.doubleValue());
        if (residual < MIN_RESIDUAL) {
            return; // not informative enough
        }

        // Find Context Manager tasks in this plan that are direct dependencies of the domain task
        List<String> filePaths = extractCmFilePaths(domainItem, planId);
        if (filePaths.isEmpty()) {
            return;
        }

        // Store one ContextFileOutcome per file
        List<ContextFileOutcome> outcomes = filePaths.stream()
                .map(path -> new ContextFileOutcome(
                        UUID.randomUUID(), taskOutcomeId, planId,
                        domainItem.getTaskKey(), path, residual))
                .toList();

        fileOutcomeRepository.saveAll(outcomes);
        log.debug("[Serendipity] Collected {} file outcomes for task {} (residual={:.2f}, plan={})",
                  outcomes.size(), domainItem.getTaskKey(),
                  residual, planId);
    }

    /**
     * Extracts file paths from Context Manager dependency results of a domain item.
     * Walks the item's dependsOn list and finds CM tasks, parsing their result JSON.
     */
    List<String> extractCmFilePaths(PlanItem domainItem, UUID planId) {
        List<PlanItem> planItems = planItemRepository.findByPlanId(planId);
        Map<String, PlanItem> byTaskKey = planItems.stream()
                .collect(Collectors.toMap(PlanItem::getTaskKey, p -> p, (a, b) -> a));

        Set<String> filePaths = new LinkedHashSet<>();

        for (String depKey : domainItem.getDependsOn()) {
            PlanItem dep = byTaskKey.get(depKey);
            if (dep != null && dep.getWorkerType() == WorkerType.CONTEXT_MANAGER
                    && dep.getResult() != null) {
                filePaths.addAll(parseRelevantFiles(dep.getResult()));
            }
        }

        return new ArrayList<>(filePaths);
    }

    /**
     * Parses the Context Manager result JSON to extract file paths from the
     * {@code relevant_files} array.
     *
     * <p>Expected format:</p>
     * <pre>{@code
     * {
     *   "relevant_files": [
     *     {"path": "src/main/java/Foo.java", "reason": "..."},
     *     ...
     *   ]
     * }
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    List<String> parseRelevantFiles(String resultJson) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(resultJson, Map.class);
            Object rf = parsed.get("relevant_files");
            if (rf instanceof List<?> list) {
                return list.stream()
                        .filter(item -> item instanceof Map)
                        .map(item -> ((Map<String, Object>) item).get("path"))
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .filter(s -> !s.isBlank())
                        .toList();
            }
        } catch (Exception e) {
            log.debug("[Serendipity] Failed to parse CM result JSON: {}", e.getMessage());
        }
        return List.of();
    }

    // ── Query (at dispatch time) ────────────────────────────────────────────────

    /**
     * Returns a ranked list of file paths that were historically surprising useful
     * for tasks similar to the given title/description.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Embed the task text via {@code TaskOutcomeService.embedTask()}</li>
     *   <li>Find top-20 similar past task outcomes via pgvector HNSW</li>
     *   <li>Load file outcomes for those tasks with residual ≥ 0.15</li>
     *   <li>Aggregate: {@code score(file) = Σ(similarity × residual)}</li>
     *   <li>Return top-5 files ranked by score</li>
     * </ol>
     */
    public List<SerendipityHint> getSerendipityHints(String title, String description) {
        float[] embedding = taskOutcomeService.embedTask(title, description);
        String embStr = TaskOutcomeService.floatArrayToString(embedding);

        // Step 1: find similar task outcomes
        List<Object[]> similarRows = taskOutcomeRepository.findSimilarOutcomes(
                embStr, MAX_SIMILAR_OUTCOMES);
        if (similarRows.isEmpty()) {
            return List.of();
        }

        // Build similarity map: taskOutcomeId → similarity score
        Map<UUID, Double> similarityMap = new HashMap<>();
        List<UUID> outcomeIds = new ArrayList<>();
        for (Object[] row : similarRows) {
            UUID id = (UUID) row[0];
            Number similarity = (Number) row[7]; // similarity column (index 7)
            outcomeIds.add(id);
            similarityMap.put(id, similarity.doubleValue());
        }

        // Step 2: load file outcomes for those task outcomes
        List<ContextFileOutcome> fileOutcomes = fileOutcomeRepository
                .findByOutcomeIdsAndMinResidual(outcomeIds, MIN_RESIDUAL);
        if (fileOutcomes.isEmpty()) {
            return List.of();
        }

        // Step 3: aggregate score(file) = Σ(similarity × residual)
        Map<String, Double> fileScores = new HashMap<>();
        for (ContextFileOutcome cfo : fileOutcomes) {
            double sim = similarityMap.getOrDefault(cfo.getTaskOutcomeId(), 0.0);
            fileScores.merge(cfo.getFilePath(), sim * cfo.getResidual(), Double::sum);
        }

        // Step 4: rank and return top N
        List<SerendipityHint> hints = fileScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(MAX_SERENDIPITY_FILES)
                .map(e -> new SerendipityHint(e.getKey(), e.getValue()))
                .toList();

        if (!hints.isEmpty()) {
            log.debug("[Serendipity] Found {} hints for '{}': {}",
                      hints.size(), title,
                      hints.stream().map(h -> h.filePath() + "(" + String.format("%.3f", h.score()) + ")")
                           .collect(Collectors.joining(", ")));
        }
        return hints;
    }

    private static boolean isDomainWorker(WorkerType type) {
        return DOMAIN_WORKER_TYPES.contains(type);
    }

    /**
     * A serendipity hint: a file path with its aggregated score (similarity × residual).
     */
    public record SerendipityHint(String filePath, double score) {}
}
