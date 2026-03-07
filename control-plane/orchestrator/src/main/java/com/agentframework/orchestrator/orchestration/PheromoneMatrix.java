package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.WorkerType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pheromone matrix for Ant Colony Optimisation (ACO) of workflow patterns.
 *
 * <p>The matrix {@code tau[from][to]} tracks pheromone intensity on transitions between
 * {@link WorkerType} values. Higher pheromone indicates historically successful sequences.
 * The matrix is indexed by {@code WorkerType.ordinal()}.</p>
 *
 * <p>Operations:
 * <ul>
 *   <li><b>Deposit</b>: add pheromone to an edge proportional to plan reward</li>
 *   <li><b>Evaporate</b>: decay all edges by factor (1 - ρ)</li>
 *   <li><b>Transition probability</b>: P(next=j|current=i) = τ[i][j]^α / Σ_k τ[i][k]^α</li>
 * </ul>
 *
 * @see <a href="https://mitpress.mit.edu/9780262042192/">
 *     Dorigo &amp; Stützle (2004), Ant Colony Optimization</a>
 */
public class PheromoneMatrix {

    private final double[][] matrix;
    private final double initialPheromone;
    private final int size;

    /**
     * Creates a new pheromone matrix initialised to {@code initialPheromone} on all edges.
     *
     * @param initialPheromone τ₀ — initial pheromone level on all edges
     */
    public PheromoneMatrix(double initialPheromone) {
        this.size = WorkerType.values().length;
        this.initialPheromone = initialPheromone;
        this.matrix = new double[size][size];
        reset();
    }

    /** Resets all cells to {@code initialPheromone}. */
    public void reset() {
        for (int i = 0; i < size; i++) {
            Arrays.fill(matrix[i], initialPheromone);
        }
    }

    /** Returns the pheromone level on edge (from → to). */
    public double get(WorkerType from, WorkerType to) {
        return matrix[from.ordinal()][to.ordinal()];
    }

    /** Deposits additional pheromone on edge (from → to). */
    public void deposit(WorkerType from, WorkerType to, double amount) {
        matrix[from.ordinal()][to.ordinal()] += amount;
    }

    /**
     * Evaporates pheromone: τ(t+1) = (1 - ρ) × τ(t) for all edges.
     *
     * @param rho evaporation rate ∈ [0, 1]
     */
    public void evaporate(double rho) {
        double factor = 1.0 - rho;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                matrix[i][j] *= factor;
            }
        }
    }

    /**
     * Computes transition probabilities from {@code from} to all other worker types.
     *
     * <p>P(next=j | current=i) = τ[i][j]^α / Σ_k τ[i][k]^α</p>
     *
     * @param from  current worker type
     * @param alpha exponent (typically 1.0)
     * @return map of worker type → transition probability (sums to 1.0)
     */
    public Map<WorkerType, Double> transitionProbabilities(WorkerType from, double alpha) {
        WorkerType[] types = WorkerType.values();
        double[] powered = new double[size];
        double sum = 0;
        int fromIdx = from.ordinal();

        for (int j = 0; j < size; j++) {
            powered[j] = Math.pow(matrix[fromIdx][j], alpha);
            sum += powered[j];
        }

        Map<WorkerType, Double> probs = new LinkedHashMap<>();
        for (int j = 0; j < size; j++) {
            probs.put(types[j], sum > 0 ? powered[j] / sum : 1.0 / size);
        }
        return probs;
    }

    /**
     * Suggests the top-k most likely next worker types from {@code from}.
     *
     * @param from  current worker type
     * @param alpha exponent
     * @param topK  max results
     * @return ordered list of most likely next worker types
     */
    public List<WorkerType> suggestNext(WorkerType from, double alpha, int topK) {
        Map<WorkerType, Double> probs = transitionProbabilities(from, alpha);
        return probs.entrySet().stream()
                .sorted(Map.Entry.<WorkerType, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /** Serialises the matrix to a flat array (row-major) for Redis storage. */
    public double[] toFlatArray() {
        double[] flat = new double[size * size];
        for (int i = 0; i < size; i++) {
            System.arraycopy(matrix[i], 0, flat, i * size, size);
        }
        return flat;
    }

    /**
     * Deserialises a pheromone matrix from a flat array.
     *
     * @param flat              row-major flat array (size × size elements)
     * @param initialPheromone  τ₀ value (used for reset, not for initialisation from flat)
     * @return reconstructed matrix
     */
    public static PheromoneMatrix fromFlatArray(double[] flat, double initialPheromone) {
        PheromoneMatrix m = new PheromoneMatrix(initialPheromone);
        int n = m.size;
        if (flat.length != n * n) {
            // Dimension mismatch (e.g. WorkerType enum changed) — keep defaults
            return m;
        }
        for (int i = 0; i < n; i++) {
            System.arraycopy(flat, i * n, m.matrix[i], 0, n);
        }
        return m;
    }

    /** Number of worker types (matrix dimension). */
    public int size() {
        return size;
    }
}
