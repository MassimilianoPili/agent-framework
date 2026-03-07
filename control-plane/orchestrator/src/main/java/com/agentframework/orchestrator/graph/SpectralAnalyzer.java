package com.agentframework.orchestrator.graph;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.EigenDecomposition_F64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Spectral analysis of a plan's task DAG using Laplacian eigenvalues.
 *
 * <p>Computes the graph Laplacian {@code L = D - A} (where D is the degree matrix and A the
 * symmetric adjacency matrix) and performs eigendecomposition via EJML. The spectrum reveals:
 * <ul>
 *   <li><b>Algebraic connectivity</b> (Fiedler value, λ₁): robustness of the graph</li>
 *   <li><b>Spectral gap</b> (λ₁ / λ_max): modularity indicator</li>
 *   <li><b>Fiedler partition</b>: bisection based on the sign of the Fiedler eigenvector</li>
 *   <li><b>Bottlenecks</b>: boundary nodes near the partition cut</li>
 * </ul>
 *
 * @see <a href="https://doi.org/10.21136/CMJ.1973.101168">
 *     Fiedler, M. (1973), "Algebraic connectivity of graphs"</a>
 * @see <a href="https://www.cs.yale.edu/homes/spielman/561/">
 *     Spielman, D. (2012), "Spectral Graph Theory", Yale</a>
 */
@Service
public class SpectralAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SpectralAnalyzer.class);

    /** Threshold for identifying bottleneck nodes (|fiedler[i]| < threshold). */
    private static final double BOTTLENECK_THRESHOLD = 0.3;

    /** Tolerance for eigenvalues considered zero. */
    private static final double EPSILON = 1e-9;

    /**
     * Performs full spectral analysis on a plan's task DAG.
     *
     * @param plan the plan whose items form the DAG
     * @return spectral metrics including Fiedler value, partition, and bottlenecks
     */
    public SpectralMetrics analyse(Plan plan) {
        List<PlanItem> items = plan.getItems();
        if (items.isEmpty()) {
            return new SpectralMetrics(plan.getId(), 0.0, 0.0, 0.0,
                    List.of(), List.of(), new double[0], 0, 0);
        }

        // Build task key index
        String[] taskKeys = items.stream().map(PlanItem::getTaskKey).toArray(String[]::new);
        Map<String, Integer> keyIndex = new LinkedHashMap<>();
        for (int i = 0; i < taskKeys.length; i++) {
            keyIndex.put(taskKeys[i], i);
        }

        int n = taskKeys.length;

        // Build symmetric adjacency matrix and count edges
        double[][] adjacency = buildSymmetricAdjacency(items, keyIndex, n);
        int edgeCount = countEdges(adjacency, n);

        if (n == 1) {
            return new SpectralMetrics(plan.getId(), 0.0, 0.0, 0.0,
                    List.of(List.of(taskKeys[0])), List.of(), new double[]{0.0}, 1, 0);
        }

        // Build Laplacian
        double[][] laplacian = buildLaplacian(adjacency, n);

        // Eigendecomposition
        double[] eigenvalues = computeEigenvalues(laplacian, n);
        Arrays.sort(eigenvalues);

        // Fiedler value = second-smallest eigenvalue
        double fiedlerValue = eigenvalues.length >= 2 ? eigenvalues[1] : 0.0;
        double lambdaMax = eigenvalues[eigenvalues.length - 1];
        double spectralGap = lambdaMax > EPSILON ? fiedlerValue / lambdaMax : 0.0;

        // Fiedler partition and bottlenecks
        List<List<String>> partition;
        List<String> bottlenecks;

        if (fiedlerValue < EPSILON) {
            // Disconnected graph — partition by connected components instead
            partition = partitionByComponents(items, keyIndex, taskKeys, n);
            bottlenecks = List.of();
        } else {
            double[] fiedlerVector = extractFiedlerVector(laplacian, n, eigenvalues);
            partition = fiedlerPartition(fiedlerVector, taskKeys);
            bottlenecks = identifyBottlenecks(fiedlerVector, taskKeys);
        }

        log.debug("Spectral analysis for plan {}: fiedler={}, spectralGap={}, nodes={}, edges={}",
                plan.getId(), fiedlerValue, spectralGap, n, edgeCount);

        return new SpectralMetrics(
                plan.getId(), fiedlerValue, spectralGap, fiedlerValue,
                partition, bottlenecks, eigenvalues, n, edgeCount);
    }

    // ── Package-private for testing ─────────────────────────────────────────────

    /**
     * Builds a symmetric adjacency matrix from the DAG.
     * If A→B exists as a dependency, both adj[A][B] and adj[B][A] are set to 1.
     */
    double[][] buildSymmetricAdjacency(List<PlanItem> items, Map<String, Integer> keyIndex, int n) {
        double[][] adj = new double[n][n];
        for (PlanItem item : items) {
            Integer toIdx = keyIndex.get(item.getTaskKey());
            if (toIdx == null) continue;
            for (String dep : item.getDependsOn()) {
                Integer fromIdx = keyIndex.get(dep);
                if (fromIdx != null) {
                    adj[fromIdx][toIdx] = 1.0;
                    adj[toIdx][fromIdx] = 1.0;
                }
            }
        }
        return adj;
    }

    /**
     * Builds the Laplacian matrix: L[i][i] = degree(i), L[i][j] = -adjacency[i][j].
     */
    double[][] buildLaplacian(double[][] adjacency, int n) {
        double[][] L = new double[n][n];
        for (int i = 0; i < n; i++) {
            double degree = 0;
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    L[i][j] = -adjacency[i][j];
                    degree += adjacency[i][j];
                }
            }
            L[i][i] = degree;
        }
        return L;
    }

    /**
     * Computes eigenvalues of the Laplacian using EJML's eigendecomposition.
     */
    double[] computeEigenvalues(double[][] laplacian, int n) {
        DMatrixRMaj matrix = new DMatrixRMaj(laplacian);
        EigenDecomposition_F64<DMatrixRMaj> eig = DecompositionFactory_DDRM.eig(n, true);

        if (!eig.decompose(matrix)) {
            log.warn("Eigendecomposition failed, returning zeros");
            return new double[n];
        }

        double[] eigenvalues = new double[eig.getNumberOfEigenvalues()];
        for (int i = 0; i < eigenvalues.length; i++) {
            eigenvalues[i] = eig.getEigenvalue(i).getReal();
        }
        return eigenvalues;
    }

    /**
     * Extracts the Fiedler eigenvector (eigenvector corresponding to the second-smallest eigenvalue).
     */
    private double[] extractFiedlerVector(double[][] laplacian, int n, double[] sortedEigenvalues) {
        DMatrixRMaj matrix = new DMatrixRMaj(laplacian);
        EigenDecomposition_F64<DMatrixRMaj> eig = DecompositionFactory_DDRM.eig(n, true);
        eig.decompose(matrix);

        // Find the eigenvector for the second-smallest eigenvalue
        double targetLambda = sortedEigenvalues.length >= 2 ? sortedEigenvalues[1] : 0.0;
        int bestIdx = 0;
        double bestDiff = Double.MAX_VALUE;

        for (int i = 0; i < eig.getNumberOfEigenvalues(); i++) {
            double lambda = eig.getEigenvalue(i).getReal();
            // Skip eigenvalues ≈ 0 (the trivial eigenvalue)
            if (Math.abs(lambda) < EPSILON) continue;
            double diff = Math.abs(lambda - targetLambda);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }

        DMatrixRMaj eigVec = eig.getEigenVector(bestIdx);
        if (eigVec == null) {
            return new double[n];
        }

        double[] vector = new double[n];
        for (int i = 0; i < n; i++) {
            vector[i] = eigVec.get(i, 0);
        }
        return vector;
    }

    /**
     * Partitions nodes based on the sign of the Fiedler vector.
     * Positive values → group 0, negative values → group 1.
     */
    List<List<String>> fiedlerPartition(double[] fiedlerVector, String[] taskKeys) {
        List<String> group0 = new ArrayList<>();
        List<String> group1 = new ArrayList<>();
        for (int i = 0; i < taskKeys.length; i++) {
            if (fiedlerVector[i] >= 0) {
                group0.add(taskKeys[i]);
            } else {
                group1.add(taskKeys[i]);
            }
        }
        List<List<String>> partition = new ArrayList<>();
        if (!group0.isEmpty()) partition.add(group0);
        if (!group1.isEmpty()) partition.add(group1);
        return partition;
    }

    /**
     * Identifies bottleneck nodes: those near the partition boundary
     * (|fiedler[i]| < threshold, scaled by max component magnitude).
     */
    List<String> identifyBottlenecks(double[] fiedlerVector, String[] taskKeys) {
        double maxAbs = 0;
        for (double v : fiedlerVector) {
            maxAbs = Math.max(maxAbs, Math.abs(v));
        }
        if (maxAbs < EPSILON) return List.of();

        double threshold = BOTTLENECK_THRESHOLD * maxAbs;
        List<String> bottlenecks = new ArrayList<>();
        for (int i = 0; i < taskKeys.length; i++) {
            if (Math.abs(fiedlerVector[i]) < threshold) {
                bottlenecks.add(taskKeys[i]);
            }
        }
        return bottlenecks;
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private int countEdges(double[][] adjacency, int n) {
        int count = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (adjacency[i][j] > 0) count++;
            }
        }
        return count;
    }

    /**
     * Partition by connected components (used when Fiedler value ≈ 0).
     */
    private List<List<String>> partitionByComponents(
            List<PlanItem> items, Map<String, Integer> keyIndex, String[] taskKeys, int n) {
        boolean[] visited = new boolean[n];
        List<List<String>> components = new ArrayList<>();

        for (int start = 0; start < n; start++) {
            if (visited[start]) continue;
            List<String> component = new ArrayList<>();
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(start);
            visited[start] = true;

            while (!stack.isEmpty()) {
                int node = stack.pop();
                component.add(taskKeys[node]);

                // Find neighbours (both directions)
                PlanItem item = items.get(node);
                for (String dep : item.getDependsOn()) {
                    Integer idx = keyIndex.get(dep);
                    if (idx != null && !visited[idx]) {
                        visited[idx] = true;
                        stack.push(idx);
                    }
                }
                // Also check items that depend on this node
                for (PlanItem other : items) {
                    if (other.getDependsOn().contains(item.getTaskKey())) {
                        Integer idx = keyIndex.get(other.getTaskKey());
                        if (idx != null && !visited[idx]) {
                            visited[idx] = true;
                            stack.push(idx);
                        }
                    }
                }
            }
            components.add(component);
        }
        return components;
    }
}
