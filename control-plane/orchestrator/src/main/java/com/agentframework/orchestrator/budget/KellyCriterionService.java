package com.agentframework.orchestrator.budget;

import com.agentframework.orchestrator.budget.KellyCriterion.KellyRecommendation;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Applies Kelly Criterion to compute optimal budget fractions for worker profiles.
 *
 * <p>Loads historical task outcomes for a worker profile, computes the empirical
 * win probability and payoffs, then applies the Kelly formula to determine the
 * optimal fraction of the token budget to allocate.</p>
 *
 * <p>Uses half-Kelly by default (fractional-kelly = 0.5) to reduce variance,
 * since the true win probability is estimated from limited data.</p>
 *
 * @see KellyCriterion
 * @see <a href="https://doi.org/10.1002/j.1538-7305.1956.tb03809.x">
 *     Kelly (1956), A New Interpretation of Information Rate,
 *     Bell System Technical Journal</a>
 */
@Service
@ConditionalOnProperty(prefix = "kelly", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KellyCriterionService {

    private static final Logger log = LoggerFactory.getLogger(KellyCriterionService.class);

    static final double WIN_THRESHOLD = 0.5;
    static final int MIN_OUTCOMES = 10;
    static final int MAX_OUTCOMES = 500;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${kelly.fractional-kelly:0.5}")
    private double kellyFraction;

    @Value("${kelly.max-fraction:0.5}")
    private double maxFraction;

    public KellyCriterionService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes Kelly recommendation for a worker profile.
     *
     * <p>Win/loss is determined by the {@link #WIN_THRESHOLD}: reward &gt; 0.5 = win.
     * <ul>
     *   <li>winProb = count(win) / total</li>
     *   <li>winPayoff = mean(reward | win) - 0.5 (excess over threshold)</li>
     *   <li>lossPayoff = 0.5 - mean(reward | loss) (shortfall from threshold)</li>
     * </ul></p>
     *
     * @param workerType worker type name
     * @param profile    worker profile identifier
     * @return Kelly recommendation, or zero fraction if insufficient data
     */
    public KellyRecommendation computeForProfile(String workerType, String profile) {
        List<Object[]> data = taskOutcomeRepository.findTrainingDataRaw(workerType, profile, MAX_OUTCOMES);

        if (data.size() < MIN_OUTCOMES) {
            return new KellyRecommendation(0.0, 0.0, 0.0, false);
        }

        int wins = 0;
        double winSum = 0.0;
        double lossSum = 0.0;
        int losses = 0;

        for (Object[] row : data) {
            // findTrainingDataRaw returns: [id, planItemId, planId, taskKey, workerType,
            //   workerProfile, embeddingText, eloAtDispatch, gpMu, gpSigma2, actualReward, createdAt]
            Double actualReward = row[10] != null ? ((Number) row[10]).doubleValue() : null;
            if (actualReward == null) continue;

            if (actualReward > WIN_THRESHOLD) {
                wins++;
                winSum += actualReward;
            } else {
                losses++;
                lossSum += actualReward;
            }
        }

        int total = wins + losses;
        if (total < MIN_OUTCOMES) {
            return new KellyRecommendation(0.0, 0.0, 0.0, false);
        }

        double winProb = (double) wins / total;
        double winPayoff = wins > 0 ? (winSum / wins) - WIN_THRESHOLD : 0.0;
        double lossPayoff = losses > 0 ? WIN_THRESHOLD - (lossSum / losses) : 0.0;

        // Guard: need meaningful payoffs
        if (winPayoff <= 0.0 || lossPayoff <= 0.0) {
            return new KellyRecommendation(0.0, 0.0, 0.0, false);
        }

        KellyRecommendation rec = KellyCriterion.compute(
                winProb, winPayoff, lossPayoff, kellyFraction, maxFraction);

        log.debug("Kelly for '{}/{}': winProb={}, b={}, a={}, f*={}, adjusted={}, clamped={}",
                workerType, profile,
                String.format("%.3f", winProb),
                String.format("%.3f", winPayoff),
                String.format("%.3f", lossPayoff),
                String.format("%.3f", rec.fullKellyFraction()),
                String.format("%.3f", rec.adjustedFraction()),
                String.format("%.3f", rec.clampedFraction()));

        return rec;
    }

    /**
     * Adjusts a base budget using the Kelly-recommended fraction.
     *
     * <p>Returns {@code baseBudget × clampedFraction}. Minimum return is 1
     * (never return 0 for a positive budget).</p>
     *
     * @param baseBudget base token budget
     * @param workerType worker type name
     * @param profile    worker profile identifier
     * @return adjusted budget, or baseBudget if Kelly recommends 0
     */
    public long adjustBudget(long baseBudget, String workerType, String profile) {
        KellyRecommendation rec = computeForProfile(workerType, profile);
        if (!rec.shouldBet() || rec.clampedFraction() <= 0) {
            return baseBudget;
        }
        return Math.max(1L, Math.round(baseBudget * rec.clampedFraction()));
    }
}
