package com.agentframework.orchestrator.analytics;

/**
 * Goodhart's Law detector: monitors metric health and proxy-goal alignment.
 *
 * <p>Implements detection for three of the four Goodhart mechanisms
 * identified by Garrabrant et al. (2017):</p>
 * <ul>
 *   <li><b>Regressional</b>: proxy based on too few data points (noise dominates signal)</li>
 *   <li><b>Extremal</b>: outlier values outside the training distribution</li>
 *   <li><b>Causal</b>: proxy-goal correlation breakdown (proxy no longer predicts goal)</li>
 *   <li><b>Adversarial</b>: active gaming (out of scope — flagged as placeholder)</li>
 * </ul>
 *
 * <p>In the agent framework context, the "proxy" is the GP-predicted reward
 * and the "goal" is the actual task quality. Divergence between these signals
 * that the prediction model is being Goodharted.</p>
 *
 * @see <a href="https://arxiv.org/abs/1803.04585">
 *     Garrabrant et al. (2017), Categorizing Variants of Goodhart's Law</a>
 */
public final class GoodhartDetector {

    private GoodhartDetector() {}

    /** Guard threshold for zero standard deviation. */
    private static final double EPSILON = 1e-12;

    /**
     * Type of Goodhart mechanism detected.
     */
    public enum GoodhartType {
        REGRESSIONAL,
        EXTREMAL,
        CAUSAL,
        ADVERSARIAL,
        NONE
    }

    /**
     * Detects regressional Goodhart: metric based on insufficient data.
     *
     * <p>When the sample size is below the minimum, the proxy estimate is
     * dominated by noise rather than signal.</p>
     *
     * @param sampleSize    number of observations
     * @param minSampleSize minimum required for reliable estimates
     * @return true if the sample is too small for reliable metrics
     */
    static boolean detectRegressional(int sampleSize, int minSampleSize) {
        return sampleSize < minSampleSize;
    }

    /**
     * Detects extremal Goodhart: outlier value far from the population.
     *
     * <p>Computes the z-score: z = |value - mean| / std. Values with
     * z &gt; numSigma are flagged as extremal outliers.</p>
     *
     * @param value          observed value to check
     * @param populationMean mean of the population
     * @param populationStd  standard deviation of the population
     * @param numSigma       threshold in standard deviations
     * @return true if the value is an extremal outlier
     */
    static boolean detectExtremal(double value, double populationMean,
                                    double populationStd, double numSigma) {
        if (populationStd < EPSILON) return false;
        double z = Math.abs(value - populationMean) / populationStd;
        return z > numSigma;
    }

    /**
     * Computes Pearson correlation coefficient between proxy and goal values.
     *
     * <p>r = cov(x,y) / (std_x × std_y)</p>
     *
     * <p>Returns 0.0 if either series has zero variance (constant values)
     * or if there are fewer than 2 observations.</p>
     *
     * @param proxy predicted reward values
     * @param goal  actual reward values
     * @return Pearson r in [-1, 1], or 0 if undefined
     */
    static double pearsonCorrelation(double[] proxy, double[] goal) {
        if (proxy == null || goal == null || proxy.length != goal.length || proxy.length < 2) {
            return 0.0;
        }

        int n = proxy.length;
        double sumX = 0, sumY = 0;
        for (int i = 0; i < n; i++) {
            sumX += proxy[i];
            sumY += goal[i];
        }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = proxy[i] - meanX;
            double dy = goal[i] - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }

        double denom = Math.sqrt(varX * varY);
        if (denom < EPSILON) return 0.0;
        return cov / denom;
    }

    /**
     * Checks whether the proxy-goal correlation has dropped below the threshold.
     *
     * <p>Indicates causal Goodhart: the proxy metric is no longer a good
     * predictor of the actual goal.</p>
     *
     * @param proxyValues          predicted reward values
     * @param goalValues           actual reward values
     * @param divergenceThreshold  minimum acceptable correlation
     * @return divergence result with diagnosis
     */
    static DivergenceResult checkDivergence(double[] proxyValues, double[] goalValues,
                                             double divergenceThreshold) {
        double r = pearsonCorrelation(proxyValues, goalValues);

        if (r < divergenceThreshold) {
            return new DivergenceResult(true, GoodhartType.CAUSAL, r, divergenceThreshold,
                    String.format("Proxy-goal correlation %.3f below threshold %.3f: " +
                            "predicted reward no longer predicts actual quality", r, divergenceThreshold));
        }

        return new DivergenceResult(false, GoodhartType.NONE, r, divergenceThreshold,
                String.format("Proxy-goal correlation %.3f is healthy (threshold %.3f)",
                        r, divergenceThreshold));
    }

    /**
     * Computes a composite metric health score (0 = critical, 1 = healthy).
     *
     * <p>Penalizes each detected Goodhart type:</p>
     * <ul>
     *   <li>Regressional: -0.3 (insufficient data for reliable estimates)</li>
     *   <li>Extremal: -0.2 (outlier value, may be noise)</li>
     *   <li>Causal (low correlation): penalty proportional to how far below 1.0</li>
     * </ul>
     *
     * @param regressional        true if regressional Goodhart detected
     * @param extremal            true if extremal Goodhart detected
     * @param proxyGoalCorrelation Pearson correlation between proxy and goal
     * @return health score in [0, 1]
     */
    static double metricHealthScore(boolean regressional, boolean extremal,
                                     double proxyGoalCorrelation) {
        double score = 1.0;

        if (regressional) score -= 0.3;
        if (extremal) score -= 0.2;

        // Correlation penalty: 1.0 → 0 penalty, 0.0 → 0.5 penalty, negative → 0.5
        double corrPenalty = Math.max(0.0, (1.0 - Math.max(0.0, proxyGoalCorrelation)) * 0.5);
        score -= corrPenalty;

        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * Divergence check result.
     *
     * @param divergent   true if proxy-goal relationship has broken down
     * @param primaryType type of Goodhart mechanism detected
     * @param correlation Pearson correlation between proxy and goal
     * @param threshold   divergence threshold used
     * @param description human-readable diagnosis
     */
    public record DivergenceResult(
            boolean divergent,
            GoodhartType primaryType,
            double correlation,
            double threshold,
            String description
    ) {}

    /**
     * Metric health assessment for a single profile.
     *
     * @param profileName          worker profile name
     * @param healthScore          composite health score (0-1)
     * @param regressionalRisk     true if too few data points
     * @param extremalRisk         true if outlier value detected
     * @param proxyGoalCorrelation correlation between predicted and actual reward
     * @param dominantRisk         most severe Goodhart type detected
     */
    public record MetricHealth(
            String profileName,
            double healthScore,
            boolean regressionalRisk,
            boolean extremalRisk,
            double proxyGoalCorrelation,
            GoodhartType dominantRisk
    ) {}

    /**
     * Goodhart audit report for multiple profiles.
     *
     * @param profileHealths          per-profile health assessments
     * @param systemHealthScore       weighted average health across profiles
     * @param profilesAtRisk          count of profiles with healthScore &lt; 0.5
     * @param recommendations         suggested remediation actions
     */
    public record GoodhartReport(
            MetricHealth[] profileHealths,
            double systemHealthScore,
            int profilesAtRisk,
            String[] recommendations
    ) {}
}
