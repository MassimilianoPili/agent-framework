package com.agentframework.orchestrator.gp;

import com.agentframework.gp.engine.GaussianProcessEngine;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GP-based worker profile selection.
 *
 * <p>For multi-profile worker types (e.g. BE: be-java, be-go, be-rust, be-node),
 * predicts expected reward for each candidate and selects the best.</p>
 *
 * <p>Cold-start: with 0 training data, all predictions are the prior
 * (identical mu, identical sigma2), and the default profile wins (tie-break).
 * This ensures zero behavioral change until training data accumulates.</p>
 */
@Service
@ConditionalOnBean(GaussianProcessEngine.class)
public class GpWorkerSelectionService {

    private static final Logger log = LoggerFactory.getLogger(GpWorkerSelectionService.class);

    private final TaskOutcomeService outcomeService;
    private final WorkerProfileRegistry profileRegistry;

    public GpWorkerSelectionService(TaskOutcomeService outcomeService,
                                     WorkerProfileRegistry profileRegistry) {
        this.outcomeService = outcomeService;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Selects the best worker profile for a task.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>List all profiles for workerType from registry</li>
     *   <li>If 0 or 1 profiles → return default (skip GP)</li>
     *   <li>Embed task text (title + description)</li>
     *   <li>For each profile, predict expected reward</li>
     *   <li>Select profile with highest mu (greedy exploitation)</li>
     *   <li>Tie-break: prefer the default profile (backward compatibility)</li>
     * </ol>
     *
     * @return selection result including predictions for all candidates
     */
    public ProfileSelection selectProfile(WorkerType workerType, String title, String description) {
        List<String> candidates = profileRegistry.profilesForWorkerType(workerType);
        String defaultProfile = profileRegistry.resolveDefaultProfile(workerType);

        // Trivial case: 0 or 1 profiles — no selection needed
        if (candidates.size() <= 1) {
            String profile = candidates.isEmpty()
                    ? (defaultProfile != null ? defaultProfile : workerType.name().toLowerCase())
                    : candidates.get(0);
            return new ProfileSelection(profile, null, Map.of());
        }

        // Embed task text
        float[] embedding = outcomeService.embedTask(title, description);

        // Predict for each candidate
        Map<String, GpPrediction> predictions = new LinkedHashMap<>();
        for (String profile : candidates) {
            GpPrediction pred = outcomeService.predict(embedding, workerType.name(), profile);
            predictions.put(profile, pred);
        }

        // Select best: max mu, tie-break = default profile
        String bestProfile = defaultProfile != null ? defaultProfile : candidates.get(0);
        double bestMu = predictions.getOrDefault(bestProfile, new GpPrediction(Double.NEGATIVE_INFINITY, 0))
                .mu();

        for (var entry : predictions.entrySet()) {
            if (entry.getValue().mu() > bestMu) {
                bestMu = entry.getValue().mu();
                bestProfile = entry.getKey();
            }
        }

        GpPrediction selectedPrediction = predictions.get(bestProfile);

        log.info("GP worker selection for {} '{}': selected '{}' (mu={}, sigma={}) from {} candidates",
                 workerType, title, bestProfile,
                 String.format("%.4f", selectedPrediction.mu()),
                 String.format("%.4f", selectedPrediction.sigma()),
                 candidates.size());

        // Record outcome at dispatch time
        outcomeService.recordOutcomeAtDispatch(
                null, null, "", workerType.name(), bestProfile, embedding, selectedPrediction);

        return new ProfileSelection(bestProfile, selectedPrediction, predictions);
    }

    /**
     * Result of profile selection, including all per-profile predictions.
     */
    public record ProfileSelection(
            String selectedProfile,
            GpPrediction selectedPrediction,
            Map<String, GpPrediction> allPredictions
    ) {}
}
