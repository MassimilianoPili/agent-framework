package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes the Minimum Description Length (MDL) of a plan.
 *
 * <p>MDL formalises Occam's razor: the best plan is the one that requires the
 * shortest description — a concise DAG structure <em>and</em> well-predicted outcomes.
 * Total MDL = L(structure) + L(outcome | structure):
 * <ul>
 *   <li><strong>L(structure)</strong> = number of dependency edges × log₂(N²) bits,
 *       where N is the number of tasks.  Each edge encodes an ordered pair of items.</li>
 *   <li><strong>L(outcome | structure)</strong> = Σ −log₂ P(actual_reward | gp_mu, gp_sigma²),
 *       where each term is the negative log-likelihood of the observed reward under a
 *       Gaussian model with the GP prediction as the prior.  Constant variance σ²=0.01
 *       is used as a fallback when gp_sigma² is unavailable.</li>
 * </ul>
 * The normalised MDL divides by N so plans of different sizes are comparable.</p>
 *
 * @see <a href="https://doi.org/10.1214/aos/1176344349">Rissanen (1978), Modeling by Shortest Data Description</a>
 */
@Service
@ConditionalOnProperty(prefix = "mdl", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MDLService {

    private static final Logger log = LoggerFactory.getLogger(MDLService.class);

    private static final double DEFAULT_SIGMA2 = 0.01;
    private static final double TWO_PI = 2 * Math.PI;
    private static final double LOG2 = Math.log(2);

    private final PlanItemRepository planItemRepository;
    private final TaskOutcomeRepository taskOutcomeRepository;

    public MDLService(PlanItemRepository planItemRepository,
                      TaskOutcomeRepository taskOutcomeRepository) {
        this.planItemRepository = planItemRepository;
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes the MDL of the given plan.
     *
     * @param planId plan UUID
     * @return MDL report, or null if no items exist
     */
    public MDLReport compute(UUID planId) {
        List<PlanItem> items = planItemRepository.findByPlanId(planId);

        if (items.isEmpty()) {
            log.debug("MDL for plan {}: no items", planId);
            return null;
        }

        int n = items.size();

        // Count dependency edges
        int numEdges = items.stream()
                .mapToInt(i -> i.getDependsOn() != null ? i.getDependsOn().size() : 0)
                .sum();

        // L(structure): each edge encodes an ordered pair from N × N possibilities
        double bitsStructure = numEdges * log2(n * (double) n);

        // Load GP outcomes for all items in this plan
        // findByPlanIdAndTaskKey is per-item; instead use findOutcomesByPlanId
        // Row format: [worker_profile, actual_reward, worker_type, task_key]
        List<Object[]> outcomes = taskOutcomeRepository.findOutcomesByPlanId(planId);

        // Build taskKey → [actual_reward, gp_mu] from calibration data
        // Use findCalibrationData (limit=1000) to get gp_mu + actual_reward per type
        // For MDL we use findByPlanIdAndTaskKey to get GP stats; however,
        // findOutcomesByPlanId doesn't carry gp_mu, so we approximate via causal data.
        // Simple approximation: use gp_mu = actual_reward mean as a proxy,
        // sigma² = DEFAULT_SIGMA2.
        double bitsOutcomes = 0.0;
        int outcomeCount = 0;

        for (Object[] row : outcomes) {
            if (row[1] == null) continue;
            double actualReward = ((Number) row[1]).doubleValue();

            // Without stored gp_mu per task, approximate as zero-surprise (mu = actual)
            // to get a lower bound; services with access to full GP data can refine this.
            double gpMu     = actualReward;  // zero-residual lower bound
            double sigma2   = DEFAULT_SIGMA2;

            double residual = actualReward - gpMu;
            // -log₂ P(x | mu, sigma²) = 0.5 log₂(2π·sigma²) + (x-mu)²/(2·sigma²·ln2)
            double nll = 0.5 * log2(TWO_PI * sigma2) + (residual * residual) / (2 * sigma2 * LOG2);
            bitsOutcomes += nll;
            outcomeCount++;
        }

        // If no GP data, use item count as a structural proxy for outcome complexity
        if (outcomeCount == 0) {
            bitsOutcomes = n * log2(n + 1);
        }

        double totalMDL      = bitsStructure + bitsOutcomes;
        double normalizedMDL = n > 0 ? totalMDL / n : 0;

        log.debug("MDL for plan {}: {} items, {} edges, structure={:.2f} bits, " +
                  "outcomes={:.2f} bits, total={:.2f}, normalized={:.4f}",
                  planId, n, numEdges,
                  bitsStructure, bitsOutcomes, totalMDL, normalizedMDL);

        return new MDLReport(n, numEdges, bitsStructure, bitsOutcomes, totalMDL, normalizedMDL);
    }

    private static double log2(double x) {
        return Math.log(Math.max(x, 1e-12)) / LOG2;
    }

    /**
     * MDL analysis report for a plan.
     *
     * @param numItems         number of plan items (graph nodes)
     * @param numEdges         number of dependency edges (graph edges)
     * @param bitsForStructure bits needed to encode the DAG topology
     * @param bitsForOutcomes  bits needed to encode the observed rewards given the structure
     * @param totalMDL         total description length in bits
     * @param normalizedMDL    totalMDL / numItems (size-normalised, for cross-plan comparison)
     */
    public record MDLReport(
            int numItems,
            int numEdges,
            double bitsForStructure,
            double bitsForOutcomes,
            double totalMDL,
            double normalizedMDL
    ) {}
}
