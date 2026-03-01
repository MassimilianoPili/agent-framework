package com.agentframework.gp.math;

/**
 * Radial Basis Function (Squared Exponential) kernel.
 *
 * <p>k(x&#7522;, x&#11388;) = &sigma;&sup2; &middot; exp(-0.5 &middot; ||x&#7522; - x&#11388;||&sup2; / l&sup2;)</p>
 *
 * <p>Isotropic: single {@code lengthScale} for all dimensions.
 * ARD (per-dimension lengthscale) deferred to a future iteration.</p>
 *
 * <p>Hyperparameters are fixed at construction time:</p>
 * <ul>
 *   <li>{@code signalVariance} (&sigma;&sup2;) — amplitude of function variations</li>
 *   <li>{@code lengthScale} (l) — how far points must be to be considered different</li>
 * </ul>
 *
 * <p>Operates on {@code float[]} embeddings (1024 dim from mxbai-embed-large).
 * Uses float for storage (halves memory) but double for kernel computation (numerical stability).</p>
 */
public final class RbfKernel {

    private final double signalVariance;
    private final double lengthScale;
    private final double negHalfInvLs2; // pre-computed: -0.5 / lengthScale^2

    public RbfKernel(double signalVariance, double lengthScale) {
        if (signalVariance <= 0) {
            throw new IllegalArgumentException("signalVariance must be positive: " + signalVariance);
        }
        if (lengthScale <= 0) {
            throw new IllegalArgumentException("lengthScale must be positive: " + lengthScale);
        }
        this.signalVariance = signalVariance;
        this.lengthScale = lengthScale;
        this.negHalfInvLs2 = -0.5 / (lengthScale * lengthScale);
    }

    /** Computes k(x&#7522;, x&#11388;). */
    public double compute(float[] xi, float[] xj) {
        double sqDist = squaredDistance(xi, xj);
        return signalVariance * Math.exp(sqDist * negHalfInvLs2);
    }

    /**
     * Computes the full kernel matrix K[i][j] = k(X[i], X[j]).
     * Exploits symmetry: only computes lower triangle, then copies.
     *
     * @param embeddings N embeddings, each of dimension D
     * @return N&times;N symmetric kernel matrix
     */
    public DenseMatrix computeMatrix(float[][] embeddings) {
        int n = embeddings.length;
        DenseMatrix K = new DenseMatrix(n, n);
        for (int i = 0; i < n; i++) {
            K.set(i, i, signalVariance); // k(x,x) = signalVariance
            for (int j = 0; j < i; j++) {
                double kij = compute(embeddings[i], embeddings[j]);
                K.set(i, j, kij);
                K.set(j, i, kij);
            }
        }
        return K;
    }

    /**
     * Computes the cross-kernel vector k* = [k(x*, x&#8321;), ..., k(x*, x&#8345;)].
     *
     * @param xStar             test point embedding
     * @param trainingEmbeddings N training embeddings
     * @return vector of length N
     */
    public double[] computeCrossKernel(float[] xStar, float[][] trainingEmbeddings) {
        int n = trainingEmbeddings.length;
        double[] kStar = new double[n];
        for (int i = 0; i < n; i++) {
            kStar[i] = compute(xStar, trainingEmbeddings[i]);
        }
        return kStar;
    }

    /** k(x*, x*) = signalVariance (self-kernel, maximum prior variance). */
    public double selfKernel() {
        return signalVariance;
    }

    public double signalVariance() { return signalVariance; }
    public double lengthScale() { return lengthScale; }

    /** Squared Euclidean distance between two float vectors. */
    private static double squaredDistance(float[] a, float[] b) {
        double sum = 0.0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            double diff = (double) a[i] - (double) b[i];
            sum += diff * diff;
        }
        return sum;
    }
}
