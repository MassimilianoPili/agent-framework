package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ProspectTheory.ProspectEvaluation;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;

/**
 * Applies Prospect Theory behavioral bias to worker profile evaluation.
 *
 * <p>Loads historical reward data for a worker profile, bins rewards into
 * outcome/probability pairs relative to a reference point (0.5 = neutral),
 * and computes the prospect value using Kahneman-Tversky parameters.</p>
 *
 * <p>The {@link #adjustmentFactor(String)} method returns a value in [-0.5, 0.5]
 * that can be added to the GP predicted mu to penalize loss-averse profiles
 * (those with high variance and frequent below-reference outcomes).</p>
 *
 * @see ProspectTheory
 * @see <a href="https://doi.org/10.2307/1914185">
 *     Kahneman &amp; Tversky (1979), Prospect Theory, Econometrica</a>
 */
@Service
@ConditionalOnProperty(prefix = "prospect-theory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProspectTheoryService {

    private static final Logger log = LoggerFactory.getLogger(ProspectTheoryService.class);

    static final double REFERENCE_POINT = 0.5;
    static final int MIN_SAMPLES = 10;
    static final int NUM_BINS = 10;
    static final double ADJUSTMENT_CLAMP = 0.5;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${prospect-theory.loss-aversion-lambda:2.25}")
    private double lambda;

    public ProspectTheoryService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Evaluates a worker profile using prospect theory.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Load reward timeseries for the profile</li>
     *   <li>Shift rewards by reference point (0.5): outcome = reward - 0.5</li>
     *   <li>Bin into {@link #NUM_BINS} equal-width bins over [-0.5, 0.5]</li>
     *   <li>Compute prospect value using Kahneman-Tversky parameters</li>
     *   <li>Compare to raw expected value (no behavioral bias)</li>
     * </ol>
     *
     * @param workerType worker type name (for logging)
     * @param profile    worker profile identifier
     * @return prospect evaluation, or neutral evaluation if insufficient data
     */
    public ProspectEvaluation evaluate(String workerType, String profile) {
        List<Object[]> timeseries = taskOutcomeRepository.findRewardTimeseriesByProfile(profile);

        if (timeseries.size() < MIN_SAMPLES) {
            return new ProspectEvaluation(profile, 0.0, 0.0, 0.0);
        }

        // Extract rewards and compute raw expected value
        double[] rewards = new double[timeseries.size()];
        for (int i = 0; i < timeseries.size(); i++) {
            rewards[i] = ((Number) timeseries.get(i)[0]).doubleValue();
        }

        double rawEV = 0.0;
        for (double r : rewards) rawEV += r;
        rawEV /= rewards.length;

        // Bin rewards into outcome/probability pairs relative to reference point
        double binWidth = 1.0 / NUM_BINS;
        double[] outcomes = new double[NUM_BINS];
        double[] probabilities = new double[NUM_BINS];

        for (double r : rewards) {
            int bin = Math.min((int) (r / binWidth), NUM_BINS - 1);
            bin = Math.max(bin, 0);
            probabilities[bin]++;
        }

        for (int i = 0; i < NUM_BINS; i++) {
            outcomes[i] = (i + 0.5) * binWidth - REFERENCE_POINT; // center of bin, shifted
            probabilities[i] /= rewards.length;
        }

        double pv = ProspectTheory.prospectValue(outcomes, probabilities,
                ProspectTheory.DEFAULT_ALPHA, ProspectTheory.DEFAULT_BETA, lambda,
                ProspectTheory.DEFAULT_GAMMA_GAIN, ProspectTheory.DEFAULT_GAMMA_LOSS);

        // Raw EV also shifted by reference
        double rawEVShifted = rawEV - REFERENCE_POINT;
        double penalty = rawEVShifted - pv;

        log.debug("Prospect evaluation for '{}' ({}): PV={}, rawEV={}, penalty={}",
                profile, workerType,
                String.format("%.4f", pv), String.format("%.4f", rawEVShifted),
                String.format("%.4f", penalty));

        return new ProspectEvaluation(profile, pv, rawEVShifted, penalty);
    }

    /**
     * Adjustment factor for GP worker selection.
     *
     * <p>Returns a value in [-0.5, 0.5] representing behavioral bias.
     * Negative values penalize profiles with loss-averse characteristics
     * (high variance, frequent below-reference outcomes).</p>
     *
     * <p>Formula: {@code adjustment = prospectValue - rawExpectedValue},
     * clamped to [-0.5, 0.5]. Since loss aversion makes prospect value &lt;
     * raw EV for volatile profiles, the adjustment is typically negative.</p>
     *
     * @param profile worker profile identifier
     * @return adjustment factor in [-0.5, 0.5]
     */
    public double adjustmentFactor(String profile) {
        List<Object[]> timeseries = taskOutcomeRepository.findRewardTimeseriesByProfile(profile);

        if (timeseries.size() < MIN_SAMPLES) {
            return 0.0;
        }

        double[] rewards = new double[timeseries.size()];
        for (int i = 0; i < timeseries.size(); i++) {
            rewards[i] = ((Number) timeseries.get(i)[0]).doubleValue();
        }

        // Bin into outcome/probability pairs
        double binWidth = 1.0 / NUM_BINS;
        double[] outcomes = new double[NUM_BINS];
        double[] probabilities = new double[NUM_BINS];

        for (double r : rewards) {
            int bin = Math.min((int) (r / binWidth), NUM_BINS - 1);
            bin = Math.max(bin, 0);
            probabilities[bin]++;
        }

        double rawEV = 0.0;
        for (double r : rewards) rawEV += r;
        rawEV = rawEV / rewards.length - REFERENCE_POINT;

        for (int i = 0; i < NUM_BINS; i++) {
            outcomes[i] = (i + 0.5) * binWidth - REFERENCE_POINT;
            probabilities[i] /= rewards.length;
        }

        double pv = ProspectTheory.prospectValue(outcomes, probabilities,
                ProspectTheory.DEFAULT_ALPHA, ProspectTheory.DEFAULT_BETA, lambda,
                ProspectTheory.DEFAULT_GAMMA_GAIN, ProspectTheory.DEFAULT_GAMMA_LOSS);

        // adjustment = PV - rawEV: negative when loss aversion penalizes the profile
        double adjustment = pv - rawEV;
        return Math.max(-ADJUSTMENT_CLAMP, Math.min(ADJUSTMENT_CLAMP, adjustment));
    }
}
