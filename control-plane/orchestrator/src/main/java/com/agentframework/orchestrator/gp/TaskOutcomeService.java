package com.agentframework.orchestrator.gp;

import com.agentframework.gp.config.GpProperties;
import com.agentframework.gp.engine.GaussianProcessEngine;
import com.agentframework.gp.engine.GpModelCache;
import com.agentframework.gp.model.GpPosterior;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.gp.model.TrainingPoint;
import com.agentframework.orchestrator.reward.WorkerEloStats;
import com.agentframework.orchestrator.reward.WorkerEloStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bridges the GP engine with the orchestrator domain.
 *
 * <p>Responsibilities:</p>
 * <ol>
 *   <li>Embed task text (title + description) via Spring AI {@link EmbeddingModel}</li>
 *   <li>Record outcome at dispatch time (embedding, ELO snapshot, GP prediction)</li>
 *   <li>Update actual_reward when task completes</li>
 *   <li>Load training data and fit/cache GP for prediction</li>
 * </ol>
 *
 * <p>Only created when {@code gp.enabled=true} (GP beans exist in context).</p>
 */
@Service
@ConditionalOnProperty(prefix = "gp", name = "enabled", havingValue = "true")
public class TaskOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(TaskOutcomeService.class);

    private final TaskOutcomeRepository outcomeRepository;
    private final WorkerEloStatsRepository eloStatsRepository;
    private final GaussianProcessEngine gpEngine;
    private final EmbeddingModel embeddingModel;
    private final GpProperties properties;
    private final GpModelCache cache;

    public TaskOutcomeService(TaskOutcomeRepository outcomeRepository,
                              WorkerEloStatsRepository eloStatsRepository,
                              GaussianProcessEngine gpEngine,
                              EmbeddingModel embeddingModel,
                              GpProperties properties) {
        this.outcomeRepository = outcomeRepository;
        this.eloStatsRepository = eloStatsRepository;
        this.gpEngine = gpEngine;
        this.embeddingModel = embeddingModel;
        this.properties = properties;

        var cacheConfig = properties.cache();
        int ttl = cacheConfig != null ? cacheConfig.ttlMinutes() : 5;
        this.cache = new GpModelCache(Duration.ofMinutes(ttl));
    }

    /**
     * Embeds the task text (title + description) into a 1024-dim vector.
     */
    public float[] embedTask(String title, String description) {
        String text = title + ": " + (description != null ? description : "");
        return embeddingModel.embed(text);
    }

    /**
     * Predicts expected reward for a task+profile combination.
     *
     * @param embedding   task embedding (1024 dim)
     * @param workerType  e.g. "BE"
     * @param profile     e.g. "be-java"
     * @return GP prediction (mu, sigma2), or prior if insufficient data
     */
    public GpPrediction predict(float[] embedding, String workerType, String profile) {
        String cacheKey = GpModelCache.cacheKey(workerType, profile);

        // Try cached posterior first
        GpPosterior posterior = cache.get(cacheKey);
        if (posterior != null) {
            return gpEngine.predict(posterior, embedding);
        }

        // Load training data
        List<TrainingPoint> trainingData = loadTrainingData(workerType, profile);
        if (trainingData.isEmpty()) {
            return gpEngine.prior(properties.defaultPriorMean());
        }

        // Fit GP and cache
        posterior = gpEngine.fit(trainingData);
        if (posterior == null) {
            return gpEngine.prior(properties.defaultPriorMean());
        }

        if (properties.cache() == null || properties.cache().enabled()) {
            cache.put(cacheKey, posterior);
        }

        return gpEngine.predict(posterior, embedding);
    }

    /**
     * Records a task outcome at dispatch time.
     */
    @Transactional
    public void recordOutcomeAtDispatch(UUID planItemId, UUID planId, String taskKey,
                                         String workerType, String workerProfile,
                                         float[] embedding, GpPrediction prediction) {
        UUID id = UUID.randomUUID();

        // Snapshot current ELO for this profile
        Double eloSnapshot = eloStatsRepository.findById(workerProfile)
                .map(WorkerEloStats::getEloRating)
                .orElse(null);

        String embeddingStr = floatArrayToString(embedding);

        outcomeRepository.insertWithEmbedding(
                id, planItemId, planId, taskKey, workerType, workerProfile,
                embeddingStr, eloSnapshot,
                prediction != null ? prediction.mu() : null,
                prediction != null ? prediction.sigma2() : null);

        log.debug("Recorded task outcome at dispatch: {} (type={}, profile={}, mu={}, sigma2={})",
                  taskKey, workerType, workerProfile,
                  prediction != null ? String.format("%.4f", prediction.mu()) : "null",
                  prediction != null ? String.format("%.4f", prediction.sigma2()) : "null");
    }

    /**
     * Updates actual reward after task completion. Invalidates the GP cache for this profile.
     */
    @Transactional
    public void updateReward(UUID planItemId, double reward, String workerType, String profile) {
        int updated = outcomeRepository.updateActualReward(planItemId, reward);
        if (updated > 0) {
            cache.invalidate(GpModelCache.cacheKey(workerType, profile));
            log.debug("Updated actual_reward={} for planItemId={}, invalidated cache for {}:{}",
                      String.format("%.4f", reward), planItemId, workerType, profile);
        }
    }

    /**
     * Loads training data from task_outcomes and converts to TrainingPoints.
     */
    private List<TrainingPoint> loadTrainingData(String workerType, String profile) {
        List<Object[]> rows = outcomeRepository.findTrainingDataRaw(
                workerType, profile, properties.maxTrainingSize());

        List<TrainingPoint> points = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String embeddingText = (String) row[6]; // embedding_text column
            Number reward = (Number) row[10];       // actual_reward column
            if (embeddingText != null && reward != null) {
                float[] emb = parseEmbeddingText(embeddingText);
                if (emb != null) {
                    points.add(new TrainingPoint(emb, reward.doubleValue(), profile));
                }
            }
        }
        return points;
    }

    /**
     * Converts float[] to pgvector text format: "[0.1,0.2,0.3,...]"
     */
    static String floatArrayToString(float[] arr) {
        if (arr == null) return null;
        var sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Parses pgvector text format "[0.1,0.2,0.3,...]" to float[].
     */
    static float[] parseEmbeddingText(String text) {
        if (text == null || text.length() < 3) return null;
        // Remove brackets
        String inner = text.startsWith("[") ? text.substring(1, text.length() - 1) : text;
        String[] parts = inner.split(",");
        float[] result = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse embedding text (length={}): {}", text.length(), e.getMessage());
            return null;
        }
        return result;
    }
}
