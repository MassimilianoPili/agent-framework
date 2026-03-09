package com.agentframework.orchestrator.reward;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Game-theoretic reputation staking for worker profiles (#47).
 *
 * <p>Before dispatch, a worker profile "stakes" a fraction of its ELO rating
 * proportional to task complexity (GP σ²). On success the profile earns a bonus;
 * on failure the stake is forfeited, lowering the ELO rating.</p>
 *
 * <p>This creates a self-selection mechanism: profiles with high uncertainty
 * (σ²) face larger stakes, discouraging speculative task acceptance.
 * The mechanism is complementary to the pairwise ELO updates in
 * {@link EloRatingService} (which operate at plan granularity).</p>
 *
 * <h3>Formula</h3>
 * <pre>
 *   stake = baseStakeRate × ELO × (1 + min(σ², maxComplexity))
 *
 *   success:  ELO += stake × successBonusRate
 *   failure:  ELO -= stake
 * </pre>
 *
 * <p>Cold start: new profiles start at ELO 1600 with a basale stake
 * of 0.05 × 1600 × 1.0 = 80 points (no complexity bonus without GP).</p>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Staking_(cryptography)">Staking in proof-of-stake systems</a>
 */
@Service
@ConditionalOnProperty(prefix = "reputation.staking", name = "enabled", havingValue = "true")
public class ReputationStakingService {

    private static final Logger log = LoggerFactory.getLogger(ReputationStakingService.class);

    private final WorkerEloStatsRepository eloStatsRepository;

    @Value("${reputation.staking.base-stake-rate:0.05}")
    private double baseStakeRate;

    @Value("${reputation.staking.max-complexity:2.0}")
    private double maxComplexity;

    @Value("${reputation.staking.success-bonus-rate:0.30}")
    private double successBonusRate;

    public ReputationStakingService(WorkerEloStatsRepository eloStatsRepository) {
        this.eloStatsRepository = eloStatsRepository;
    }

    /**
     * Stakes reputation for a worker profile before task dispatch.
     *
     * @param workerProfile the profile staking reputation (e.g. "be-java")
     * @param gpSigma2      GP prediction variance (0 if GP is disabled)
     * @return the staked amount (ELO points); 0 if profile has no ELO history
     */
    @Transactional
    public double stake(String workerProfile, double gpSigma2) {
        WorkerEloStats stats = getOrCreate(workerProfile);

        double complexity = Math.min(Math.max(gpSigma2, 0.0), maxComplexity);
        double amount = baseStakeRate * stats.getEloRating() * (1.0 + complexity);

        stats.addStake(amount);
        eloStatsRepository.save(stats);

        log.debug("Staked {} ELO for profile {} (σ²={}, complexity={})",
                  String.format("%.1f", amount), workerProfile, gpSigma2, complexity);

        return amount;
    }

    /**
     * Settles a stake after task completion.
     *
     * <p>On success, the profile earns {@code stake × bonusRate} ELO points.
     * On failure, the full stake is deducted from the profile's rating.</p>
     *
     * @param workerProfile the profile whose stake to settle
     * @param stakedAmount  the amount originally staked at dispatch
     * @param success       true if the task completed with status DONE
     */
    @Transactional
    public void settle(String workerProfile, double stakedAmount, boolean success) {
        if (stakedAmount <= 0) return;

        WorkerEloStats stats = eloStatsRepository.findById(workerProfile).orElse(null);
        if (stats == null) {
            log.warn("Cannot settle stake for unknown profile {}", workerProfile);
            return;
        }

        stats.settleStake(stakedAmount, success, successBonusRate);
        eloStatsRepository.save(stats);

        log.debug("Settled stake for {}: {} ELO {} (bonus rate={})",
                  workerProfile, String.format("%.1f", stakedAmount),
                  success ? "recovered + bonus" : "forfeited", successBonusRate);
    }

    private WorkerEloStats getOrCreate(String profile) {
        return eloStatsRepository.findById(profile)
                .orElseGet(() -> new WorkerEloStats(profile));
    }
}
