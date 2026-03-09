package com.agentframework.orchestrator.federation;

import com.agentframework.common.privacy.DifferentialPrivacyMechanism;
import com.agentframework.common.privacy.LaplaceMechanism;
import com.agentframework.common.privacy.PrivacyBudget;
import com.agentframework.common.privacy.PrivatizedMetrics;
import com.agentframework.orchestrator.reward.WorkerEloStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Exports worker metrics with differential privacy noise for federated sharing (#43).
 *
 * <p>Before any metric leaves this server, it passes through the configured
 * {@link DifferentialPrivacyMechanism} (Laplace by default). A daily
 * {@link PrivacyBudget} limits total information leakage.</p>
 *
 * <p>Match counts are additionally binned to the nearest 10 for k-anonymity:
 * knowing that a profile has "~30 matches" reveals far less than "exactly 27".</p>
 *
 * <p>Active only when {@code federation.privacy.enabled=true}.</p>
 */
@Service
@ConditionalOnProperty(name = "federation.privacy.enabled", havingValue = "true")
@EnableConfigurationProperties(FederationPrivacyProperties.class)
public class FederationMetricsExporter {

    private static final Logger log = LoggerFactory.getLogger(FederationMetricsExporter.class);

    private final DifferentialPrivacyMechanism mechanism;
    private final PrivacyBudget budget;
    private final FederationPrivacyProperties properties;

    public FederationMetricsExporter(FederationPrivacyProperties properties) {
        this.properties = properties;
        this.mechanism = new LaplaceMechanism();
        this.budget = new PrivacyBudget(properties.maxQueriesPerDay());
        log.info("Federation DP exporter active (ε={}, budget={}/day, eloΔ={}, rewardΔ={})",
                 properties.epsilon(), properties.maxQueriesPerDay(),
                 properties.eloSensitivity(), properties.rewardSensitivity());
    }

    /** Constructor for testing with injectable mechanism. */
    FederationMetricsExporter(FederationPrivacyProperties properties,
                              DifferentialPrivacyMechanism mechanism,
                              PrivacyBudget budget) {
        this.properties = properties;
        this.mechanism = mechanism;
        this.budget = budget;
    }

    /**
     * Exports a worker's metrics with DP noise.
     *
     * @param stats the true worker ELO stats
     * @return privatised metrics ready for federated broadcast
     * @throws PrivacyBudgetExhaustedException if the daily budget is depleted
     */
    public PrivatizedMetrics exportMetrics(WorkerEloStats stats) {
        if (!budget.canQuery()) {
            log.warn("Privacy budget exhausted — refusing metric export for profile '{}'",
                     stats.getWorkerProfile());
            throw new PrivacyBudgetExhaustedException(
                    "Daily DP budget exhausted (%d/%d queries used)".formatted(
                            budget.queriesUsedToday(), budget.maxQueriesPerDay()));
        }

        double noisyElo = mechanism.privatize(
                stats.getEloRating(), properties.eloSensitivity(), properties.epsilon());
        double noisyReward = mechanism.privatize(
                stats.avgReward(), properties.rewardSensitivity(), properties.epsilon());
        int binnedMatchCount = binToNearest10(stats.getMatchCount());

        budget.recordQuery();

        log.debug("Exported privatised metrics for '{}' (ε={}, budget remaining={}/{})",
                  stats.getWorkerProfile(), properties.epsilon(),
                  budget.remaining(), budget.maxQueriesPerDay());

        return new PrivatizedMetrics(
                stats.getWorkerProfile(),
                noisyElo,
                noisyReward,
                binnedMatchCount,
                properties.epsilon(),
                Instant.now()
        );
    }

    /**
     * Privatises a single ELO value (convenience for ad-hoc queries).
     */
    public double privatizeElo(double trueElo) {
        return mechanism.privatize(trueElo, properties.eloSensitivity(), properties.epsilon());
    }

    /**
     * Privatises a single reward value (convenience for ad-hoc queries).
     */
    public double privatizeReward(double trueReward) {
        return mechanism.privatize(trueReward, properties.rewardSensitivity(), properties.epsilon());
    }

    /**
     * Returns the remaining query budget for today.
     */
    public int getRemainingBudget() {
        return budget.remaining();
    }

    /**
     * Returns the number of queries used today.
     */
    public int getQueriesUsedToday() {
        return budget.queriesUsedToday();
    }

    /**
     * Bins a count to the nearest 10 for k-anonymity.
     * E.g. 27 → 30, 14 → 10, 5 → 10.
     */
    static int binToNearest10(int count) {
        return Math.max(10, ((count + 5) / 10) * 10);
    }
}
