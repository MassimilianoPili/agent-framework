package com.agentframework.orchestrator.reward;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * ELO-based performance rating for a worker profile.
 *
 * <p>Uses the standard Chess ELO formula (K=32) to compare profiles
 * that executed tasks of the same workerType within the same plan.
 * The rating starts at 1600 (unrated) and converges toward the true
 * quality level as more match data is accumulated.</p>
 *
 * <p>One row per worker profile (e.g. "be-java", "be-go", "task-manager").
 * Updated by {@link EloRatingService} once per plan, after all rewards are assigned.</p>
 */
@Entity
@Table(name = "worker_elo_stats")
public class WorkerEloStats {

    @Id
    @Column(name = "worker_profile", length = 50)
    private String workerProfile;

    @Column(name = "elo_rating", nullable = false)
    private double eloRating = 1600.0;

    @Column(name = "match_count", nullable = false)
    private int matchCount = 0;

    @Column(name = "win_count", nullable = false)
    private int winCount = 0;

    /** Sum of all aggregatedReward values seen — used to compute running average. */
    @Column(name = "cumulative_reward", nullable = false)
    private double cumulativeReward = 0.0;

    /** ELO currently locked as stakes on in-flight tasks. */
    @Column(name = "staked_reputation", nullable = false)
    private double stakedReputation = 0.0;

    /** Cumulative ELO staked across all tasks (historical). */
    @Column(name = "total_staked", nullable = false)
    private double totalStaked = 0.0;

    /** Cumulative ELO forfeited due to task failures. */
    @Column(name = "total_forfeited", nullable = false)
    private double totalForfeited = 0.0;

    @Column(name = "last_updated_at")
    private Instant lastUpdatedAt;

    protected WorkerEloStats() {}

    public WorkerEloStats(String workerProfile) {
        this.workerProfile = workerProfile;
    }

    /** Running average reward across all tasks handled by this profile. */
    public double avgReward() {
        return matchCount == 0 ? 0.0 : cumulativeReward / matchCount;
    }

    /**
     * Applies a single ELO update after comparing this profile against an opponent.
     *
     * @param opponentElo opponent's ELO rating before this match
     * @param won         true if this profile's reward was strictly higher
     */
    public void applyEloUpdate(double opponentElo, boolean won) {
        double expected = 1.0 / (1.0 + Math.pow(10.0, (opponentElo - eloRating) / 400.0));
        double actual = won ? 1.0 : 0.0;
        this.eloRating += 32.0 * (actual - expected);
        this.matchCount++;
        if (won) this.winCount++;
        this.lastUpdatedAt = Instant.now();
    }

    /** Records a task reward contribution (called once per DONE task for this profile). */
    public void recordReward(double reward) {
        this.cumulativeReward += reward;
    }

    // ── Staking methods (#47) ───────────────────────────────────────────────

    /** Debits a stake from this profile's reputation pool. */
    public void addStake(double amount) {
        this.stakedReputation += amount;
        this.totalStaked += amount;
        this.lastUpdatedAt = Instant.now();
    }

    /**
     * Settles a stake after task completion.
     *
     * @param stakeAmount the amount originally staked
     * @param success     true if the task completed successfully
     * @param bonusRate   fraction of stake awarded as bonus on success (e.g. 0.30)
     */
    public void settleStake(double stakeAmount, boolean success, double bonusRate) {
        this.stakedReputation -= stakeAmount;
        if (success) {
            this.eloRating += stakeAmount * bonusRate;
        } else {
            this.eloRating -= stakeAmount;
            this.totalForfeited += stakeAmount;
        }
        this.lastUpdatedAt = Instant.now();
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public String getWorkerProfile() { return workerProfile; }
    public double getEloRating() { return eloRating; }
    public int getMatchCount() { return matchCount; }
    public int getWinCount() { return winCount; }
    public double getCumulativeReward() { return cumulativeReward; }
    public double getStakedReputation() { return stakedReputation; }
    public double getTotalStaked() { return totalStaked; }
    public double getTotalForfeited() { return totalForfeited; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
}
