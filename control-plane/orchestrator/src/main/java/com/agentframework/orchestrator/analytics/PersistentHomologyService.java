package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes persistent homology (Vietoris-Rips) of task embedding spaces.
 *
 * <p>Persistent homology tracks topological features (connected components β₀,
 * independent cycles β₁) of a point cloud as a scale parameter ε increases.
 * A "barcode" interval (birth, death) records how long a feature persists;
 * long-lived features are structural, short-lived ones are noise.</p>
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Load task embeddings and parse the first 2 dimensions as a 2-D projection
 *       (full pgvector space is too large for exact Rips; 2-D suffices for topology analysis).</li>
 *   <li>Build a Vietoris-Rips complex: add edge (i,j) when ||p_i − p_j|| &lt; ε.</li>
 *   <li>Track connected component merges (β₀ barcodes) as ε increases via Union-Find.</li>
 *   <li>Identify significant features: persistence &gt; threshold (death − birth &gt; 20% of range).</li>
 * </ol>
 * This is a dimension-0 homology computation (connected components only);
 * β₁ (cycles) would require a full boundary matrix reduction.</p>
 *
 * @see <a href="https://doi.org/10.1007/s00454-002-2885-2">
 *     Edelsbrunner, Letscher &amp; Zomorodian (2002), Topological Persistence and Simplification</a>
 */
@Service
@ConditionalOnProperty(prefix = "persistent-homology", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PersistentHomologyService {

    private static final Logger log = LoggerFactory.getLogger(PersistentHomologyService.class);

    static final int MIN_SAMPLES = 5;
    static final int MAX_SAMPLES = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${persistent-homology.max-epsilon:0.5}")
    private double maxEpsilon;

    @Value("${persistent-homology.epsilon-steps:50}")
    private int epsilonSteps;

    public PersistentHomologyService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes dimension-0 persistent homology (connected components) for task embeddings.
     *
     * @param workerType worker type (used to filter relevant embeddings)
     * @return persistent homology report, or null if insufficient data
     */
    public PersistentHomologyReport compute(String workerType) {
        List<Object[]> rows = taskOutcomeRepository.findOutcomesWithEmbeddingByWorkerType(
                workerType, MAX_SAMPLES);

        List<double[]> points = new ArrayList<>();
        for (Object[] row : rows) {
            if (row[4] == null) continue;
            double[] emb = InformationBottleneckService.parseEmbedding((String) row[4]);
            if (emb != null && emb.length >= 2) {
                // Use first 2 dimensions as a 2-D projection
                points.add(new double[]{emb[0], emb[1]});
            }
        }

        if (points.size() < MIN_SAMPLES) {
            log.debug("PersistentHomology for {}: {} points, need {}", workerType, points.size(), MIN_SAMPLES);
            return null;
        }

        int n = points.size();

        // Pre-compute all pairwise distances (for small n this is fine)
        double[][] dist = new double[n][n];
        double maxDist = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = euclidean(points.get(i), points.get(j));
                dist[i][j] = d;
                dist[j][i] = d;
                if (d > maxDist) maxDist = d;
            }
        }

        // Cap maxEpsilon to the actual max distance
        double effectiveMax = Math.min(maxEpsilon, maxDist);
        double step = effectiveMax / epsilonSteps;

        // Vietoris-Rips persistence: track component merges via Union-Find
        List<Barcode> barcodes = new ArrayList<>();

        // Each point starts as its own component (born at ε=0)
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        double[] birthTime = new double[n];  // all born at 0

        // Sorted edges by distance (for incremental Rips)
        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                edges.add(new int[]{i, j});
            }
        }
        edges.sort(Comparator.comparingDouble(e -> dist[e[0]][e[1]]));

        // Process edges in order — merge components when edge is added
        for (int[] edge : edges) {
            double eps = dist[edge[0]][edge[1]];
            if (eps > effectiveMax) break;

            int ri = find(parent, edge[0]);
            int rj = find(parent, edge[1]);

            if (ri != rj) {
                // Merge — younger component dies (higher birth index dies)
                int dying   = (birthTime[ri] > birthTime[rj]) ? ri : rj;
                int survivor = (dying == ri) ? rj : ri;

                // Record barcode for dying component
                if (eps > birthTime[dying]) {
                    barcodes.add(new Barcode(birthTime[dying], eps));
                }
                parent[dying] = survivor;
            }
        }

        // Surviving components get death = maxEpsilon (infinite bar)
        Set<Integer> survivors = new HashSet<>();
        for (int i = 0; i < n; i++) survivors.add(find(parent, i));
        for (int root : survivors) {
            barcodes.add(new Barcode(birthTime[root], effectiveMax));
        }

        // Significant features: persistence > 20% of the distance range
        double persistenceThreshold = 0.2 * effectiveMax;
        List<TopologicalFeature> significant = new ArrayList<>();
        for (Barcode b : barcodes) {
            double persistence = b.death() - b.birth();
            if (persistence > persistenceThreshold) {
                significant.add(new TopologicalFeature("β₀", b.birth(), b.death(), persistence));
            }
        }

        log.debug("PersistentHomology for {}: {} points, {} barcodes, {} significant features",
                  workerType, n, barcodes.size(), significant.size());

        return new PersistentHomologyReport(n, barcodes, significant);
    }

    private int find(int[] parent, int i) {
        if (parent[i] != i) parent[i] = find(parent, parent[i]);
        return parent[i];
    }

    private double euclidean(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    /**
     * A barcode interval representing a topological feature that persists from birth to death.
     *
     * @param birth ε value at which the feature was born
     * @param death ε value at which the feature was destroyed
     */
    public record Barcode(double birth, double death) {}

    /**
     * A topological feature that is significant (long persistence).
     *
     * @param type        Betti number type ("β₀" = connected component, "β₁" = cycle)
     * @param birth       ε at birth
     * @param death       ε at death (or maxEpsilon for infinite features)
     * @param persistence death − birth
     */
    public record TopologicalFeature(String type, double birth, double death, double persistence) {}

    /**
     * Persistent homology report for task embedding space.
     *
     * @param numTasks           number of task embeddings analysed
     * @param barcodes           all topological features as (birth, death) intervals
     * @param significantFeatures features with high persistence (structural, not noise)
     */
    public record PersistentHomologyReport(
            int numTasks,
            List<Barcode> barcodes,
            List<TopologicalFeature> significantFeatures
    ) {}
}
