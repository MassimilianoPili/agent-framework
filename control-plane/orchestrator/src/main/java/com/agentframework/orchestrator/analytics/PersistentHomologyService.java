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
 * <p>Algorithm (two-phase):
 * <ol>
 *   <li><b>Phase 1 — β₀ (Union-Find)</b>: process edges in distance order; track
 *       component merges. O(n² α(n)).</li>
 *   <li><b>Phase 2 — β₁ (boundary matrix reduction)</b>: for each edge that does
 *       NOT merge two components (i.e. creates a cycle), check if the cycle is
 *       the boundary of a triangle (2-simplex). If not, a 1-cycle is born.
 *       Triangles that fill cycles record the death of β₁ features.</li>
 * </ol>
 *
 * <p>With n ≤ 100 points in 2-D projection, the O(n³) triangle enumeration
 * is tractable (~10⁶ operations worst case).</p>
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
     * Computes persistent homology (β₀ connected components + β₁ cycles) for task embeddings.
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
                points.add(new double[]{emb[0], emb[1]});
            }
        }

        if (points.size() < MIN_SAMPLES) {
            log.debug("PersistentHomology for {}: {} points, need {}", workerType, points.size(), MIN_SAMPLES);
            return null;
        }

        int n = points.size();

        // Pre-compute all pairwise distances
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

        double effectiveMax = Math.min(maxEpsilon, maxDist);

        // ── Phase 1: β₀ via Union-Find ─────────────────────────────────────────

        List<Barcode> beta0Barcodes = new ArrayList<>();

        int[] parent = new int[n];
        int[] rank = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        double[] birthTime = new double[n]; // all born at 0

        // Sorted edges by distance
        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                edges.add(new int[]{i, j});
            }
        }
        edges.sort(Comparator.comparingDouble(e -> dist[e[0]][e[1]]));

        // Track which edges are "active" (within effectiveMax) for β₁ phase
        Set<Long> activeEdges = new HashSet<>();

        for (int[] edge : edges) {
            double eps = dist[edge[0]][edge[1]];
            if (eps > effectiveMax) break;

            activeEdges.add(edgeKey(edge[0], edge[1]));

            int ri = find(parent, edge[0]);
            int rj = find(parent, edge[1]);

            if (ri != rj) {
                // Merge — younger component dies
                int dying   = (birthTime[ri] > birthTime[rj]) ? ri : rj;
                int survivor = (dying == ri) ? rj : ri;

                if (eps > birthTime[dying]) {
                    beta0Barcodes.add(new Barcode(birthTime[dying], eps));
                }
                union(parent, rank, dying, survivor);
            }
        }

        // Surviving components get death = effectiveMax
        Set<Integer> survivors = new HashSet<>();
        for (int i = 0; i < n; i++) survivors.add(find(parent, i));
        for (int root : survivors) {
            beta0Barcodes.add(new Barcode(birthTime[root], effectiveMax));
        }

        // ── Phase 2: β₁ via incremental cycle detection ────────────────────────
        //
        // Re-process edges in distance order with a fresh Union-Find.
        // An edge that connects two already-connected vertices creates a 1-cycle.
        // That cycle dies when a triangle (2-simplex) fills it:
        //   triangle (i,j,k) exists at ε = max(d(i,j), d(j,k), d(i,k)).
        //
        // We track cycle births via "non-tree edges" and deaths via triangles.

        List<Barcode> beta1Barcodes = computeBeta1(n, dist, edges, effectiveMax);

        // ── Combine results ─────────────────────────────────────────────────────

        List<Barcode> allBarcodes = new ArrayList<>(beta0Barcodes);
        allBarcodes.addAll(beta1Barcodes);

        double persistenceThreshold = 0.2 * effectiveMax;
        List<TopologicalFeature> significant = new ArrayList<>();

        for (Barcode b : beta0Barcodes) {
            double p = b.death() - b.birth();
            if (p > persistenceThreshold) {
                significant.add(new TopologicalFeature("β₀", b.birth(), b.death(), p));
            }
        }
        for (Barcode b : beta1Barcodes) {
            double p = b.death() - b.birth();
            if (p > persistenceThreshold) {
                significant.add(new TopologicalFeature("β₁", b.birth(), b.death(), p));
            }
        }

        int beta0Count = survivors.size();
        int beta1Count = (int) beta1Barcodes.stream()
                .filter(b -> b.death() >= effectiveMax).count(); // surviving cycles
        BettiSummary bettiSummary = new BettiSummary(beta0Count, beta1Count);

        List<String> interpretations = buildInterpretations(bettiSummary,
                beta0Barcodes.size(), beta1Barcodes.size());

        log.debug("PersistentHomology for {}: {} points, β₀={}, β₁={}, {} significant",
                  workerType, n, beta0Count, beta1Count, significant.size());

        return new PersistentHomologyReport(n, allBarcodes, significant, bettiSummary, interpretations);
    }

    // ── β₁ computation ──────────────────────────────────────────────────────────

    /**
     * Computes β₁ barcodes using incremental cycle detection.
     *
     * <p>Edges are processed in distance order. An edge connecting two already-connected
     * vertices (in a fresh Union-Find) creates a 1-cycle born at that edge's distance.
     * The cycle dies when a triangle fills it — the triangle's filtration value
     * (max of its 3 edge distances) is the death time.</p>
     */
    List<Barcode> computeBeta1(int n, double[][] dist, List<int[]> sortedEdges, double effectiveMax) {
        // Fresh Union-Find for cycle detection
        int[] ufParent = new int[n];
        int[] ufRank = new int[n];
        for (int i = 0; i < n; i++) ufParent[i] = i;

        // Cycle births: non-tree edges (edge that doesn't merge components)
        List<double[]> cycleBirths = new ArrayList<>(); // [birthEps, edgeI, edgeJ]

        for (int[] edge : sortedEdges) {
            double eps = dist[edge[0]][edge[1]];
            if (eps > effectiveMax) break;

            int ri = find(ufParent, edge[0]);
            int rj = find(ufParent, edge[1]);

            if (ri == rj) {
                // Non-tree edge → creates a 1-cycle
                cycleBirths.add(new double[]{eps, edge[0], edge[1]});
            } else {
                union(ufParent, ufRank, ri, rj);
            }
        }

        if (cycleBirths.isEmpty()) {
            return List.of();
        }

        // For each cycle birth, find the earliest triangle that kills it.
        // A triangle (a,b,c) has filtration value max(d(a,b), d(b,c), d(a,c)).
        // It kills a cycle involving edge (i,j) if (i,j) is part of the triangle
        // and the triangle's filtration > birth time.
        //
        // Simplified approach: for each non-tree edge (i,j), check all vertices k
        // to see if triangle (i,j,k) exists within effectiveMax. The death time
        // is the minimum such triangle filtration value.

        List<Barcode> beta1Barcodes = new ArrayList<>();

        for (double[] birth : cycleBirths) {
            double birthEps = birth[0];
            int ei = (int) birth[1];
            int ej = (int) birth[2];

            double deathEps = effectiveMax; // survives if no killing triangle

            for (int k = 0; k < n; k++) {
                if (k == ei || k == ej) continue;
                double dik = dist[ei][k];
                double djk = dist[ej][k];
                if (dik > effectiveMax || djk > effectiveMax) continue;

                // Triangle (ei, ej, k) has filtration = max of 3 edge distances
                double triFiltration = Math.max(dist[ei][ej], Math.max(dik, djk));

                if (triFiltration > birthEps && triFiltration < deathEps) {
                    deathEps = triFiltration;
                }
            }

            beta1Barcodes.add(new Barcode(birthEps, deathEps));
        }

        return beta1Barcodes;
    }

    // ── Interpretations ─────────────────────────────────────────────────────────

    static List<String> buildInterpretations(BettiSummary betti, int totalBeta0, int totalBeta1) {
        List<String> interps = new ArrayList<>();

        if (betti.beta0() == 1) {
            interps.add("Worker embeddings form a single connected cluster — good cohesion.");
        } else if (betti.beta0() <= 3) {
            interps.add("Workers operate in " + betti.beta0()
                    + " distinct clusters — moderate specialization.");
        } else {
            interps.add("Workers operate in " + betti.beta0()
                    + " disconnected clusters — low cross-profile transferability.");
        }

        if (betti.beta1() == 0 && totalBeta1 == 0) {
            interps.add("No cyclic structures detected — execution patterns are acyclic.");
        } else if (betti.beta1() == 0) {
            interps.add(totalBeta1 + " transient cycle(s) detected but all were filled by triangles"
                    + " — no persistent cyclic patterns.");
        } else {
            interps.add(betti.beta1() + " persistent cyclic pattern(s) detected"
                    + " — may indicate retry loops or oscillating behavior between worker profiles.");
        }

        return interps;
    }

    // ── Union-Find helpers ──────────────────────────────────────────────────────

    private int find(int[] parent, int i) {
        if (parent[i] != i) parent[i] = find(parent, parent[i]);
        return parent[i];
    }

    private void union(int[] parent, int[] rank, int a, int b) {
        if (rank[a] < rank[b]) { parent[a] = b; }
        else if (rank[a] > rank[b]) { parent[b] = a; }
        else { parent[b] = a; rank[a]++; }
    }

    private static long edgeKey(int i, int j) {
        return ((long) Math.min(i, j) << 32) | Math.max(i, j);
    }

    private double euclidean(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    // ── Records ─────────────────────────────────────────────────────────────────

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
     * Summary of Betti numbers at the final filtration level.
     *
     * @param beta0 number of connected components (surviving β₀ features)
     * @param beta1 number of independent 1-cycles (surviving β₁ features)
     */
    public record BettiSummary(int beta0, int beta1) {}

    /**
     * Persistent homology report for task embedding space.
     *
     * @param numTasks            number of task embeddings analysed
     * @param barcodes            all topological features as (birth, death) intervals
     * @param significantFeatures features with high persistence (structural, not noise)
     * @param bettiSummary        Betti number counts at the final filtration level
     * @param interpretations     human-readable diagnostic interpretations
     */
    public record PersistentHomologyReport(
            int numTasks,
            List<Barcode> barcodes,
            List<TopologicalFeature> significantFeatures,
            BettiSummary bettiSummary,
            List<String> interpretations
    ) {}
}
