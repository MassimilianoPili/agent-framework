package com.agentframework.orchestrator.gp;

import com.agentframework.gp.engine.GaussianProcessEngine;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.analytics.DescriptionLogicMatcher;
import com.agentframework.orchestrator.analytics.EdgeOfChaosService;
import com.agentframework.orchestrator.analytics.HInfinityRobustService;
import com.agentframework.orchestrator.analytics.HedgeAlgorithmService;
import com.agentframework.orchestrator.analytics.InformationBottleneckService;
import com.agentframework.orchestrator.analytics.PACBayesService;
import com.agentframework.orchestrator.analytics.ProspectTheoryService;
import com.agentframework.orchestrator.analytics.ReflectiveDispatchService;
import com.agentframework.orchestrator.analytics.WorkerDriftMonitor;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

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
    private final Optional<ProspectTheoryService> prospectTheoryService;
    private final Optional<HedgeAlgorithmService> hedgeAlgorithmService;
    // A2 Fase 2: GP pipeline analytics
    private final Optional<DescriptionLogicMatcher> descriptionLogicMatcher;
    private final Optional<InformationBottleneckService> informationBottleneckService;
    private final Optional<PACBayesService> pacBayesService;
    private final Optional<HInfinityRobustService> hInfinityRobustService;
    private final Optional<EdgeOfChaosService> edgeOfChaosService;
    private final Optional<ReflectiveDispatchService> reflectiveDispatchService;

    public GpWorkerSelectionService(TaskOutcomeService outcomeService,
                                     WorkerProfileRegistry profileRegistry,
                                     Optional<WorkerGreeksService> greeksService,
                                     Optional<WorkerDriftMonitor> driftMonitor,
                                     Optional<ProspectTheoryService> prospectTheoryService,
                                     Optional<HedgeAlgorithmService> hedgeAlgorithmService,
                                     Optional<DescriptionLogicMatcher> descriptionLogicMatcher,
                                     Optional<InformationBottleneckService> informationBottleneckService,
                                     Optional<PACBayesService> pacBayesService,
                                     Optional<HInfinityRobustService> hInfinityRobustService,
                                     Optional<EdgeOfChaosService> edgeOfChaosService,
                                     Optional<ReflectiveDispatchService> reflectiveDispatchService) {
        this.outcomeService = outcomeService;
        this.profileRegistry = profileRegistry;
        this.greeksService = greeksService;
        this.driftMonitor = driftMonitor;
        this.prospectTheoryService = prospectTheoryService;
        this.hedgeAlgorithmService = hedgeAlgorithmService;
        this.descriptionLogicMatcher = descriptionLogicMatcher;
        this.informationBottleneckService = informationBottleneckService;
        this.pacBayesService = pacBayesService;
        this.hInfinityRobustService = hInfinityRobustService;
        this.edgeOfChaosService = edgeOfChaosService;
        this.reflectiveDispatchService = reflectiveDispatchService;
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

        // A2: Description Logic pre-filter — remove candidates that don't satisfy capability requirements
        if (descriptionLogicMatcher.isPresent()) {
            try {
                // Build capability map: profile → set of capability keywords from profile name
                Map<String, Set<String>> workerCapabilities = new LinkedHashMap<>();
                for (String profile : candidates) {
                    // Profile names like "be-java", "fe-react" encode capability; split on '-'
                    workerCapabilities.put(profile, new LinkedHashSet<>(List.of(profile.split("-"))));
                }
                // Required capability derived from workerType (e.g. "BE" → "backend")
                String required = workerType.name().toLowerCase();
                var dlReport = descriptionLogicMatcher.get().match(required, workerCapabilities);
                if (dlReport.satisfiable() && !dlReport.matchedWorkers().isEmpty()) {
                    List<String> filtered = dlReport.matchedWorkers().stream()
                            .filter(candidates::contains)
                            .toList();
                    if (!filtered.isEmpty() && filtered.size() < candidates.size()) {
                        log.debug("DL pre-filter: {} → {} candidates for {} ({})",
                                  candidates.size(), filtered.size(), workerType, dlReport.explanation());
                        candidates = new ArrayList<>(filtered);
                    }
                }
            } catch (Exception e) {
                log.debug("Description Logic matching failed (non-blocking): {}", e.getMessage());
            }
        }

        // Embed task text
        float[] embedding = outcomeService.embedTask(title, description);

        // A2: Information Bottleneck diagnostic — measure embedding compression quality
        if (informationBottleneckService.isPresent()) {
            try {
                var ibReport = informationBottleneckService.get().compress(workerType.name());
                if (ibReport != null) {
                    log.debug("IB compression for {}: {}→{} dim, I(Z;Y)={}, I(Z;X)={}",
                              workerType, ibReport.originalDim(), ibReport.compressedDim(),
                              String.format("%.4f", ibReport.mutualInfoZY()),
                              String.format("%.4f", ibReport.mutualInfoZX()));
                }
            } catch (Exception e) {
                log.debug("Information Bottleneck failed (non-blocking): {}", e.getMessage());
            }
        }

        // Predict for each candidate
        Map<String, GpPrediction> predictions = new LinkedHashMap<>();
        for (String profile : candidates) {
            GpPrediction pred = outcomeService.predict(embedding, workerType.name(), profile);
            predictions.put(profile, pred);
        }

        // A2: PAC-Bayes convergence gate — check if GP has enough data to be trusted
        boolean gpConverged = true;
        if (pacBayesService.isPresent()) {
            try {
                var pacReport = pacBayesService.get().compute(workerType.name());
                gpConverged = pacReport.convergenceReached();
                if (!gpConverged) {
                    log.info("PAC-Bayes: GP not converged for {} (samples={}, required={}, KL={})",
                             workerType, pacReport.currentSamples(), pacReport.requiredSamples(),
                             String.format("%.4f", pacReport.klDivergence()));
                }
            } catch (Exception e) {
                log.debug("PAC-Bayes check failed (non-blocking): {}", e.getMessage());
            }
        }

        // A2: H∞ robust fallback — when GP is uncertain, use worst-case optimal profile
        if (!gpConverged && hInfinityRobustService.isPresent()) {
            try {
                var robustReport = hInfinityRobustService.get().computeRobustChoice(workerType.name());
                if (robustReport.robustChoice() != null && predictions.containsKey(robustReport.robustChoice())) {
                    log.info("H∞ robust fallback for {} (GP not converged): selecting '{}' (worst-case reward={})",
                             workerType, robustReport.robustChoice(),
                             String.format("%.4f", robustReport.worstCaseReward()));
                    GpPrediction robustPred = predictions.get(robustReport.robustChoice());
                    return new ProfileSelection(robustReport.robustChoice(), robustPred, predictions);
                }
            } catch (Exception e) {
                log.debug("H∞ robust fallback failed (non-blocking): {}", e.getMessage());
            }
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

        // Prospect theory: behavioral bias adjustment (loss aversion)
        if (prospectTheoryService.isPresent()) {
            try {
                double ptAdjustment = prospectTheoryService.get().adjustmentFactor(bestProfile);
                if (ptAdjustment < -0.1) {
                    double penalizedMu = bestMu + ptAdjustment;
                    for (var entry : predictions.entrySet()) {
                        if (entry.getKey().equals(bestProfile)) continue;
                        double altAdj = prospectTheoryService.get().adjustmentFactor(entry.getKey());
                        if (entry.getValue().mu() + altAdj > penalizedMu) {
                            log.info("Prospect theory: switching from '{}' (adj={}) to '{}'",
                                    bestProfile, String.format("%.3f", ptAdjustment), entry.getKey());
                            bestProfile = entry.getKey();
                            bestMu = entry.getValue().mu();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Prospect theory failed (non-blocking): {}", e.getMessage());
            }
        }

        // Hedge: exploration bonus from multiplicative weights
        if (hedgeAlgorithmService.isPresent()) {
            try {
                double bonus = hedgeAlgorithmService.get().explorationBonus(workerType, bestProfile);
                if (bonus < 0.5) {
                    for (var entry : predictions.entrySet()) {
                        if (entry.getKey().equals(bestProfile)) continue;
                        double altBonus = hedgeAlgorithmService.get().explorationBonus(workerType, entry.getKey());
                        if (altBonus > bonus && entry.getValue().mu() >= bestMu * 0.95) {
                            log.info("Hedge bonus: switching from '{}' (bonus={}) to '{}' (bonus={})",
                                    bestProfile, String.format("%.2f", bonus),
                                    entry.getKey(), String.format("%.2f", altBonus));
                            bestProfile = entry.getKey();
                            bestMu = entry.getValue().mu();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Hedge bonus failed (non-blocking): {}", e.getMessage());
            }
        }

        // A2: FDT Reflective Dispatch — secondary ranking when GP uncertainty is high
        if (reflectiveDispatchService.isPresent()) {
            GpPrediction currentBestPred = predictions.get(bestProfile);
            if (currentBestPred != null && currentBestPred.sigma() > 0.5) {
                try {
                    var fdtReport = reflectiveDispatchService.get()
                            .computeReflectivePolicy(title, workerType.name());
                    if (fdtReport != null && fdtReport.recommendedProfile() != null
                            && predictions.containsKey(fdtReport.recommendedProfile())
                            && !fdtReport.recommendedProfile().equals(bestProfile)) {
                        log.info("FDT reflective dispatch for {}: switching '{}' → '{}' (policyReward={}, {} similar tasks)",
                                 workerType, bestProfile, fdtReport.recommendedProfile(),
                                 String.format("%.4f", fdtReport.policyReward()), fdtReport.similarCount());
                        bestProfile = fdtReport.recommendedProfile();
                        bestMu = predictions.get(bestProfile).mu();
                    }
                } catch (Exception e) {
                    log.debug("FDT reflective dispatch failed (non-blocking): {}", e.getMessage());
                }
            }
        }

        // A2: Edge of Chaos — adaptive exploration/exploitation tuning
        if (edgeOfChaosService.isPresent()) {
            try {
                // Current exploration estimate from GP sigma of selected profile
                GpPrediction currentPred = predictions.get(bestProfile);
                double currentExploration = currentPred != null ? currentPred.sigma() : 0.5;
                var eocReport = edgeOfChaosService.get().tune(workerType.name(), currentExploration);
                if (Math.abs(eocReport.adaptationSignal()) > 0.01) {
                    log.debug("EdgeOfChaos for {}: Lyapunov={}, exploration {} → {} (signal={})",
                              workerType,
                              String.format("%.4f", eocReport.lyapunovExponent()),
                              String.format("%.3f", eocReport.currentExploration()),
                              String.format("%.3f", eocReport.newExploration()),
                              String.format("%.4f", eocReport.adaptationSignal()));
                }
            } catch (Exception e) {
                log.debug("Edge of Chaos tuning failed (non-blocking): {}", e.getMessage());
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
