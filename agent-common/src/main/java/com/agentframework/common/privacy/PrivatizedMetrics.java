package com.agentframework.common.privacy;

import java.time.Instant;

/**
 * Privatised worker metrics for federated sharing (#43).
 *
 * <p>All numeric values have had Laplace noise added via the configured
 * {@link DifferentialPrivacyMechanism}. The {@code matchCount} is binned
 * (rounded to nearest 10) for additional k-anonymity protection.</p>
 *
 * @param workerProfile        the worker profile these metrics describe
 * @param noisyEloRating       ELO rating + Laplace(0, eloSensitivity/ε)
 * @param noisyAverageReward   average reward + Laplace(0, rewardSensitivity/ε)
 * @param approximateMatchCount match count binned to nearest 10 (k-anonymity)
 * @param epsilonUsed          the ε value used for this export (audit trail)
 * @param exportedAt           timestamp of the export
 */
public record PrivatizedMetrics(
        String workerProfile,
        double noisyEloRating,
        double noisyAverageReward,
        int approximateMatchCount,
        double epsilonUsed,
        Instant exportedAt
) {}
