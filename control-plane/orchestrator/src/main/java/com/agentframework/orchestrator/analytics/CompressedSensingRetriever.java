package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Sparse signal recovery for RAG query optimisation using the
 * Orthogonal Matching Pursuit (OMP) algorithm.
 *
 * <p><b>Compressed sensing premise.</b>
 * Candès, Romberg and Tao (2006) showed that a K-sparse signal x ∈ ℝⁿ can be
 * recovered from m ≪ n linear measurements y = Φx with m = O(K·log(n/K))
 * measurements, provided the measurement matrix satisfies the Restricted
 * Isometry Property (RIP).
 *
 * <p>Applied to RAG retrieval: task embeddings in the K-dimensional space of
 * "prototypical task categories" (e.g. BACKEND, FRONTEND, DATABASE, SECURITY …)
 * are naturally sparse — a typical task belongs predominantly to 1–3 categories.
 * Instead of searching all n vectors, OMP identifies the sparse representation
 * and retrieves only the relevant sub-dictionary atoms.
 *
 * <p><b>OMP algorithm.</b>
 * <pre>
 *   Input:  query y ∈ ℝⁿ,  dictionary D ∈ ℝⁿˣᵏ (columns = atoms, unit-norm),
 *           maxAtoms K
 *   Output: sparse coefficients s ∈ ℝᵏ, support T ⊆ {0,…,k−1}
 *
 *   Initialise: residual r = y, support T = ∅
 *   For t = 1, …, K:
 *     1. j* = argmax_j |⟨r, dⱼ⟩|       (most correlated atom)
 *     2. T ← T ∪ {j*}
 *     3. s_T = (D_T^⊤ D_T)⁻¹ D_T^⊤ y  (least-squares projection onto D_T)
 *     4. r = y − D_T s_T               (update residual)
 *     5. If ‖r‖² / ‖y‖² &lt; stopThreshold → break
 * </pre>
 *
 * <p><b>Computational complexity.</b>
 * OMP runs in O(K·n·k) — linear in the query dimension n and the dictionary
 * size k, compared to O(n) for brute-force vector search with n stored embeddings.
 * When K ≪ k ≪ n, the savings are substantial.
 *
 * @see <a href="https://doi.org/10.1109/TIT.2006.885507">
 *     Candès, Romberg, Tao (2006) — Robust uncertainty principles</a>
 * @see <a href="https://doi.org/10.1109/18.382009">
 *     Pati, Rezaiifar, Krishnaprasad (1993) — Orthogonal Matching Pursuit</a>
 */
@Service
@ConditionalOnProperty(prefix = "compressed-sensing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CompressedSensingRetriever {

    private static final Logger log = LoggerFactory.getLogger(CompressedSensingRetriever.class);

    /**
     * Default residual-energy stopping threshold.
     * OMP stops early when ‖r‖² / ‖y‖² falls below this value.
     */
    private static final double DEFAULT_STOP_THRESHOLD = 0.01;

    /**
     * Runs OMP to find the K-sparse representation of the query in the given dictionary.
     *
     * @param query     query embedding vector y ∈ ℝⁿ
     * @param dictionary column-matrix of dictionary atoms D ∈ ℝⁿˣᵏ (list of columns)
     * @param atomLabels human-readable labels for each dictionary column
     * @param maxAtoms  maximum number of atoms K to include in the sparse support
     * @return sparse reconstruction report
     * @throws IllegalArgumentException if query/dictionary dimensions are inconsistent
     */
    public SparsityReport reconstruct(double[] query,
                                       List<double[]> dictionary,
                                       List<String> atomLabels,
                                       int maxAtoms) {
        return reconstruct(query, dictionary, atomLabels, maxAtoms, DEFAULT_STOP_THRESHOLD);
    }

    /**
     * Runs OMP with a custom stopping threshold.
     */
    public SparsityReport reconstruct(double[] query,
                                       List<double[]> dictionary,
                                       List<String> atomLabels,
                                       int maxAtoms,
                                       double stopThreshold) {
        if (query == null || query.length == 0) {
            throw new IllegalArgumentException("Query vector must be non-empty");
        }
        if (dictionary == null || dictionary.isEmpty()) {
            throw new IllegalArgumentException("Dictionary must contain at least one atom");
        }
        int n = query.length;
        int k = dictionary.size();
        for (int j = 0; j < k; j++) {
            if (dictionary.get(j).length != n) {
                throw new IllegalArgumentException(
                        "Atom " + j + " dimension mismatch: expected " + n +
                        ", got " + dictionary.get(j).length);
            }
        }

        double queryNormSq = dot(query, query);

        // Normalise dictionary columns to unit norm (OMP requires this)
        List<double[]> normalised = new ArrayList<>(k);
        for (double[] atom : dictionary) {
            double norm = Math.sqrt(dot(atom, atom));
            normalised.add(norm > 1e-12 ? scale(atom, 1.0 / norm) : atom.clone());
        }

        // ── OMP main loop ──────────────────────────────────────────────────────
        double[] residual = query.clone();
        List<Integer> supportIdx  = new ArrayList<>();
        List<Double>  coefficients = new ArrayList<>();

        for (int t = 0; t < Math.min(maxAtoms, k); t++) {
            // Step 1: find most correlated atom
            int best = -1;
            double bestCorr = -1;
            for (int j = 0; j < k; j++) {
                if (supportIdx.contains(j)) continue;
                double corr = Math.abs(dot(residual, normalised.get(j)));
                if (corr > bestCorr) { bestCorr = corr; best = j; }
            }
            if (best < 0) break;
            supportIdx.add(best);

            // Step 2: least-squares projection onto current support D_T
            double[] lsCoeffs = leastSquaresProjection(query, normalised, supportIdx);
            coefficients = new ArrayList<>();
            for (double c : lsCoeffs) coefficients.add(c);

            // Step 3: update residual r = y - D_T s_T
            residual = query.clone();
            for (int i = 0; i < supportIdx.size(); i++) {
                double[] atom = normalised.get(supportIdx.get(i));
                double c = lsCoeffs[i];
                for (int d = 0; d < n; d++) residual[d] -= c * atom[d];
            }

            // Step 4: early stopping
            double relResidual = queryNormSq > 1e-12
                    ? dot(residual, residual) / queryNormSq : 0.0;
            if (relResidual < stopThreshold) break;
        }

        // Build result
        double relResidualFinal = queryNormSq > 1e-12
                ? dot(residual, residual) / queryNormSq : 0.0;
        double reconstructionQuality = 1.0 - relResidualFinal;

        List<String> selectedAtoms = new ArrayList<>();
        Map<String, Double> atomCoefficients = new LinkedHashMap<>();
        for (int i = 0; i < supportIdx.size(); i++) {
            int idx = supportIdx.get(i);
            String label = (atomLabels != null && idx < atomLabels.size())
                    ? atomLabels.get(idx) : "atom_" + idx;
            selectedAtoms.add(label);
            atomCoefficients.put(label, i < coefficients.size() ? coefficients.get(i) : 0.0);
        }

        log.debug("OMP: {} atoms selected, reconstruction quality={}", selectedAtoms.size(), reconstructionQuality);
        return new SparsityReport(selectedAtoms, atomCoefficients, reconstructionQuality,
                relResidualFinal, supportIdx.size());
    }

    // ── Linear algebra helpers ─────────────────────────────────────────────────

    private double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private double[] scale(double[] a, double c) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] * c;
        return r;
    }

    /**
     * Solves the least-squares system D_T s_T = y for s_T using the normal equations.
     * D_T^⊤ D_T s_T = D_T^⊤ y  →  s_T = (D_T^⊤ D_T)⁻¹ D_T^⊤ y
     * Uses Gaussian elimination for the small (K × K) normal system.
     */
    private double[] leastSquaresProjection(double[] y,
                                             List<double[]> atoms,
                                             List<Integer> support) {
        int m = support.size();
        // Build D_T^⊤ D_T  (m×m) and D_T^⊤ y  (m×1)
        double[][] gram = new double[m][m];
        double[] rhs    = new double[m];
        for (int i = 0; i < m; i++) {
            double[] ai = atoms.get(support.get(i));
            rhs[i] = dot(ai, y);
            for (int j = 0; j < m; j++) {
                gram[i][j] = dot(ai, atoms.get(support.get(j)));
            }
        }
        return gaussianElimination(gram, rhs);
    }

    /** Gaussian elimination with partial pivoting for small m×m systems. */
    private double[] gaussianElimination(double[][] A, double[] b) {
        int m = b.length;
        double[][] aug = new double[m][m + 1];
        for (int i = 0; i < m; i++) {
            System.arraycopy(A[i], 0, aug[i], 0, m);
            aug[i][m] = b[i];
        }
        for (int col = 0; col < m; col++) {
            // Partial pivot
            int pivot = col;
            for (int row = col + 1; row < m; row++) {
                if (Math.abs(aug[row][col]) > Math.abs(aug[pivot][col])) pivot = row;
            }
            double[] tmp = aug[col]; aug[col] = aug[pivot]; aug[pivot] = tmp;
            if (Math.abs(aug[col][col]) < 1e-12) continue; // singular — skip
            double diag = aug[col][col];
            for (int j = col; j <= m; j++) aug[col][j] /= diag;
            for (int row = 0; row < m; row++) {
                if (row == col) continue;
                double factor = aug[row][col];
                for (int j = col; j <= m; j++) aug[row][j] -= factor * aug[col][j];
            }
        }
        double[] x = new double[m];
        for (int i = 0; i < m; i++) x[i] = aug[i][m];
        return x;
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────

    /**
     * Result of an OMP sparse reconstruction.
     *
     * @param selectedAtoms        ordered list of selected dictionary atom labels
     * @param atomCoefficients     map from atom label to its least-squares coefficient
     * @param reconstructionQuality 1 − ‖residual‖²/‖query‖²; 1.0 = perfect reconstruction
     * @param relativeResidualEnergy ‖residual‖²/‖query‖²; lower = better
     * @param sparsity             number of atoms selected (K in the sparse representation)
     */
    public record SparsityReport(
            List<String> selectedAtoms,
            Map<String, Double> atomCoefficients,
            double reconstructionQuality,
            double relativeResidualEnergy,
            int sparsity
    ) {}
}
