package com.agentframework.orchestrator.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Calibration audit for prediction quality monitoring.
 *
 * <p>Metrics:</p>
 * <ul>
 *   <li><strong>ECE</strong> (Expected Calibration Error): {@code Σ (nₖ/N) × |accₖ - confₖ|}</li>
 *   <li><strong>Brier Score</strong>: {@code (1/N) × Σ (fᵢ - oᵢ)²}</li>
 *   <li><strong>MCE</strong> (Maximum Calibration Error): {@code max|accₖ - confₖ|}</li>
 *   <li><strong>Dutch Book</strong>: MCE &gt; threshold indicates an adversary can construct
 *       a sure-loss bet against the predictor (de Finetti's theorem).</li>
 * </ul>
 *
 * @see <a href="https://doi.org/10.1111/j.2517-6161.1983.tb01232.x">
 *     DeGroot &amp; Fienberg (1983), The Comparison and Evaluation of Forecasters,
 *     J. Royal Statistical Society Series D</a>
 * @see <a href="https://doi.org/10.1175/1520-0493(1950)078%3C0001:VOFEIT%3E2.0.CO;2">
 *     Brier (1950), Verification of Forecasts Expressed in Terms of Probability,
 *     Monthly Weather Review</a>
 */
public final class CalibrationAudit {

    /** Default number of equal-width calibration bins. */
    static final int DEFAULT_NUM_BINS = 10;

    /** Default threshold for Dutch Book vulnerability. */
    static final double DEFAULT_DUTCH_BOOK_THRESHOLD = 0.15;

    private CalibrationAudit() {}

    /**
     * Expected Calibration Error (ECE).
     *
     * <p>Bins predictions into equal-width intervals, then computes the weighted
     * average of |accuracy - confidence| across bins. Empty bins are skipped.</p>
     *
     * @param predicted predicted probabilities
     * @param actual    actual outcomes (true = success)
     * @param numBins   number of equal-width bins
     * @return ECE in [0, 1]
     */
    static double expectedCalibrationError(double[] predicted, boolean[] actual, int numBins) {
        List<CalibrationBin> bins = computeBins(predicted, actual, numBins);
        int n = predicted.length;
        if (n == 0) return 0.0;

        double ece = 0.0;
        for (CalibrationBin bin : bins) {
            if (bin.count() > 0) {
                ece += ((double) bin.count() / n) * Math.abs(bin.avgAccuracy() - bin.avgConfidence());
            }
        }
        return ece;
    }

    /**
     * Brier Score: mean squared error of probabilistic predictions.
     *
     * <p>{@code (1/N) × Σ (fᵢ - oᵢ)²} where fᵢ is the predicted probability
     * and oᵢ is the actual outcome (0 or 1).</p>
     *
     * @param predicted predicted probabilities
     * @param actual    actual outcomes
     * @return Brier score in [0, 1] (lower is better)
     */
    static double brierScore(double[] predicted, boolean[] actual) {
        if (predicted.length != actual.length) {
            throw new IllegalArgumentException("predicted and actual must have same length");
        }
        if (predicted.length == 0) return 0.0;

        double sum = 0.0;
        for (int i = 0; i < predicted.length; i++) {
            double outcome = actual[i] ? 1.0 : 0.0;
            double diff = predicted[i] - outcome;
            sum += diff * diff;
        }
        return sum / predicted.length;
    }

    /**
     * Maximum Calibration Error (MCE): worst-case bin miscalibration.
     *
     * @param predicted predicted probabilities
     * @param actual    actual outcomes
     * @param numBins   number of bins
     * @return MCE in [0, 1]
     */
    static double maxCalibrationError(double[] predicted, boolean[] actual, int numBins) {
        List<CalibrationBin> bins = computeBins(predicted, actual, numBins);
        double mce = 0.0;
        for (CalibrationBin bin : bins) {
            if (bin.count() > 0) {
                mce = Math.max(mce, Math.abs(bin.avgAccuracy() - bin.avgConfidence()));
            }
        }
        return mce;
    }

    /**
     * Dutch Book vulnerability check.
     *
     * <p>If the maximum calibration error exceeds the threshold, an adversary
     * can exploit the miscalibration to construct a guaranteed profit (Dutch Book).
     * Per de Finetti's theorem, well-calibrated predictions are immune to Dutch Books.</p>
     *
     * @param predicted predicted probabilities
     * @param actual    actual outcomes
     * @param numBins   number of bins
     * @param threshold MCE threshold for vulnerability
     * @return true if the predictor is Dutch Book vulnerable
     */
    static boolean isDutchBookVulnerable(double[] predicted, boolean[] actual,
                                         int numBins, double threshold) {
        return maxCalibrationError(predicted, actual, numBins) > threshold;
    }

    /**
     * Complete calibration audit.
     *
     * @param predicted           predicted probabilities
     * @param actual              actual outcomes
     * @param numBins             number of calibration bins
     * @param dutchBookThreshold  MCE threshold for Dutch Book vulnerability
     * @return full calibration report
     */
    static CalibrationReport audit(double[] predicted, boolean[] actual,
                                   int numBins, double dutchBookThreshold) {
        if (predicted.length != actual.length) {
            throw new IllegalArgumentException("predicted and actual must have same length");
        }

        List<CalibrationBin> bins = computeBins(predicted, actual, numBins);
        double ece = 0.0;
        double mce = 0.0;
        int n = predicted.length;

        if (n > 0) {
            for (CalibrationBin bin : bins) {
                if (bin.count() > 0) {
                    double gap = Math.abs(bin.avgAccuracy() - bin.avgConfidence());
                    ece += ((double) bin.count() / n) * gap;
                    mce = Math.max(mce, gap);
                }
            }
        }

        double brier = brierScore(predicted, actual);
        boolean dutchBook = mce > dutchBookThreshold;

        return new CalibrationReport(ece, brier, mce, dutchBook, bins, n);
    }

    /**
     * Computes calibration bins with equal-width intervals over [0, 1].
     */
    private static List<CalibrationBin> computeBins(double[] predicted, boolean[] actual, int numBins) {
        double binWidth = 1.0 / numBins;
        int[] counts = new int[numBins];
        double[] confSums = new double[numBins];
        double[] accSums = new double[numBins];

        for (int i = 0; i < predicted.length; i++) {
            int bin = Math.min((int) (predicted[i] / binWidth), numBins - 1);
            bin = Math.max(bin, 0);
            counts[bin]++;
            confSums[bin] += predicted[i];
            accSums[bin] += actual[i] ? 1.0 : 0.0;
        }

        List<CalibrationBin> bins = new ArrayList<>();
        for (int i = 0; i < numBins; i++) {
            double avgConf = counts[i] > 0 ? confSums[i] / counts[i] : 0.0;
            double avgAcc = counts[i] > 0 ? accSums[i] / counts[i] : 0.0;
            bins.add(new CalibrationBin(i, avgConf, avgAcc, counts[i]));
        }
        return bins;
    }

    /**
     * Per-bin calibration result.
     *
     * @param binIndex      bin index (0 to numBins-1)
     * @param avgConfidence average predicted probability in this bin
     * @param avgAccuracy   fraction of actual positives in this bin
     * @param count         number of predictions in this bin
     */
    public record CalibrationBin(int binIndex, double avgConfidence, double avgAccuracy, int count) {}

    /**
     * Complete calibration audit report.
     *
     * @param ece                    Expected Calibration Error
     * @param brierScore             Brier Score (mean squared error)
     * @param maxCalibrationError    Maximum Calibration Error
     * @param dutchBookVulnerable    true if MCE exceeds threshold
     * @param bins                   per-bin calibration details
     * @param totalPredictions       total number of predictions audited
     */
    public record CalibrationReport(
            double ece,
            double brierScore,
            double maxCalibrationError,
            boolean dutchBookVulnerable,
            List<CalibrationBin> bins,
            int totalPredictions
    ) {}
}
