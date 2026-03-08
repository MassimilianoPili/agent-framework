package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.SingularValueDecomposition_F64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Compresses task embeddings using the Information Bottleneck principle.
 *
 * <p>The Information Bottleneck (Tishby et al., 1999) finds a compressed
 * representation Z of input X that maximises mutual information with target Y
 * while minimising the complexity of Z:
 * <pre>
 *   max I(Z; Y) − β · I(Z; X)
 * </pre>
 * where X = task embedding (1024-dim), Y = actual_reward, Z = compressed embedding.</p>
 *
 * <p>Implementation uses SVD as a linear IB approximation on the outcome matrix:
 * <ol>
 *   <li>Build embedding matrix E (n × d) and reward vector r (n × 1).</li>
 *   <li>Centre E by subtracting column means.</li>
 *   <li>Truncated SVD: keep top-k singular vectors as the compressed basis.</li>
 *   <li>Project E onto the k-dim subspace: Z = E · V_k (n × k).</li>
 *   <li>Estimate I(Z;Y) as correlation between ||Z_i|| and r_i (proxy).</li>
 *   <li>Estimate I(Z;X) as fraction of variance explained by top-k components.</li>
 * </ol>
 * The compression ratio is determined by {@code compression-ratio} config (default 0.125
 * → 1024 × 0.125 = 128 dims).</p>
 *
 * @see <a href="https://arxiv.org/abs/physics/0004057">Tishby, Pereira &amp; Bialek (1999), The Information Bottleneck Method</a>
 */
@Service
@ConditionalOnProperty(prefix = "information-bottleneck", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InformationBottleneckService {

    private static final Logger log = LoggerFactory.getLogger(InformationBottleneckService.class);

    static final int MIN_SAMPLES = 10;
    static final int MAX_SAMPLES = 200;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${information-bottleneck.compression-ratio:0.125}")
    private double compressionRatio;

    @Value("${information-bottleneck.beta:1.0}")
    private double beta;

    public InformationBottleneckService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Compresses task embeddings for the given worker type and estimates IB mutual information.
     *
     * @param workerType worker type name (e.g. "BE")
     * @return IB compression report, or null if insufficient data
     */
    public IBCompressionReport compress(String workerType) {
        // [task_key, worker_profile, actual_reward, gp_mu, task_embedding_text]
        List<Object[]> rows = taskOutcomeRepository.findOutcomesWithEmbeddingByWorkerType(
                workerType, MAX_SAMPLES);

        // Parse embedding vectors and rewards
        List<double[]> embeddingList = new ArrayList<>();
        List<Double>   rewardList    = new ArrayList<>();

        for (Object[] row : rows) {
            if (row[2] == null || row[4] == null) continue;
            double reward = ((Number) row[2]).doubleValue();
            double[] emb  = parseEmbedding((String) row[4]);
            if (emb != null && emb.length > 0) {
                embeddingList.add(emb);
                rewardList.add(reward);
            }
        }

        if (embeddingList.size() < MIN_SAMPLES) {
            log.debug("InformationBottleneck for {}: only {} samples, need {}",
                      workerType, embeddingList.size(), MIN_SAMPLES);
            return null;
        }

        int n = embeddingList.size();
        int originalDim = embeddingList.get(0).length;
        int compressedDim = Math.max(1, (int) Math.round(originalDim * compressionRatio));
        // Cap compressedDim by available data
        compressedDim = Math.min(compressedDim, Math.min(n - 1, originalDim));

        // Build centred embedding matrix (n × d)
        DMatrixRMaj E = buildCentredMatrix(embeddingList, n, originalDim);

        // SVD decomposition: E = U · S · V^T
        SingularValueDecomposition_F64<DMatrixRMaj> svd =
                DecompositionFactory_DDRM.svd(n, originalDim, false, true, false);

        if (!svd.decompose(E)) {
            log.warn("InformationBottleneck for {}: SVD decomposition failed", workerType);
            return null;
        }

        double[] singularValues = svd.getSingularValues();
        double totalVariance = 0.0;
        for (double sv : singularValues) totalVariance += sv * sv;

        // Variance explained by top-k components — proxy for I(Z; X)
        double explainedVariance = 0.0;
        for (int i = 0; i < compressedDim && i < singularValues.length; i++) {
            explainedVariance += singularValues[i] * singularValues[i];
        }
        double mutualInfoZX = totalVariance > 0 ? explainedVariance / totalVariance : 0;

        // Proxy for I(Z; Y): correlation between compressed norms and rewards
        // Project onto first singular vector (dominant direction) and correlate with reward
        DMatrixRMaj V = svd.getV(null, true);  // d × n (transposed)
        double mutualInfoZY = estimateRewardCorrelation(E, V, rewardList, compressedDim, n, originalDim);

        log.debug("InformationBottleneck for {}: {}→{} dims, I(Z;X)={:.4f}, I(Z;Y)={:.4f}, β={}",
                  workerType, originalDim, compressedDim,
                  mutualInfoZX, mutualInfoZY, beta);

        return new IBCompressionReport(originalDim, compressedDim, beta, mutualInfoZY, mutualInfoZX);
    }

    private DMatrixRMaj buildCentredMatrix(List<double[]> embeddings, int n, int d) {
        // Compute column means
        double[] means = new double[d];
        for (double[] emb : embeddings) {
            for (int j = 0; j < Math.min(d, emb.length); j++) {
                means[j] += emb[j];
            }
        }
        for (int j = 0; j < d; j++) means[j] /= n;

        DMatrixRMaj E = new DMatrixRMaj(n, d);
        for (int i = 0; i < n; i++) {
            double[] emb = embeddings.get(i);
            for (int j = 0; j < Math.min(d, emb.length); j++) {
                E.set(i, j, emb[j] - means[j]);
            }
        }
        return E;
    }

    /**
     * Computes correlation between projected norms and actual rewards (I(Z;Y) proxy).
     * Higher correlation → compressed representation retains reward-relevant information.
     */
    private double estimateRewardCorrelation(DMatrixRMaj E, DMatrixRMaj V,
                                              List<Double> rewards, int k, int n, int d) {
        // Project each sample onto first k singular vectors
        double[] projNorm = new double[n];
        for (int i = 0; i < n; i++) {
            double norm2 = 0.0;
            for (int comp = 0; comp < k && comp < V.numRows; comp++) {
                double dot = 0.0;
                for (int j = 0; j < d && j < V.numCols; j++) {
                    dot += E.get(i, j) * V.get(comp, j);
                }
                norm2 += dot * dot;
            }
            projNorm[i] = Math.sqrt(norm2);
        }

        // Pearson correlation between projNorm and rewards
        double meanNorm   = 0.0, meanReward = 0.0;
        for (int i = 0; i < n; i++) {
            meanNorm   += projNorm[i];
            meanReward += rewards.get(i);
        }
        meanNorm   /= n;
        meanReward /= n;

        double cov = 0.0, varNorm = 0.0, varReward = 0.0;
        for (int i = 0; i < n; i++) {
            double dn = projNorm[i] - meanNorm;
            double dr = rewards.get(i) - meanReward;
            cov      += dn * dr;
            varNorm  += dn * dn;
            varReward += dr * dr;
        }

        double denom = Math.sqrt(varNorm * varReward);
        return denom > 1e-10 ? Math.abs(cov / denom) : 0.0;
    }

    /**
     * Parses a pgvector text embedding of the form "[0.1,0.2,...]".
     * Returns null if the text is blank or unparseable.
     */
    static double[] parseEmbedding(String embeddingText) {
        if (embeddingText == null || embeddingText.isBlank()) return null;
        String trimmed = embeddingText.strip();
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]"))   trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] parts = trimmed.split(",");
        double[] result = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                result[i] = Double.parseDouble(parts[i].strip());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return result;
    }

    /**
     * Information Bottleneck compression report.
     *
     * @param originalDim    dimensionality of the input embedding
     * @param compressedDim  dimensionality after IB compression
     * @param beta           IB trade-off parameter β
     * @param mutualInfoZY   estimated I(Z; Y) — reward-relevant information retained
     * @param mutualInfoZX   estimated I(Z; X) — fraction of input variance captured
     */
    public record IBCompressionReport(
            int originalDim,
            int compressedDim,
            double beta,
            double mutualInfoZY,
            double mutualInfoZX
    ) {}
}
