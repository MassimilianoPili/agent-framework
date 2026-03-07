package com.agentframework.orchestrator.analytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Contract Theory for SLA worker incentive design.
 *
 * <p>Models the principal-agent problem between the framework (principal)
 * and workers (agents). The contract specifies quality targets and
 * bonus/penalty structures to align incentives under information asymmetry.</p>
 *
 * <p>Optimal linear contract:</p>
 * <pre>
 *   w(x) = α + β·max(0, x - target) − γ·max(0, target - x)
 *
 *   where: α = base token budget
 *          β = bonus multiplier (reward per unit above target)
 *          γ = penalty rate (cost per unit below target)
 *          x = actual quality (observed output)
 * </pre>
 *
 * <p>Constraints:</p>
 * <ul>
 *   <li><strong>Participation</strong>: effective budget ≥ minimum viable budget</li>
 *   <li><strong>Incentive Compatibility</strong>: β &gt; γ (effort is rewarded more than shirking saves)</li>
 * </ul>
 *
 * @see <a href="https://www.nobelprize.org/prizes/economic-sciences/2016/summary/">
 *     Hart &amp; Holmström (2016), Nobel Prize in Economics — Contract Theory</a>
 */
public final class ContractTheory {

    private ContractTheory() {}

    /**
     * Linear contract: base + bonus for quality above target − penalty below.
     *
     * @param workerType      type of worker (e.g. "BE", "FE")
     * @param profile         worker profile name
     * @param baseTokenBudget α: minimum guaranteed budget
     * @param qualityTarget   quality threshold
     * @param bonusMultiplier β: bonus per unit above target
     * @param penaltyRate     γ: penalty per unit below target
     */
    public record WorkerContract(
            String workerType,
            String profile,
            double baseTokenBudget,
            double qualityTarget,
            double bonusMultiplier,
            double penaltyRate
    ) {}

    /**
     * Performance evaluation against a contract.
     *
     * @param profile         worker profile evaluated
     * @param targetMet       whether actualQuality ≥ qualityTarget
     * @param actualQuality   observed output quality
     * @param qualityTarget   contract's quality threshold
     * @param surplus         actualQuality − qualityTarget
     * @param effectiveBudget adjusted budget (base ± bonus/penalty)
     * @param informationRent rent due to information asymmetry
     */
    public record ContractEvaluation(
            String profile,
            boolean targetMet,
            double actualQuality,
            double qualityTarget,
            double surplus,
            double effectiveBudget,
            double informationRent
    ) {}

    /**
     * Multi-profile contract report.
     *
     * @param contracts           contract per profile
     * @param evaluations         evaluation per profile
     * @param systemSurplus       aggregate surplus
     * @param profilesAboveTarget count meeting quality target
     * @param profilesBelowTarget count below quality target
     * @param recommendations     remediation actions
     */
    public record ContractReport(
            WorkerContract[] contracts,
            ContractEvaluation[] evaluations,
            double systemSurplus,
            int profilesAboveTarget,
            int profilesBelowTarget,
            String[] recommendations
    ) {}

    /**
     * Computes surplus: actualQuality − qualityTarget.
     *
     * <p>Positive = above target, negative = below target.</p>
     */
    static double computeSurplus(double actualQuality, double qualityTarget) {
        return actualQuality - qualityTarget;
    }

    /**
     * Effective budget given contract and observed quality.
     *
     * <pre>
     *   effective = base + bonus × max(0, surplus) − penalty × max(0, −surplus)
     * </pre>
     *
     * <p>Result is clamped to [0, +∞).</p>
     *
     * @param baseBudget      α: base token budget
     * @param bonusMultiplier β: bonus per unit above target
     * @param penaltyRate     γ: penalty per unit below target
     * @param surplus         actualQuality − qualityTarget
     * @return effective budget ≥ 0
     */
    static double effectiveBudget(double baseBudget, double bonusMultiplier,
                                    double penaltyRate, double surplus) {
        double bonus = bonusMultiplier * Math.max(0, surplus);
        double penalty = penaltyRate * Math.max(0, -surplus);
        return Math.max(0, baseBudget + bonus - penalty);
    }

    /**
     * Information rent: rent earned by a high-type worker over a low-type worker.
     *
     * <pre>
     *   IR = effectiveBudget(highQuality) − effectiveBudget(lowQuality)
     * </pre>
     *
     * <p>Measures the residual information asymmetry in the contract.
     * A well-designed contract minimizes IR while maintaining IC.</p>
     *
     * @param highTypeQuality quality of the high-type worker
     * @param lowTypeQuality  quality of the low-type worker
     * @param contract        the contract applied to both types
     * @return information rent (≥ 0 if highType &gt; lowType)
     */
    static double informationRent(double highTypeQuality, double lowTypeQuality,
                                    WorkerContract contract) {
        double surplusHigh = computeSurplus(highTypeQuality, contract.qualityTarget());
        double surplusLow = computeSurplus(lowTypeQuality, contract.qualityTarget());

        double budgetHigh = effectiveBudget(contract.baseTokenBudget(),
                contract.bonusMultiplier(), contract.penaltyRate(), surplusHigh);
        double budgetLow = effectiveBudget(contract.baseTokenBudget(),
                contract.bonusMultiplier(), contract.penaltyRate(), surplusLow);

        return budgetHigh - budgetLow;
    }

    /**
     * Participation constraint: effective budget must cover minimum viable cost.
     *
     * <p>If the effective budget falls below the minimum, the worker would
     * rationally refuse the task (outside option is better).</p>
     *
     * @param effectiveBudget computed effective budget
     * @param minBudget       minimum viable budget for the worker
     * @return true if participation constraint is satisfied
     */
    static boolean participationSatisfied(double effectiveBudget, double minBudget) {
        return effectiveBudget >= minBudget;
    }

    /**
     * Incentive compatibility: bonus must exceed penalty.
     *
     * <p>If β ≤ γ, the worker prefers to minimize effort because the penalty
     * for failure is greater than the reward for success. The contract fails
     * to incentivize quality.</p>
     *
     * @param bonusMultiplier β: bonus per unit above target
     * @param penaltyRate     γ: penalty per unit below target
     * @return true if incentive compatible (β &gt; γ)
     */
    static boolean incentiveCompatible(double bonusMultiplier, double penaltyRate) {
        return bonusMultiplier > penaltyRate;
    }

    /**
     * Evaluates performance against a contract.
     *
     * <p>Computes surplus, effective budget, and information rent
     * (estimated as the difference between actual quality and a hypothetical
     * low-quality performance at 50% of target).</p>
     *
     * @param contract      the worker's contract
     * @param actualQuality observed output quality
     * @return evaluation with all derived metrics
     */
    static ContractEvaluation evaluate(WorkerContract contract, double actualQuality) {
        double surplus = computeSurplus(actualQuality, contract.qualityTarget());
        boolean met = actualQuality >= contract.qualityTarget();
        double eb = effectiveBudget(contract.baseTokenBudget(),
                contract.bonusMultiplier(), contract.penaltyRate(), surplus);

        // Estimate information rent: compare actual vs hypothetical low type
        double lowTypeQuality = contract.qualityTarget() * 0.5;
        double ir = informationRent(actualQuality, lowTypeQuality, contract);

        return new ContractEvaluation(
                contract.profile(), met, actualQuality, contract.qualityTarget(),
                surplus, eb, ir);
    }

    /**
     * Bayesian contract adjustment after observing performance.
     *
     * <p>Updates quality target towards the mean observed quality:
     * newTarget = oldTarget + learningRate × mean(surplus).</p>
     *
     * <p>Target is clamped to [0, 1].</p>
     *
     * @param contract          current contract
     * @param observedQualities array of observed quality values
     * @param learningRate      η: update rate (0 = no update, 1 = full update)
     * @return adjusted contract with updated quality target
     */
    static WorkerContract adjustContract(WorkerContract contract,
                                          double[] observedQualities,
                                          double learningRate) {
        if (observedQualities.length == 0) {
            return contract;
        }

        double meanQuality = 0;
        for (double q : observedQualities) meanQuality += q;
        meanQuality /= observedQualities.length;

        double meanSurplus = meanQuality - contract.qualityTarget();
        double newTarget = contract.qualityTarget() + learningRate * meanSurplus;

        // Clamp target to [0, 1]
        newTarget = Math.max(0, Math.min(1, newTarget));

        return new WorkerContract(
                contract.workerType(), contract.profile(),
                contract.baseTokenBudget(), newTarget,
                contract.bonusMultiplier(), contract.penaltyRate());
    }

    /**
     * Computes an optimal contract from historical observations.
     *
     * <p>Calibration:</p>
     * <ul>
     *   <li>Base budget = mean(observed token costs)</li>
     *   <li>Quality target = 40th percentile of observed qualities
     *       ("achievable but challenging")</li>
     *   <li>Bonus/penalty from defaults (IC guaranteed by configuration)</li>
     * </ul>
     *
     * <p>With fewer than 5 observations, uses the median instead of
     * the 40th percentile.</p>
     *
     * @param workerType             worker type
     * @param profile                profile name
     * @param observedQualities      historical quality observations
     * @param observedTokenCosts     historical token cost observations
     * @param defaultBonusMultiplier β default
     * @param defaultPenaltyRate     γ default
     * @return calibrated contract
     */
    static WorkerContract optimalContract(String workerType, String profile,
                                           double[] observedQualities,
                                           double[] observedTokenCosts,
                                           double defaultBonusMultiplier,
                                           double defaultPenaltyRate) {
        // Base budget: mean of observed token costs
        double baseBudget = 0;
        for (double c : observedTokenCosts) baseBudget += c;
        baseBudget = observedTokenCosts.length > 0 ? baseBudget / observedTokenCosts.length : 1.0;

        // Quality target: 40th percentile (or median for small samples)
        double target;
        if (observedQualities.length == 0) {
            target = 0.5; // fallback
        } else {
            double[] sorted = Arrays.copyOf(observedQualities, observedQualities.length);
            Arrays.sort(sorted);
            double percentile = observedQualities.length < 5 ? 0.5 : 0.4;
            int idx = Math.min((int) (sorted.length * percentile), sorted.length - 1);
            target = sorted[idx];
        }

        return new WorkerContract(workerType, profile, baseBudget, target,
                defaultBonusMultiplier, defaultPenaltyRate);
    }
}
