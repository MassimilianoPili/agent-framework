package com.agentframework.orchestrator.gp;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.reward.WorkerEloStats;
import com.agentframework.orchestrator.reward.WorkerEloStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes Black-Scholes-inspired Greeks for worker profiles using GP predictions.
 *
 * <p>Greeks are computed via finite differences on the GP prediction surface:
 * <ul>
 *   <li><b>Delta</b>: {@code (mu(emb*(1+h)) - mu(emb*(1-h))) / (2h)}, h=0.1</li>
 *   <li><b>Gamma</b>: {@code (mu(emb*(1+h)) - 2*mu(emb) + mu(emb*(1-h))) / h²}</li>
 *   <li><b>Vega</b>: perturbation along uncertainty direction (sigma-scaled)</li>
 *   <li><b>Theta</b>: avgReward from ELO stats (learning rate proxy)</li>
 * </ul>
 *
 * <p>Only created when GP is enabled ({@code gp.enabled=true}).</p>
 */
@Service
@ConditionalOnProperty(prefix = "gp", name = "enabled", havingValue = "true")
public class WorkerGreeksService {

    private static final Logger log = LoggerFactory.getLogger(WorkerGreeksService.class);

    /** Perturbation step size for finite differences. */
    static final double H = 0.1;

    private final TaskOutcomeService outcomeService;
    private final WorkerEloStatsRepository eloStatsRepository;

    public WorkerGreeksService(TaskOutcomeService outcomeService,
                                WorkerEloStatsRepository eloStatsRepository) {
        this.outcomeService = outcomeService;
        this.eloStatsRepository = eloStatsRepository;
    }

    /**
     * Computes Greeks for a single worker profile.
     *
     * @param profile       worker profile name (e.g. "be-java")
     * @param workerType    worker type name (e.g. "BE")
     * @param taskEmbedding reference task embedding for perturbation
     * @return computed Greeks
     */
    public WorkerGreeks computeGreeks(String profile, String workerType, float[] taskEmbedding) {
        // Base prediction
        GpPrediction base = outcomeService.predict(taskEmbedding, workerType, profile);

        // Perturbation for Delta and Gamma
        float[] embHigh = scaleEmbedding(taskEmbedding, 1.0 + H);
        float[] embLow  = scaleEmbedding(taskEmbedding, 1.0 - H);

        GpPrediction predHigh = outcomeService.predict(embHigh, workerType, profile);
        GpPrediction predLow  = outcomeService.predict(embLow, workerType, profile);

        // Delta: first derivative (central difference)
        double delta = (predHigh.mu() - predLow.mu()) / (2 * H);

        // Gamma: second derivative
        double gamma = (predHigh.mu() - 2 * base.mu() + predLow.mu()) / (H * H);

        // Vega: sensitivity to uncertainty
        double sigma = base.sigma();
        double vega;
        if (sigma > 0) {
            double vegaStep = 0.5 * sigma;
            float[] embVegaHigh = scaleEmbedding(taskEmbedding, 1.0 + vegaStep);
            float[] embVegaLow  = scaleEmbedding(taskEmbedding, 1.0 - vegaStep);

            GpPrediction predVegaHigh = outcomeService.predict(embVegaHigh, workerType, profile);
            GpPrediction predVegaLow  = outcomeService.predict(embVegaLow, workerType, profile);

            vega = (predVegaHigh.mu() - predVegaLow.mu()) / (2 * vegaStep);
        } else {
            vega = 0.0;
        }

        // Theta: learning rate proxy from ELO stats
        double theta = eloStatsRepository.findById(profile)
                .filter(s -> s.getMatchCount() > 0)
                .map(WorkerEloStats::avgReward)
                .orElse(0.0);

        double riskScore = WorkerGreeks.computeRiskScore(delta, gamma, vega);

        return new WorkerGreeks(profile, workerType, base.mu(), base.sigma2(),
                delta, gamma, vega, theta, riskScore);
    }

    /**
     * Computes Greeks for all given profiles of a worker type.
     *
     * @param workerType         worker type name
     * @param profiles           list of profile names
     * @param referenceEmbedding reference task embedding
     * @return list of computed Greeks (profiles that error out are skipped)
     */
    public List<WorkerGreeks> computeGreeksForType(String workerType, List<String> profiles,
                                                     float[] referenceEmbedding) {
        List<WorkerGreeks> results = new ArrayList<>();
        for (String profile : profiles) {
            try {
                results.add(computeGreeks(profile, workerType, referenceEmbedding));
            } catch (Exception e) {
                log.warn("Failed to compute Greeks for profile '{}': {}", profile, e.getMessage());
            }
        }
        return results;
    }

    /**
     * Scales each element of the embedding by the given factor.
     */
    static float[] scaleEmbedding(float[] embedding, double factor) {
        float[] scaled = new float[embedding.length];
        for (int i = 0; i < embedding.length; i++) {
            scaled[i] = (float) (embedding[i] * factor);
        }
        return scaled;
    }
}
