package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements Functional Decision Theory (FDT/TDT) for reflective dispatch.
 *
 * <p>Standard expected-utility maximization picks the best worker for the
 * <em>current</em> task in isolation. FDT instead asks: "Which profile policy
 * would have produced the best aggregate reward across all historically similar
 * tasks?" The recommended profile is the one that wins under this timeless,
 * policy-level evaluation.</p>
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Retrieve the N most similar historical task outcomes (cosine similarity
 *       on pgvector embeddings), filtered to the given workerType.</li>
 *   <li>Further filter to outcomes whose cosine similarity ≥ threshold.</li>
 *   <li>If fewer than minSimilarTasks outcomes pass, return null (insufficient data).</li>
 *   <li>Group by workerProfile and compute mean actual_reward per profile.</li>
 *   <li>Recommend the profile with the highest mean reward — the globally optimal policy.</li>
 * </ol>
 * </p>
 *
 * @see <a href="https://arxiv.org/abs/1710.05060">Soares &amp; Leike (2017), Functional Decision Theory</a>
 */
@Service
@ConditionalOnProperty(prefix = "fdt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReflectiveDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ReflectiveDispatchService.class);

    private static final int CANDIDATE_FETCH_LIMIT = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${fdt.similarity-threshold:0.85}")
    private double similarityThreshold;

    @Value("${fdt.min-similar-tasks:3}")
    private int minSimilarTasks;

    public ReflectiveDispatchService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes the FDT-optimal worker profile for a task represented by its embedding.
     *
     * @param taskEmbeddingText embedding vector as PostgreSQL text (e.g. "[0.1,0.2,...]")
     * @param workerType        worker type to constrain the search (e.g. "BE")
     * @return FDT report with recommended profile and policy reward, or null if insufficient data
     */
    public FDTDispatchReport computeReflectivePolicy(String taskEmbeddingText, String workerType) {
        // findSimilarOutcomes columns:
        // [0]=id(UUID), [1]=plan_id(UUID), [2]=task_key(String), [3]=worker_type(String),
        // [4]=worker_profile(String), [5]=gp_mu(Number), [6]=actual_reward(Number), [7]=similarity(Number)
        List<Object[]> candidates = taskOutcomeRepository.findSimilarOutcomes(
                taskEmbeddingText, CANDIDATE_FETCH_LIMIT);

        // Filter: correct worker_type AND similarity ≥ threshold AND reward non-null
        List<Object[]> filtered = candidates.stream()
                .filter(row -> workerType.equals(row[3])
                        && row[7] != null
                        && ((Number) row[7]).doubleValue() >= similarityThreshold
                        && row[6] != null)
                .collect(Collectors.toList());

        if (filtered.size() < minSimilarTasks) {
            log.debug("FDT for workerType={}: only {} similar tasks (threshold={}), need {}",
                    workerType, filtered.size(), similarityThreshold, minSimilarTasks);
            return null;
        }

        // Group by worker_profile: compute mean actual_reward (the "policy reward")
        Map<String, DoubleSummaryStatistics> byProfile = filtered.stream()
                .collect(Collectors.groupingBy(
                        row -> (String) row[4],
                        Collectors.summarizingDouble(row -> ((Number) row[6]).doubleValue())));

        // FDT choice: profile with highest mean reward across all similar tasks
        String bestProfile = byProfile.entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue().getAverage()))
                .map(Map.Entry::getKey)
                .orElse(null);

        double policyReward = bestProfile != null ? byProfile.get(bestProfile).getAverage() : 0.0;

        List<String> similarTaskKeys = filtered.stream()
                .map(row -> (String) row[2])
                .distinct()
                .collect(Collectors.toList());

        log.debug("FDT for workerType={}: {} similar tasks, recommended='{}', policyReward={}",
                workerType, filtered.size(), bestProfile, String.format("%.4f", policyReward));

        return new FDTDispatchReport(bestProfile, policyReward, similarTaskKeys, filtered.size());
    }

    /**
     * FDT dispatch recommendation.
     *
     * @param recommendedProfile worker profile that wins under the timeless policy evaluation
     * @param policyReward       mean reward of the recommended profile over similar tasks
     * @param similarTaskKeys    task keys of the historically similar tasks used
     * @param similarCount       number of similar tasks found above the similarity threshold
     */
    public record FDTDispatchReport(
            String recommendedProfile,
            double policyReward,
            List<String> similarTaskKeys,
            int similarCount
    ) {}
}
