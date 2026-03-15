package com.agentframework.orchestrator.benchmark;

import com.agentframework.orchestrator.analytics.bocpd.BocpdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * OLS linear regression on effectiveness snapshot time-series.
 *
 * <p>Computes trend direction (IMPROVING, DEGRADING, STABLE) from the slope
 * of a least-squares fit over recent snapshots. Optionally integrates with
 * {@link BocpdService} for Bayesian changepoint detection.</p>
 *
 * <p>OLS formula (simple linear regression):</p>
 * <pre>
 *   slope     = (n·Σxy − Σx·Σy) / (n·Σx² − (Σx)²)
 *   intercept = (Σy − slope·Σx) / n
 *   r²        = 1 − SS_res / SS_tot
 * </pre>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Ordinary_least_squares">OLS</a>
 */
@Component
@ConditionalOnProperty(prefix = "benchmark", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(BenchmarkConfig.class)
public class TrendAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TrendAnalyzer.class);

    private final BenchmarkConfig config;
    private final BenchmarkRepository repository;
    @Nullable
    private final BocpdService bocpdService;

    public TrendAnalyzer(BenchmarkConfig config,
                         BenchmarkRepository repository,
                         @Nullable BocpdService bocpdService) {
        this.config = config;
        this.repository = repository;
        this.bocpdService = bocpdService;
    }

    /**
     * Analyzes the trend for a given metric using the most recent snapshots.
     *
     * @param metricName the metric to analyze (e.g. REWARD_MEAN)
     * @return trend result, or null if insufficient data
     */
    @Nullable
    public TrendResult analyzeTrend(String metricName) {
        int windowSize = config.trend().windowSize();
        List<EffectivenessSnapshot> snapshots = repository.findLatestByMetric(metricName, windowSize);

        if (snapshots.size() < config.trend().regressionMinPoints()) {
            log.debug("Insufficient data for trend analysis on {}: {} < {}",
                    metricName, snapshots.size(), config.trend().regressionMinPoints());
            return null;
        }

        // Reverse to chronological order (findLatest returns DESC)
        List<EffectivenessSnapshot> chronological = new java.util.ArrayList<>(snapshots);
        Collections.reverse(chronological);

        return computeOls(metricName, chronological);
    }

    /**
     * Analyzes trends for all known metrics.
     */
    public List<TrendResult> analyzeAllTrends() {
        return List.of(
                EffectivenessSnapshot.REWARD_MEAN,
                EffectivenessSnapshot.COMPLETION_RATE,
                EffectivenessSnapshot.DURATION_P95,
                EffectivenessSnapshot.GP_ACCURACY,
                EffectivenessSnapshot.ELO_PROGRESSION
        ).stream()
                .map(this::analyzeTrend)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private TrendResult computeOls(String metricName, List<EffectivenessSnapshot> chronological) {
        int n = chronological.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;

        for (int i = 0; i < n; i++) {
            double x = i;
            double y = chronological.get(i).mean() != null ? chronological.get(i).mean() : 0;

            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
            sumY2 += y * y;
        }

        double denominator = n * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 1e-12) {
            return new TrendResult(metricName, 0, 0, 0, TrendDirection.STABLE, n, false);
        }

        double slope = (n * sumXY - sumX * sumY) / denominator;
        double intercept = (sumY - slope * sumX) / n;

        // R² = 1 - SS_res / SS_tot
        double yMean = sumY / n;
        double ssTot = 0, ssRes = 0;
        for (int i = 0; i < n; i++) {
            double y = chronological.get(i).mean() != null ? chronological.get(i).mean() : 0;
            double yHat = slope * i + intercept;
            ssTot += (y - yMean) * (y - yMean);
            ssRes += (y - yHat) * (y - yHat);
        }
        double rSquared = ssTot > 1e-12 ? 1.0 - ssRes / ssTot : 0;

        // Direction classification
        double threshold = config.trend().slopeThreshold();
        TrendDirection direction;
        if (Math.abs(slope) < threshold) {
            direction = TrendDirection.STABLE;
        } else if (slope > 0) {
            // For GP_ACCURACY and DURATION, higher = worse
            direction = isInverseMetric(metricName) ? TrendDirection.DEGRADING : TrendDirection.IMPROVING;
        } else {
            direction = isInverseMetric(metricName) ? TrendDirection.IMPROVING : TrendDirection.DEGRADING;
        }

        // Optional BOCPD changepoint check
        boolean changepointDetected = false;
        if (bocpdService != null && !chronological.isEmpty()) {
            EffectivenessSnapshot latest = chronological.getLast();
            if (latest.mean() != null) {
                changepointDetected = bocpdService.observeRewardResidual(
                        "benchmark", metricName, latest.mean());
            }
        }

        return new TrendResult(metricName, slope, intercept, rSquared,
                direction, n, changepointDetected);
    }

    /**
     * Metrics where higher values indicate worse performance.
     */
    private boolean isInverseMetric(String metricName) {
        return EffectivenessSnapshot.GP_ACCURACY.equals(metricName)
                || EffectivenessSnapshot.DURATION_P95.equals(metricName);
    }

    // --- Types ---

    public enum TrendDirection {
        IMPROVING, DEGRADING, STABLE
    }

    /**
     * Result of trend analysis for a single metric.
     *
     * @param metricName         the metric analyzed
     * @param slope              OLS regression slope
     * @param intercept          OLS regression intercept
     * @param rSquared           coefficient of determination [0, 1]
     * @param direction          trend classification
     * @param dataPoints         number of snapshots used
     * @param changepointDetected whether BOCPD detected a regime change
     */
    public record TrendResult(
            String metricName,
            double slope,
            double intercept,
            double rSquared,
            TrendDirection direction,
            int dataPoints,
            boolean changepointDetected
    ) {}
}
