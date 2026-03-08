package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects worker profiles by minimising variational free energy (Active Inference / FEP).
 *
 * <p>The Free Energy Principle (Friston, 2010) states that adaptive agents minimise
 * the variational free energy F, which upper-bounds surprise.  Under a Gaussian
 * variational approximation, the free energy decomposes into:
 * <pre>
 *   F(profile) ≈ −E[log P(outcome|profile)] + KL(Q(state|profile) ‖ P(state))
 * </pre>
 * Using the GP posterior as the belief state:
 * <ul>
 *   <li>Expected log-likelihood proxy: E[log P] ≈ GP.mu (higher mu = higher likelihood)</li>
 *   <li>KL divergence proxy: KL ≈ GP.sigma² × kl_weight (uncertainty about the latent state)</li>
 * </ul>
 * This recovers a GP-UCB-like formula but grounded in Bayesian brain theory:
 * <pre>
 *   F(profile) ≈ −GP.mu + kl_weight × GP.sigma²
 * </pre>
 * The optimal action is argmin F — the profile that minimises both surprise and
 * epistemic uncertainty simultaneously.</p>
 *
 * @see <a href="https://doi.org/10.1007/s10827-010-0300-y">Friston (2010), The free-energy principle</a>
 */
@Service
@ConditionalOnProperty(prefix = "active-inference", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ActiveInferenceService {

    private static final Logger log = LoggerFactory.getLogger(ActiveInferenceService.class);

    static final int MIN_SAMPLES = 5;
    static final int MAX_SAMPLES = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final WorkerProfileRegistry profileRegistry;

    @Value("${active-inference.kl-weight:1.0}")
    private double klWeight;

    public ActiveInferenceService(TaskOutcomeRepository taskOutcomeRepository,
                                   WorkerProfileRegistry profileRegistry) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Computes variational free energy for all profiles of a worker type.
     *
     * @param workerType worker type name (e.g. "BE")
     * @return free energy report with optimal profile, or null if insufficient data
     */
    public FreeEnergyReport computeFreeEnergy(String workerType) {
        List<String> profiles = profileRegistry.profilesForWorkerType(WorkerType.valueOf(workerType));

        List<String>  candidateNames = new ArrayList<>();
        List<Double>  freeEnergies   = new ArrayList<>();
        List<Double>  likelihoods    = new ArrayList<>();
        List<Double>  klDivergences  = new ArrayList<>();

        for (String profile : profiles) {
            // findTrainingDataRaw columns: [8]=gp_mu, [9]=gp_sigma2, [10]=actual_reward
            List<Object[]> data = taskOutcomeRepository.findTrainingDataRaw(
                    workerType, profile, MAX_SAMPLES);

            // Compute mean GP.mu and mean GP.sigma2 from historical predictions
            double sumMu     = 0.0;
            double sumSigma2 = 0.0;
            int count = 0;
            for (Object[] row : data) {
                Double gpMu     = row[8]  != null ? ((Number) row[8]).doubleValue()  : null;
                Double gpSigma2 = row[9]  != null ? ((Number) row[9]).doubleValue()  : null;
                if (gpMu != null && gpSigma2 != null) {
                    sumMu     += gpMu;
                    sumSigma2 += gpSigma2;
                    count++;
                }
            }

            if (count < MIN_SAMPLES) continue;

            double meanMu     = sumMu     / count;
            double meanSigma2 = sumSigma2 / count;

            // Variational free energy (lower = better)
            double likelihood  = meanMu;                       // E[log P] proxy
            double klDiv       = meanSigma2 * klWeight;        // KL proxy
            double freeEnergy  = -likelihood + klDiv;          // F = -E[log P] + KL

            candidateNames.add(profile);
            freeEnergies.add(freeEnergy);
            likelihoods.add(likelihood);
            klDivergences.add(klDiv);
        }

        if (candidateNames.isEmpty()) {
            log.debug("ActiveInference for {}: no profiles with sufficient GP data", workerType);
            return null;
        }

        // Optimal action: profile with minimum free energy
        int bestIdx = 0;
        for (int i = 1; i < freeEnergies.size(); i++) {
            if (freeEnergies.get(i) < freeEnergies.get(bestIdx)) {
                bestIdx = i;
            }
        }

        String optimalChoice = candidateNames.get(bestIdx);

        log.debug("ActiveInference for {}: {} candidates, optimal='{}', F={}",
                  workerType, candidateNames.size(), optimalChoice,
                  String.format("%.4f", freeEnergies.get(bestIdx)));

        return new FreeEnergyReport(
                candidateNames.toArray(new String[0]),
                freeEnergies.stream().mapToDouble(Double::doubleValue).toArray(),
                likelihoods.stream().mapToDouble(Double::doubleValue).toArray(),
                klDivergences.stream().mapToDouble(Double::doubleValue).toArray(),
                optimalChoice
        );
    }

    /**
     * Free energy report for worker profile selection.
     *
     * @param candidates    all evaluated worker profile names
     * @param freeEnergies  variational free energy for each candidate (lower = better)
     * @param likelihoods   expected log-likelihood proxy (GP.mu) per candidate
     * @param klDivergences KL divergence proxy (GP.sigma² × klWeight) per candidate
     * @param optimalChoice profile with minimum free energy
     */
    public record FreeEnergyReport(
            String[] candidates,
            double[] freeEnergies,
            double[] likelihoods,
            double[] klDivergences,
            String optimalChoice
    ) {}
}
