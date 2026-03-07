package com.agentframework.orchestrator.analytics;

/**
 * Fisher Information metric for uncertainty decomposition and information gain.
 *
 * <p>For the normal distribution (GP posterior), Fisher Information has closed-form
 * expressions that quantify the "informativeness" of observations:</p>
 * <ul>
 *   <li>I(μ) = n/σ² — information about the mean</li>
 *   <li>I(σ²) = n/(2σ⁴) — information about the variance</li>
 *   <li>Cramér-Rao: Var(μ̂) ≥ 1/I(μ) = σ²/n — no unbiased estimator can do better</li>
 * </ul>
 *
 * <p>In the agent framework context, this decomposes worker profile uncertainty
 * into reducible (more data helps) vs irreducible (noise floor) components,
 * directly informing exploration vs exploitation decisions.</p>
 *
 * @see <a href="https://doi.org/10.1017/S0305004100009580">
 *     Fisher (1925), Theory of Statistical Estimation,
 *     Mathematical Proceedings of the Cambridge Philosophical Society 22(5)</a>
 */
public final class FisherInformation {

    private FisherInformation() {}

    /** Guard threshold for zero variance. */
    private static final double EPSILON = 1e-12;

    /**
     * Fisher information for the mean of a normal distribution.
     *
     * <p>I(μ) = n / σ². Measures how precisely we can estimate the mean
     * from n observations with variance σ².</p>
     *
     * @param n      number of observations (must be &gt; 0)
     * @param sigma2 variance of the distribution (must be &gt; 0)
     * @return Fisher information for the mean
     */
    static double fisherInfoMean(int n, double sigma2) {
        if (n <= 0) return 0.0;
        if (sigma2 < EPSILON) return Double.MAX_VALUE;
        return (double) n / sigma2;
    }

    /**
     * Fisher information for the variance of a normal distribution.
     *
     * <p>I(σ²) = n / (2σ⁴). Measures how precisely we can estimate the variance.</p>
     *
     * @param n      number of observations (must be &gt; 0)
     * @param sigma2 variance of the distribution (must be &gt; 0)
     * @return Fisher information for the variance
     */
    static double fisherInfoVariance(int n, double sigma2) {
        if (n <= 0) return 0.0;
        if (sigma2 < EPSILON) return Double.MAX_VALUE;
        return (double) n / (2.0 * sigma2 * sigma2);
    }

    /**
     * Cramér-Rao lower bound for the variance of the mean estimator.
     *
     * <p>CRLB = 1/I(μ) = σ²/n. No unbiased estimator of the mean can have
     * variance below this bound.</p>
     *
     * @param n      number of observations (must be &gt; 0)
     * @param sigma2 variance of the distribution
     * @return lower bound on estimator variance
     */
    static double cramerRaoLowerBound(int n, double sigma2) {
        if (n <= 0) return Double.MAX_VALUE;
        return sigma2 / n;
    }

    /**
     * Determines whether uncertainty is reducible by collecting more data.
     *
     * <p>Compares the current variance with the CRLB that would be achieved
     * after collecting {@code additionalSamples} more observations. If the
     * relative reduction exceeds {@code threshold}, the uncertainty is reducible.</p>
     *
     * @param currentSigma2     current variance estimate
     * @param currentN          current number of observations
     * @param additionalSamples hypothetical additional observations
     * @param threshold         minimum relative reduction to consider reducible (0-1)
     * @return true if gathering more data would meaningfully reduce uncertainty
     */
    static boolean isUncertaintyReducible(double currentSigma2, int currentN,
                                           int additionalSamples, double threshold) {
        if (currentN <= 0 || additionalSamples <= 0) return false;
        if (currentSigma2 < EPSILON) return false;

        double currentCrlb = cramerRaoLowerBound(currentN, currentSigma2);
        double futureCrlb = cramerRaoLowerBound(currentN + additionalSamples, currentSigma2);
        double relativeReduction = (currentCrlb - futureCrlb) / currentCrlb;
        return relativeReduction > threshold;
    }

    /**
     * Expected information gain from additional observations.
     *
     * <p>IG = CRLB(n) - CRLB(n+k) = σ²/n - σ²/(n+k) = σ²k / (n(n+k)).</p>
     *
     * <p>This quantifies how much the best-case estimator variance decreases
     * with k additional observations.</p>
     *
     * @param sigma2            variance of the distribution
     * @param currentN          current number of observations
     * @param additionalSamples number of additional observations
     * @return expected variance reduction
     */
    static double expectedInformationGain(double sigma2, int currentN, int additionalSamples) {
        if (currentN <= 0 || additionalSamples <= 0) return 0.0;
        if (sigma2 < EPSILON) return 0.0;
        int futureN = currentN + additionalSamples;
        return sigma2 * additionalSamples / ((double) currentN * futureN);
    }

    /**
     * Fisher-Rao distance between two univariate normal distributions.
     *
     * <p>For the simplified case with equal variance σ²:</p>
     * <p>d = √[(μ₁-μ₂)²/σ² + 2(σ₁-σ₂)²/σ²]</p>
     *
     * <p>where σ is the average of the two standard deviations. This is
     * a Riemannian distance on the statistical manifold — it respects the
     * geometry of probability distributions.</p>
     *
     * @param mu1    mean of first distribution
     * @param sigma1 standard deviation of first distribution
     * @param mu2    mean of second distribution
     * @param sigma2 standard deviation of second distribution
     * @return Fisher-Rao distance (non-negative)
     */
    static double fisherDistance(double mu1, double sigma1, double mu2, double sigma2) {
        if (sigma1 < EPSILON && sigma2 < EPSILON) return Math.abs(mu1 - mu2);
        double avgSigma = (sigma1 + sigma2) / 2.0;
        if (avgSigma < EPSILON) return Math.abs(mu1 - mu2);

        double avgSigma2 = avgSigma * avgSigma;
        double meanTerm = (mu1 - mu2) * (mu1 - mu2) / avgSigma2;
        double varTerm = 2.0 * (sigma1 - sigma2) * (sigma1 - sigma2) / avgSigma2;
        return Math.sqrt(meanTerm + varTerm);
    }

    /**
     * Uncertainty decomposition for a worker profile.
     *
     * @param totalUncertainty      total variance (σ²)
     * @param reducibleUncertainty  variance that can be reduced with more data
     * @param irreducibleFloor      noise variance estimate (aleatoric uncertainty)
     * @param worthExploring        true if reducible uncertainty exceeds threshold
     * @param fisherInfoMean        I(μ) for the current observations
     * @param cramerRaoBound        CRLB for the mean estimator
     */
    public record UncertaintyDecomposition(
            double totalUncertainty,
            double reducibleUncertainty,
            double irreducibleFloor,
            boolean worthExploring,
            double fisherInfoMean,
            double cramerRaoBound
    ) {}

    /**
     * Decomposes uncertainty for a set of observations into reducible and irreducible.
     *
     * <p>Estimates the noise floor from the sample variance of residuals,
     * then separates total uncertainty into:
     * <ul>
     *   <li>Irreducible: estimated noise variance (aleatoric)</li>
     *   <li>Reducible: total variance - irreducible floor</li>
     * </ul></p>
     *
     * @param observations   array of observed reward values
     * @param noiseVariance  estimated irreducible noise variance
     * @return decomposition result
     */
    static UncertaintyDecomposition decompose(double[] observations, double noiseVariance) {
        if (observations == null || observations.length == 0) {
            return new UncertaintyDecomposition(0, 0, noiseVariance, false, 0, Double.MAX_VALUE);
        }

        int n = observations.length;

        // Compute sample variance
        double mean = 0.0;
        for (double v : observations) mean += v;
        mean /= n;

        double totalVariance = 0.0;
        for (double v : observations) {
            double diff = v - mean;
            totalVariance += diff * diff;
        }
        totalVariance = n > 1 ? totalVariance / (n - 1) : 0.0;

        // Decompose
        double irreducible = Math.min(noiseVariance, totalVariance);
        double reducible = Math.max(0.0, totalVariance - irreducible);

        double fisherInfo = fisherInfoMean(n, totalVariance);
        double crlb = cramerRaoLowerBound(n, totalVariance);

        // Worth exploring if reducible uncertainty is at least 20% of total
        boolean worthExploring = totalVariance > EPSILON && reducible / totalVariance > 0.2;

        return new UncertaintyDecomposition(
                totalVariance, reducible, irreducible,
                worthExploring, fisherInfo, crlb
        );
    }

    /**
     * Fisher information report for multiple profiles.
     *
     * @param profileNames             names of analyzed profiles
     * @param decompositions           uncertainty decomposition per profile
     * @param mostInformativeProfile   index of the profile with most reducible uncertainty
     * @param totalReducibleUncertainty aggregate reducible uncertainty across profiles
     */
    public record FisherReport(
            String[] profileNames,
            UncertaintyDecomposition[] decompositions,
            int mostInformativeProfile,
            double totalReducibleUncertainty
    ) {}
}
