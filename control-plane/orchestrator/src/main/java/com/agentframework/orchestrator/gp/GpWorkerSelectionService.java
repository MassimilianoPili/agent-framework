package com.agentframework.orchestrator.gp;

import com.agentframework.gp.engine.GaussianProcessEngine;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.analytics.WorkerDriftMonitor;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
@ConditionalOnProperty(prefix = "gp", name = "enabled", havingValue = "true")
public class GpWorkerSelectionService {

    private static final Logger log = LoggerFactory.getLogger(GpWorkerSelectionService.class);

    private final TaskOutcomeService outcomeService;
    private final WorkerProfileRegistry profileRegistry;
    private final Optional<WorkerGreeksService> greeksService;
    private final Optional<WorkerDriftMonitor> driftMonitor;

    public GpWorkerSelectionService(TaskOutcomeService outcomeService,
                                     WorkerProfileRegistry profileRegistry,
                                     Optional<WorkerGreeksService> greeksService,
                                     Optional<WorkerDriftMonitor> driftMonitor) {
        this.outcomeService = outcomeService;
        this.profileRegistry = profileRegistry;
        this.greeksService = greeksService;
        this.driftMonitor = driftMonitor;
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

        // Greeks risk penalty: if the selected profile is too risky, switch to a safer one
        if (greeksService.isPresent()) {
            try {
                WorkerGreeks greeks = greeksService.get()
                        .computeGreeks(bestProfile, workerType.name(), embedding);
                if (greeks.riskScore() > 0.7) {
                    // Find a safer alternative with mu >= bestMu * 0.9
                    final double threshold = bestMu * 0.9;
                    final String riskyProfile = bestProfile;
                    for (var entry : predictions.entrySet()) {
                        if (entry.getKey().equals(riskyProfile)) continue;
                        if (entry.getValue().mu() >= threshold) {
                            WorkerGreeks altGreeks = greeksService.get()
                                    .computeGreeks(entry.getKey(), workerType.name(), embedding);
                            if (altGreeks.riskScore() < greeks.riskScore()) {
                                log.info("Greeks risk penalty: switching from '{}' (risk={}) to '{}' (risk={})",
                                        riskyProfile, String.format("%.3f", greeks.riskScore()),
                                        entry.getKey(), String.format("%.3f", altGreeks.riskScore()));
                                bestProfile = entry.getKey();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Greeks computation failed during selection, proceeding without risk check: {}", e.getMessage());
            }
        }

        // Drift penalty: penalize profiles with distribution shift
        if (driftMonitor.isPresent()) {
            double penalty = driftMonitor.get().penaltyFor(bestProfile);
            if (penalty > 0) {
                double penalizedMu = bestMu - penalty;
                for (var entry : predictions.entrySet()) {
                    if (entry.getKey().equals(bestProfile)) continue;
                    double altPenalty = driftMonitor.get().penaltyFor(entry.getKey());
                    if (entry.getValue().mu() - altPenalty > penalizedMu) {
                        log.info("Drift penalty: switching from '{}' (W1={}) to '{}'",
                                bestProfile, String.format("%.3f", penalty), entry.getKey());
                        bestProfile = entry.getKey();
                        bestMu = entry.getValue().mu();
                        break;
                    }
                }
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
