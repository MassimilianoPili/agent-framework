package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.reward.WorkerEloStats;
import com.agentframework.orchestrator.reward.WorkerEloStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Applies replicator dynamics from evolutionary game theory to analyse the
 * worker profile population.
 *
 * <p>The replicator equation iterates:
 * <pre>
 *   x_i(t+1) = x_i(t) × fitness_i / avgFitness(t)
 * </pre>
 * where {@code fitness_i = avgReward()} from {@link WorkerEloStats} and
 * the initial distribution is uniform ({@code x_i = 1/n}).
 *
 * <p>After convergence, {@code D_ESS = Σ|x_i(initial) - x_i(equilibrium)|}.
 * If D_ESS > 0.3, the service recommends rebalancing with GROW/SHRINK hints.</p>
 *
 * @see <a href="https://doi.org/10.1017/CBO9780511542640">
 *     Maynard Smith (1982), Evolution and the Theory of Games</a>
 */
@Service
public class ReplicatorDynamicsService {

    private static final Logger log = LoggerFactory.getLogger(ReplicatorDynamicsService.class);

    /** Default fitness for profiles with no match data. */
    static final double DEFAULT_FITNESS = 0.5;

    /** Maximum simulation steps. */
    static final int MAX_STEPS = 100;

    /** Early-stop threshold: converged when max|Δx_i| < this value. */
    static final double CONVERGENCE_THRESHOLD = 1e-6;

    /** D_ESS threshold above which rebalance is recommended. */
    static final double REBALANCE_THRESHOLD = 0.3;

    private final WorkerEloStatsRepository eloStatsRepository;

    public ReplicatorDynamicsService(WorkerEloStatsRepository eloStatsRepository) {
        this.eloStatsRepository = eloStatsRepository;
    }

    /**
     * Runs the replicator dynamics simulation on all known worker profiles.
     */
    public WorkerPopulationReport analyse() {
        List<WorkerEloStats> allStats = eloStatsRepository.findAllByOrderByEloRatingDesc();

        if (allStats.isEmpty()) {
            return new WorkerPopulationReport(
                    Instant.now(), 0,
                    Map.of(), Map.of(),
                    0.0, false, List.of(),
                    0.0, 0);
        }

        int n = allStats.size();

        // Extract profile names and fitness values
        String[] profiles = new String[n];
        double[] fitness = new double[n];
        for (int i = 0; i < n; i++) {
            profiles[i] = allStats.get(i).getWorkerProfile();
            fitness[i] = allStats.get(i).getMatchCount() > 0
                    ? allStats.get(i).avgReward()
                    : DEFAULT_FITNESS;
            // Fitness must be positive for replicator dynamics to work
            if (fitness[i] <= 0) fitness[i] = DEFAULT_FITNESS;
        }

        // Initial distribution: uniform
        double[] x = new double[n];
        double uniformShare = 1.0 / n;
        Arrays.fill(x, uniformShare);

        Map<String, Double> initialDist = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            initialDist.put(profiles[i], uniformShare);
        }

        // Replicator dynamics simulation
        int steps = 0;
        for (int t = 0; t < MAX_STEPS; t++) {
            steps++;
            double[] xNew = replicatorStep(x, fitness);

            // Check convergence
            double maxDelta = 0.0;
            for (int i = 0; i < n; i++) {
                maxDelta = Math.max(maxDelta, Math.abs(xNew[i] - x[i]));
            }

            x = xNew;
            if (maxDelta < CONVERGENCE_THRESHOLD) break;
        }

        // Build equilibrium map
        Map<String, Double> equilibrium = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            equilibrium.put(profiles[i], x[i]);
        }

        // D_ESS = sum of absolute deviations
        double dEss = 0.0;
        for (int i = 0; i < n; i++) {
            dEss += Math.abs(uniformShare - x[i]);
        }

        boolean rebalanceRecommended = dEss > REBALANCE_THRESHOLD;

        // Generate hints
        List<String> hints = new ArrayList<>();
        if (rebalanceRecommended) {
            for (int i = 0; i < n; i++) {
                double delta = x[i] - uniformShare;
                if (delta > 0.05) {
                    hints.add("GROW:" + profiles[i]);
                } else if (delta < -0.05) {
                    hints.add("SHRINK:" + profiles[i]);
                }
            }
        }

        // Average fitness (population-weighted)
        double avgFit = 0.0;
        for (int i = 0; i < n; i++) {
            avgFit += x[i] * fitness[i];
        }

        return new WorkerPopulationReport(
                Instant.now(), n,
                initialDist, equilibrium,
                dEss, rebalanceRecommended, hints,
                avgFit, steps);
    }

    /**
     * Single replicator dynamics step with explicit renormalisation.
     *
     * @param x       current frequency distribution
     * @param fitness fitness values per profile
     * @return new frequency distribution (renormalised)
     */
    double[] replicatorStep(double[] x, double[] fitness) {
        int n = x.length;

        // Population-weighted average fitness
        double avgFitness = 0.0;
        for (int i = 0; i < n; i++) {
            avgFitness += x[i] * fitness[i];
        }

        if (avgFitness <= 0) {
            // Degenerate case: all zero fitness — return uniform
            double[] uniform = new double[n];
            Arrays.fill(uniform, 1.0 / n);
            return uniform;
        }

        // Replicator equation: x_i' = x_i * f_i / f_avg
        double[] xNew = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            xNew[i] = x[i] * fitness[i] / avgFitness;
            sum += xNew[i];
        }

        // Explicit renormalisation to prevent floating-point drift
        if (sum > 0) {
            for (int i = 0; i < n; i++) {
                xNew[i] /= sum;
            }
        }

        return xNew;
    }
}
