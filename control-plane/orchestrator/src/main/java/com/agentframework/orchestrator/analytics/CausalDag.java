package com.agentframework.orchestrator.analytics;

import java.util.*;

/**
 * Directed acyclic graph for causal inference using Pearl's do-calculus.
 *
 * <p>Implements the backdoor adjustment formula for computing interventional
 * probabilities P(Y|do(X=x)) from observational data:</p>
 *
 * <pre>
 * P(Y|do(X=x)) = Σ_z P(Y|X=x, Z=z) × P(Z=z)
 * </pre>
 *
 * <p>where Z is the minimal adjustment set satisfying the backdoor criterion.</p>
 *
 * <p>Continuous variables are binned with {@link #BIN_WIDTH} for stratification.
 * Conditional independence is tested via Fisher's z-test on partial correlations.</p>
 *
 * @see <a href="https://doi.org/10.1017/CBO9780511803161">
 *     Pearl (2009), Causality: Models, Reasoning, and Inference, 2nd ed.</a>
 */
public class CausalDag {

    /** Bin width for discretizing continuous variables in stratification. */
    static final double BIN_WIDTH = 0.15;

    /** z-critical value for α=0.05 two-tailed (independence threshold). */
    static final double INDEPENDENCE_THRESHOLD = 1.96;

    /** Minimum observations per stratum for reliable estimation. */
    static final int MIN_STRATUM_SIZE = 5;

    private final List<String> nodes;
    private final Map<String, List<String>> edges; // parent → children

    public CausalDag(List<String> nodes, Map<String, List<String>> edges) {
        this.nodes = List.copyOf(nodes);
        this.edges = new LinkedHashMap<>();
        for (var entry : edges.entrySet()) {
            this.edges.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
    }

    /**
     * Domain-knowledge causal DAG for the agent framework.
     *
     * <p>Nodes represent measurable features of task outcomes. Edges encode
     * known causal relationships:</p>
     * <ul>
     *   <li>context_quality → task_success (direct)</li>
     *   <li>worker_elo → quality → task_success (mediated)</li>
     *   <li>token_budget → duration → task_success (mediated)</li>
     *   <li>task_complexity → difficulty → task_success (mediated)</li>
     * </ul>
     */
    public static CausalDag defaultDag() {
        List<String> nodes = List.of(
                "context_quality", "worker_elo", "quality",
                "token_budget", "duration",
                "task_complexity", "difficulty",
                "task_success"
        );
        Map<String, List<String>> edges = new LinkedHashMap<>();
        edges.put("context_quality", List.of("task_success"));
        edges.put("worker_elo", List.of("quality"));
        edges.put("quality", List.of("task_success"));
        edges.put("token_budget", List.of("duration"));
        edges.put("duration", List.of("task_success"));
        edges.put("task_complexity", List.of("difficulty"));
        edges.put("difficulty", List.of("task_success"));
        return new CausalDag(nodes, edges);
    }

    /** Returns the list of nodes in this DAG. */
    public List<String> nodes() {
        return nodes;
    }

    /** Returns the edge map (parent → children). */
    public Map<String, List<String>> edges() {
        return edges;
    }

    /** Direct parents of a node. */
    public List<String> parents(String node) {
        List<String> result = new ArrayList<>();
        for (var entry : edges.entrySet()) {
            if (entry.getValue().contains(node)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** Direct children of a node. */
    public List<String> children(String node) {
        return edges.getOrDefault(node, List.of());
    }

    /** Descendants of a node (BFS traversal). */
    public Set<String> descendants(String node) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(children(node));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.add(current)) {
                queue.addAll(children(current));
            }
        }
        return visited;
    }

    /** Ancestors of a node (BFS on reversed edges). */
    public Set<String> ancestors(String node) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(parents(node));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.add(current)) {
                queue.addAll(parents(current));
            }
        }
        return visited;
    }

    /**
     * Backdoor criterion: valid adjustment set Z.
     *
     * <p>Z = parents(treatment) — the simplest sufficient adjustment set
     * (Pearl, 2009, Theorem 3.3.2). Conditioning on parents of the treatment
     * variable blocks all backdoor paths while not introducing bias.</p>
     *
     * <p>When the treatment is a root node (no parents), Z = ∅ since
     * there are no backdoor paths to block.</p>
     *
     * @param treatment the intervention variable
     * @param outcome   the target variable
     * @return the set of variables to condition on
     */
    public Set<String> backdoorSet(String treatment, String outcome) {
        return new LinkedHashSet<>(parents(treatment));
    }

    /**
     * Interventional probability P(Y=1|do(X=x)) via backdoor adjustment.
     *
     * <p>When the backdoor set Z is empty, this equals the observational
     * conditional probability P(Y=1|X near x).</p>
     *
     * <p>Formula: P(Y|do(X=x)) = Σ_z P(Y|X=x, Z=z) × P(Z=z)</p>
     *
     * @param treatment      the intervention variable name
     * @param outcome        the outcome variable name
     * @param treatmentValue the fixed value for the do-intervention
     * @param data           map of variable name → array of observed values (all same length)
     * @return estimated interventional probability
     */
    public double interventionalProbability(String treatment, String outcome,
                                             double treatmentValue,
                                             Map<String, double[]> data) {
        Set<String> z = backdoorSet(treatment, outcome);

        if (z.isEmpty()) {
            return observationalProbability(treatment, outcome, treatmentValue, data);
        }

        double[] treatmentData = data.get(treatment);
        double[] outcomeData = data.get(outcome);
        int n = treatmentData.length;

        // Build covariate bins: each unique combination of binned Z values forms a stratum
        // For simplicity with multiple covariates, we use a single combined key
        Map<String, List<Integer>> strata = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            StringBuilder key = new StringBuilder();
            for (String zVar : z) {
                double[] zData = data.get(zVar);
                if (zData == null) continue;
                int bin = (int) Math.floor(zData[i] / BIN_WIDTH);
                key.append(zVar).append('=').append(bin).append(';');
            }
            strata.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(i);
        }

        double totalWeight = 0;
        double weightedSum = 0;

        for (var entry : strata.entrySet()) {
            List<Integer> indices = entry.getValue();
            double pZ = (double) indices.size() / n; // P(Z=z)

            // P(Y=1|X near x, Z=z): filter indices where treatment is near treatmentValue
            int matchCount = 0;
            int successCount = 0;
            for (int idx : indices) {
                if (Math.abs(treatmentData[idx] - treatmentValue) <= BIN_WIDTH / 2) {
                    matchCount++;
                    if (outcomeData[idx] > 0.5) {
                        successCount++;
                    }
                }
            }

            if (matchCount >= MIN_STRATUM_SIZE) {
                double pYgivenXZ = (double) successCount / matchCount;
                weightedSum += pYgivenXZ * pZ;
                totalWeight += pZ;
            }
        }

        return totalWeight > 0 ? weightedSum / totalWeight : 0.5;
    }

    /**
     * Observational conditional probability P(Y=1|X near x).
     *
     * <p>Bins observations where the treatment variable is within
     * {@link #BIN_WIDTH}/2 of the target value and computes the
     * proportion with outcome > 0.5.</p>
     *
     * @param treatment      the conditioning variable name
     * @param outcome        the target variable name
     * @param treatmentValue the conditioning value
     * @param data           map of variable name → observed values
     * @return estimated conditional probability
     */
    public double observationalProbability(String treatment, String outcome,
                                            double treatmentValue,
                                            Map<String, double[]> data) {
        double[] treatmentData = data.get(treatment);
        double[] outcomeData = data.get(outcome);
        int n = treatmentData.length;

        int matchCount = 0;
        int successCount = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(treatmentData[i] - treatmentValue) <= BIN_WIDTH / 2) {
                matchCount++;
                if (outcomeData[i] > 0.5) {
                    successCount++;
                }
            }
        }

        return matchCount >= MIN_STRATUM_SIZE ? (double) successCount / matchCount : 0.5;
    }

    /**
     * Fisher z-test for conditional independence of X and Y given Z.
     *
     * <p>Computes partial correlation r(X,Y|Z) = (r_xy - r_xz × r_yz) / sqrt((1-r_xz²)(1-r_yz²)),
     * then tests H₀: ρ = 0 via the Fisher z-transform:</p>
     *
     * <pre>
     * z_stat = sqrt(n - |Z| - 3) × 0.5 × ln((1+r)/(1-r))
     * </pre>
     *
     * @param x observations of variable X
     * @param y observations of variable Y
     * @param z observations of conditioning variable Z (may be null for unconditional test)
     * @return true if X and Y are conditionally independent given Z (|z_stat| < 1.96)
     */
    public boolean conditionalIndependence(double[] x, double[] y, double[] z) {
        double r;
        int effectiveN;

        if (z == null || z.length == 0) {
            r = pearsonCorrelation(x, y);
            effectiveN = x.length;
        } else {
            double rxy = pearsonCorrelation(x, y);
            double rxz = pearsonCorrelation(x, z);
            double ryz = pearsonCorrelation(y, z);

            double denom = Math.sqrt((1 - rxz * rxz) * (1 - ryz * ryz));
            if (denom < 1e-12) {
                return false; // perfectly collinear → cannot determine independence
            }
            r = (rxy - rxz * ryz) / denom;
            effectiveN = x.length;
        }

        // Fisher z-transform
        int condSize = (z != null && z.length > 0) ? 1 : 0;
        int df = effectiveN - condSize - 3;
        if (df <= 0) {
            return true; // insufficient data
        }

        // Clamp r to avoid log(0)
        r = Math.max(-0.9999, Math.min(0.9999, r));
        double fisherZ = Math.sqrt(df) * 0.5 * Math.log((1 + r) / (1 - r));

        return Math.abs(fisherZ) < INDEPENDENCE_THRESHOLD;
    }

    /** Pearson correlation coefficient between two arrays. */
    static double pearsonCorrelation(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < 2) return 0;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }
        double denom = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return denom < 1e-12 ? 0 : (n * sumXY - sumX * sumY) / denom;
    }
}
