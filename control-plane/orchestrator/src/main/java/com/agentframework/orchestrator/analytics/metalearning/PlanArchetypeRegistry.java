package com.agentframework.orchestrator.analytics.metalearning;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry of plan archetypes for cross-plan meta-learning and few-shot transfer.
 *
 * <p>Lineage: Case-Based Planning (Gerevini et al. JAIR 2023) → Skill Libraries
 * (Voyager, NeurIPS 2023 spotlight) → AgentReuse (Li et al. 2024, 93% reuse rate via
 * intent classification). Two-stage retrieval: pgvector embedding (coarse) + structural
 * similarity (fine).</p>
 *
 * <p>Also stores failed archetypes as contrastive signal (ETO, Song et al. ACL 2024)
 * — knowing what <em>didn't</em> work is as valuable as knowing what did.</p>
 *
 * @see <a href="https://arxiv.org/abs/2305.16291">Wang et al., Voyager (NeurIPS 2023)</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.plan-archetype", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(PlanArchetypeConfig.class)
public class PlanArchetypeRegistry {

    private static final Logger log = LoggerFactory.getLogger(PlanArchetypeRegistry.class);

    private final TaskOutcomeService outcomeService;
    private final PlanArchetypeConfig config;

    private final ConcurrentHashMap<UUID, PlanArchetype> archetypes = new ConcurrentHashMap<>();

    public PlanArchetypeRegistry(TaskOutcomeService outcomeService, PlanArchetypeConfig config) {
        this.outcomeService = outcomeService;
        this.config = config;
    }

    /**
     * Two-stage retrieval: pgvector cosine (coarse) + structural similarity (fine).
     *
     * @param spec       the plan specification to match against
     * @param maxResults maximum number of matches to return
     * @return ranked list of archetype matches (positive + contrastive)
     */
    public List<ArchetypeMatch> findSimilar(String spec, int maxResults) {
        if (spec == null || spec.isBlank() || archetypes.isEmpty()) return List.of();

        float[] queryEmbedding = outcomeService.embedTask(spec, spec);

        // Stage 1: cosine similarity (coarse filter, top-20)
        List<ArchetypeMatch> coarseMatches = archetypes.values().stream()
                .map(a -> {
                    double sim = cosineSimilarity(queryEmbedding, a.embedding());
                    return new ArchetypeMatch(a, sim, a.failed());
                })
                .filter(m -> m.similarity() >= config.similarityThreshold())
                .sorted(Comparator.comparingDouble(ArchetypeMatch::similarity).reversed())
                .limit(20)
                .collect(Collectors.toList());

        // Stage 2: re-rank by quality score (structural similarity proxy)
        return coarseMatches.stream()
                .sorted(Comparator.comparingDouble(m -> -qualityScore(m)))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * Registers a completed plan as a positive archetype.
     */
    public void registerArchetype(Plan completedPlan) {
        if (completedPlan == null || completedPlan.getSpec() == null) return;

        double successRate = computeSuccessRate(completedPlan);
        double avgReward = computeAvgReward(completedPlan);

        float[] embedding = outcomeService.embedTask(completedPlan.getSpec(), completedPlan.getSpec());

        // Deduplication: check if very similar archetype exists
        Optional<PlanArchetype> duplicate = findDuplicate(embedding);
        if (duplicate.isPresent()) {
            updateExisting(duplicate.get(), successRate, avgReward);
            return;
        }

        // Evict if at capacity (LRU by usage count then creation time)
        evictIfNeeded();

        UUID archetypeId = UUID.randomUUID();
        PlanArchetype archetype = new PlanArchetype(
                archetypeId,
                truncate(completedPlan.getSpec(), 500),
                completedPlan.getItems() != null ? completedPlan.getItems().size() : 0,
                embedding,
                successRate,
                avgReward,
                1,
                false,
                extractWorkerDistribution(completedPlan),
                completedPlan.getDepth(),
                Instant.now()
        );

        archetypes.put(archetypeId, archetype);
        log.debug("Registered archetype: id={} tasks={} successRate={} avgReward={}",
                archetypeId, archetype.taskCount(), String.format("%.2f", successRate),
                String.format("%.3f", avgReward));
    }

    /**
     * Registers a failed plan as a contrastive archetype.
     * Contrastive archetypes teach the system what <em>not</em> to repeat.
     */
    public void registerFailedArchetype(Plan failedPlan) {
        if (failedPlan == null || failedPlan.getSpec() == null) return;

        float[] embedding = outcomeService.embedTask(failedPlan.getSpec(), failedPlan.getSpec());

        Optional<PlanArchetype> duplicate = findDuplicate(embedding);
        if (duplicate.isPresent()) return; // Already have this failure pattern

        evictIfNeeded();

        UUID archetypeId = UUID.randomUUID();
        PlanArchetype archetype = new PlanArchetype(
                archetypeId,
                truncate(failedPlan.getSpec(), 500),
                failedPlan.getItems() != null ? failedPlan.getItems().size() : 0,
                embedding,
                0.0,
                computeAvgReward(failedPlan),
                0,
                true,
                extractWorkerDistribution(failedPlan),
                failedPlan.getDepth(),
                Instant.now()
        );

        archetypes.put(archetypeId, archetype);
        log.debug("Registered contrastive archetype: id={} tasks={}", archetypeId, archetype.taskCount());
    }

    public int size() {
        return archetypes.size();
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private double qualityScore(ArchetypeMatch match) {
        PlanArchetype a = match.archetype();
        if (a.failed()) return match.similarity() * 0.5; // Contrastive = lower priority

        double freshness = Math.max(0, 1.0 - (Instant.now().getEpochSecond() - a.createdAt().getEpochSecond())
                / (86400.0 * 90)); // 90-day decay
        return a.successRate() * 0.4 + a.avgReward() * 0.3 + freshness * 0.2 + match.similarity() * 0.1;
    }

    private Optional<PlanArchetype> findDuplicate(float[] embedding) {
        return archetypes.values().stream()
                .filter(a -> cosineSimilarity(embedding, a.embedding()) >= config.deduplicationThreshold())
                .findFirst();
    }

    private void updateExisting(PlanArchetype existing, double newSuccessRate, double newAvgReward) {
        // Incremental update: weighted average
        int n = existing.usageCount() + 1;
        double updatedSuccessRate = existing.successRate() + (newSuccessRate - existing.successRate()) / n;
        double updatedAvgReward = existing.avgReward() + (newAvgReward - existing.avgReward()) / n;

        PlanArchetype updated = new PlanArchetype(
                existing.archetypeId(), existing.specSummary(), existing.taskCount(),
                existing.embedding(), updatedSuccessRate, updatedAvgReward, n,
                existing.failed(), existing.workerDistribution(), existing.planDepth(), existing.createdAt()
        );
        archetypes.put(existing.archetypeId(), updated);
        log.debug("Updated archetype: id={} usageCount={}", existing.archetypeId(), n);
    }

    private void evictIfNeeded() {
        while (archetypes.size() >= config.maxArchetypes()) {
            // Evict: lowest usage count, then oldest
            Optional<UUID> victim = archetypes.entrySet().stream()
                    .min(Comparator.<Map.Entry<UUID, PlanArchetype>>comparingInt(e -> e.getValue().usageCount())
                            .thenComparing(e -> e.getValue().createdAt()))
                    .map(Map.Entry::getKey);
            victim.ifPresent(id -> {
                archetypes.remove(id);
                log.debug("Evicted archetype: id={}", id);
            });
        }
    }

    private double computeSuccessRate(Plan plan) {
        if (plan.getItems() == null || plan.getItems().isEmpty()) return 0.0;
        long done = plan.getItems().stream().filter(i -> i.getStatus() == ItemStatus.DONE).count();
        return (double) done / plan.getItems().size();
    }

    private double computeAvgReward(Plan plan) {
        if (plan.getItems() == null) return 0.0;
        return plan.getItems().stream()
                .filter(i -> i.getAggregatedReward() != null)
                .mapToDouble(i -> i.getAggregatedReward())
                .average()
                .orElse(0.0);
    }

    private Map<WorkerType, Integer> extractWorkerDistribution(Plan plan) {
        if (plan.getItems() == null) return Map.of();
        return plan.getItems().stream()
                .filter(i -> i.getWorkerType() != null)
                .collect(Collectors.groupingBy(PlanItem::getWorkerType, Collectors.summingInt(i -> 1)));
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom > 0 ? dot / denom : 0.0;
    }

    private static String truncate(String s, int maxLength) {
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * A plan archetype: distilled pattern from a completed or failed plan.
     *
     * @param archetypeId        unique identifier
     * @param specSummary        truncated specification text
     * @param taskCount          number of tasks in the original plan
     * @param embedding          pgvector embedding of the spec (1024 dim)
     * @param successRate        ratio of DONE tasks (0.0 for failed archetypes)
     * @param avgReward          average aggregated reward across tasks
     * @param usageCount         number of times this archetype was matched/used
     * @param failed             true if this is a contrastive (negative) archetype
     * @param workerDistribution worker type distribution in the original plan
     * @param planDepth          nesting depth of the original plan
     * @param createdAt          when the archetype was registered
     */
    public record PlanArchetype(
            UUID archetypeId,
            String specSummary,
            int taskCount,
            float[] embedding,
            double successRate,
            double avgReward,
            int usageCount,
            boolean failed,
            Map<WorkerType, Integer> workerDistribution,
            int planDepth,
            Instant createdAt
    ) {}

    /**
     * A match result from archetype retrieval.
     *
     * @param archetype   the matched archetype
     * @param similarity  cosine similarity to the query spec
     * @param contrastive true if this is a negative example (failed plan)
     */
    public record ArchetypeMatch(
            PlanArchetype archetype,
            double similarity,
            boolean contrastive
    ) {}
}
