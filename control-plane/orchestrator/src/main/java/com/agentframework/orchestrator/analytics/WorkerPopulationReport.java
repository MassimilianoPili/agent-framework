package com.agentframework.orchestrator.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of the replicator dynamics simulation on the worker profile population.
 *
 * <p>Compares the initial (uniform) distribution of profiles to the equilibrium
 * reached after running the replicator equation:
 * <pre>
 *   x_i(t+1) = x_i(t) × fitness_i / avgFitness(t)
 * </pre>
 *
 * <p>If the ESS (Evolutionarily Stable Strategy) deviates significantly from the
 * initial distribution ({@code dEss > 0.3}), a rebalance is recommended.</p>
 *
 * @see <a href="https://doi.org/10.1017/CBO9780511542640">
 *     Maynard Smith (1982), Evolution and the Theory of Games</a>
 */
public record WorkerPopulationReport(
        Instant generatedAt,
        int profileCount,
        Map<String, Double> initialDistribution,
        Map<String, Double> equilibrium,
        double dEss,
        boolean rebalanceRecommended,
        List<String> rebalanceHints,
        double avgFitness,
        int simulationSteps
) {}
