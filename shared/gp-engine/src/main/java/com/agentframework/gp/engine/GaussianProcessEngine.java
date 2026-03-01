package com.agentframework.gp.engine;

import com.agentframework.gp.math.CholeskyDecomposition;
import com.agentframework.gp.math.DenseMatrix;
import com.agentframework.gp.math.RbfKernel;
import com.agentframework.gp.model.GpPosterior;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.gp.model.TrainingPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Gaussian Process regression engine.
 *
 * <p>Stateless and thread-safe: {@link #fit} produces a {@link GpPosterior},
 * {@link #predict} uses it. No mutable internal state.</p>
 *
 * <h3>GP posterior inference</h3>
 * <pre>
 * alpha = (K + sigma_n^2 I)^{-1} (y - mean)
 * mu(x*)    = mean + k*^T alpha
 * sigma^2(x*) = k(x*, x*) - v^T v    where L v = k*
 * </pre>
 *
 * @see <a href="http://www.gaussianprocess.org/gpml/">Rasmussen &amp; Williams, 2006</a>
 */
public class GaussianProcessEngine {

    private static final Logger log = LoggerFactory.getLogger(GaussianProcessEngine.class);

    private final RbfKernel kernel;
    private final double noiseVariance;

    public GaussianProcessEngine(RbfKernel kernel, double noiseVariance) {
        if (noiseVariance < 0) {
            throw new IllegalArgumentException("noiseVariance must be non-negative: " + noiseVariance);
        }
        this.kernel = kernel;
        this.noiseVariance = noiseVariance;
    }

    /**
     * Fits the GP on training data.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Compute mean reward (prior mean)</li>
     *   <li>Center targets: y_centered = y - mean</li>
     *   <li>Build kernel matrix K from training embeddings</li>
     *   <li>Add noise: K_noisy = K + sigma_n^2 I</li>
     *   <li>Cholesky decompose: L = chol(K_noisy)</li>
     *   <li>Compute alpha = L^T \ (L \ y_centered)</li>
     * </ol>
     *
     * @param trainingPoints training data (embedding + reward). Max recommended: 500.
     * @return fitted posterior for future predictions, or {@code null} if empty input
     */
    public GpPosterior fit(List<TrainingPoint> trainingPoints) {
        if (trainingPoints == null || trainingPoints.isEmpty()) {
            return null;
        }

        int n = trainingPoints.size();

        // Extract embeddings and rewards
        float[][] embeddings = new float[n][];
        double[] rewards = new double[n];
        double meanReward = 0.0;

        for (int i = 0; i < n; i++) {
            TrainingPoint tp = trainingPoints.get(i);
            embeddings[i] = tp.embedding();
            rewards[i] = tp.reward();
            meanReward += tp.reward();
        }
        meanReward /= n;

        // Center targets
        double[] yCentered = new double[n];
        for (int i = 0; i < n; i++) {
            yCentered[i] = rewards[i] - meanReward;
        }

        // Build kernel matrix + noise
        DenseMatrix K = kernel.computeMatrix(embeddings);
        DenseMatrix Knoisy = K.addDiagonal(noiseVariance);

        // Cholesky decomposition
        CholeskyDecomposition cholesky;
        try {
            cholesky = new CholeskyDecomposition(Knoisy);
        } catch (ArithmeticException e) {
            log.warn("Cholesky decomposition failed (matrix not positive definite, N={}). "
                    + "Falling back to prior. Cause: {}", n, e.getMessage());
            return null;
        }

        // Compute alpha = (K + noise*I)^{-1} y_centered
        double[] alpha = cholesky.solve(yCentered);

        log.debug("GP fitted on {} training points (meanReward={:.4f})", n, meanReward);
        return new GpPosterior(alpha, cholesky, embeddings, meanReward, kernel);
    }

    /**
     * Predicts (mu, sigma^2) for a new test point using a fitted posterior.
     *
     * <p>mu = mean + k*^T alpha</p>
     * <p>sigma^2 = k(x*, x*) - v^T v, where L v = k*</p>
     *
     * @param posterior a fitted GP posterior from {@link #fit}
     * @param embedding the test point embedding (same dimensionality as training)
     * @return prediction with mean and variance
     */
    public GpPrediction predict(GpPosterior posterior, float[] embedding) {
        if (posterior == null) {
            throw new IllegalArgumentException("posterior must not be null — use prior() for empty data");
        }

        // Cross-kernel vector: k* = [k(x*, x_1), ..., k(x*, x_N)]
        double[] kStar = posterior.kernel().computeCrossKernel(embedding, posterior.trainingEmbeddings());

        // Posterior mean: mu = meanReward + k*^T alpha
        double mu = posterior.meanReward();
        for (int i = 0; i < kStar.length; i++) {
            mu += kStar[i] * posterior.alpha()[i];
        }

        // Posterior variance: sigma^2 = k** - v^T v, where Lv = k*
        double kStarStar = posterior.kernel().selfKernel();
        double[] v = posterior.cholesky().solveForward(kStar);
        double vTv = 0.0;
        for (double vi : v) {
            vTv += vi * vi;
        }
        double sigma2 = kStarStar - vTv;

        // Numerical safety: variance can go slightly negative due to floating point
        sigma2 = Math.max(0.0, sigma2);

        return new GpPrediction(mu, sigma2);
    }

    /**
     * Returns a prior prediction when no training data is available.
     *
     * <p>mu = priorMean (e.g. global average reward or 0.5)</p>
     * <p>sigma^2 = kernel.selfKernel() (maximum uncertainty)</p>
     *
     * @param priorMean default expected reward
     */
    public GpPrediction prior(double priorMean) {
        return new GpPrediction(priorMean, kernel.selfKernel());
    }

    public RbfKernel kernel() { return kernel; }
    public double noiseVariance() { return noiseVariance; }
}
