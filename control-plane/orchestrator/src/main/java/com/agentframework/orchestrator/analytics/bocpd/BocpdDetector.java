package com.agentframework.orchestrator.analytics.bocpd;

import java.time.Instant;
import java.util.Optional;

/**
 * Bayesian Online Changepoint Detection for a single time series.
 *
 * <p>Implements Adams &amp; MacKay (2007) with a Normal-Gamma conjugate prior
 * for the underlying data model (Gaussian with unknown mean and variance)
 * and a geometric hazard function H(r) = 1/λ.</p>
 *
 * <h3>Algorithm</h3>
 * <p>At each observation x_t, the run-length posterior P(r_t | x_{1:t}) is updated:</p>
 * <ol>
 *   <li>Compute predictive probability π(x_t | r_t) via Student-t distribution
 *       with parameters derived from Normal-Gamma sufficient statistics</li>
 *   <li>Compute growth probabilities: P(r_t = r_{t-1}+1) ∝ P(r_{t-1}) × π(x_t | r_t) × (1 - H)</li>
 *   <li>Compute changepoint probability: P(r_t = 0) ∝ Σ P(r_{t-1}) × π(x_t | r_t) × H</li>
 *   <li>Normalize the posterior</li>
 * </ol>
 *
 * <p>The Normal-Gamma sufficient statistics (mu0, kappa, alpha, beta) are updated
 * incrementally for each run length, enabling exact Bayesian inference without MCMC.</p>
 *
 * <p>Memory is bounded by {@code maxRunLength}: older run-length hypotheses are
 * truncated, with their mass redistributed to the changepoint probability.</p>
 *
 * @see BocpdConfig
 * @see <a href="https://arxiv.org/abs/0710.3742">
 *     Adams &amp; MacKay (2007) — Bayesian Online Changepoint Detection</a>
 */
public class BocpdDetector {

    /** Observation with timestamp for changepoint reporting. */
    public record Observation(double value, Instant timestamp) {}

    /** Detected changepoint event. */
    public record Changepoint(
            Instant when,
            double probability,
            int runLengthBefore,
            String sliName
    ) {}

    private final String sliName;
    private final double hazardRate;    // H = 1/lambda
    private final double threshold;
    private final int maxRunLength;

    // Run-length posterior: posterior[r] = P(r_t = r | x_{1:t})
    private double[] posterior;
    private int currentLength;

    // Normal-Gamma sufficient statistics per run length
    // Each index r holds the stats for the hypothesis "run length = r"
    private double[] mu0;       // prior mean
    private double[] kappa;     // prior precision scaling
    private double[] alpha;     // prior shape (Gamma)
    private double[] beta;      // prior rate (Gamma)

    // Prior hyperparameters (initial values for r=0)
    private static final double PRIOR_MU0 = 0.0;
    private static final double PRIOR_KAPPA = 1.0;
    private static final double PRIOR_ALPHA = 1.0;
    private static final double PRIOR_BETA = 1.0;

    private int observationCount;
    private Instant lastTimestamp;

    public BocpdDetector(String sliName, BocpdConfig config) {
        this.sliName = sliName;
        this.hazardRate = 1.0 / config.hazardLambda();
        this.threshold = config.threshold();
        this.maxRunLength = config.maxRunLength();

        // Initialize with a single run-length hypothesis r=0 with probability 1
        this.posterior = new double[maxRunLength + 1];
        this.posterior[0] = 1.0;
        this.currentLength = 1;

        this.mu0 = new double[maxRunLength + 1];
        this.kappa = new double[maxRunLength + 1];
        this.alpha = new double[maxRunLength + 1];
        this.beta = new double[maxRunLength + 1];

        resetSufficientStats(0);
        this.observationCount = 0;
    }

    /**
     * Processes a new observation and updates the run-length posterior.
     *
     * @param obs the new observation (value + timestamp)
     * @return detected changepoint if P(r_t=0) exceeds threshold, empty otherwise
     */
    public Optional<Changepoint> observe(Observation obs) {
        double x = obs.value();
        this.lastTimestamp = obs.timestamp();
        this.observationCount++;

        // Need at least 2 observations before detecting changepoints
        if (observationCount < 2) {
            updateSufficientStats(x);
            return Optional.empty();
        }

        int newLength = Math.min(currentLength + 1, maxRunLength + 1);
        double[] newPosterior = new double[maxRunLength + 1];

        // Compute predictive probabilities and growth probabilities
        double changepointMass = 0.0;
        for (int r = 0; r < currentLength; r++) {
            double predictive = studentTPredictive(x, r);

            // Growth: P(r_t = r+1) += P(r_{t-1} = r) * π(x_t|r) * (1 - H)
            if (r + 1 <= maxRunLength) {
                newPosterior[r + 1] += posterior[r] * predictive * (1.0 - hazardRate);
            }

            // Changepoint: P(r_t = 0) += P(r_{t-1} = r) * π(x_t|r) * H
            changepointMass += posterior[r] * predictive * hazardRate;
        }
        newPosterior[0] = changepointMass;

        // Normalize
        double total = 0.0;
        for (int r = 0; r < newLength; r++) {
            total += newPosterior[r];
        }
        if (total > 0) {
            for (int r = 0; r < newLength; r++) {
                newPosterior[r] /= total;
            }
        }

        // Update sufficient statistics:
        // Shift stats for growth (r+1 inherits from r after update with x)
        double[] newMu0 = new double[maxRunLength + 1];
        double[] newKappa = new double[maxRunLength + 1];
        double[] newAlpha = new double[maxRunLength + 1];
        double[] newBeta = new double[maxRunLength + 1];

        // r=0 gets fresh prior (changepoint → reset)
        newMu0[0] = PRIOR_MU0;
        newKappa[0] = PRIOR_KAPPA;
        newAlpha[0] = PRIOR_ALPHA;
        newBeta[0] = PRIOR_BETA;

        // r>0: update old stats with new observation
        for (int r = 0; r < currentLength && r < maxRunLength; r++) {
            double k = kappa[r];
            double m = mu0[r];
            double a = alpha[r];
            double b = beta[r];

            newKappa[r + 1] = k + 1;
            newMu0[r + 1] = (k * m + x) / (k + 1);
            newAlpha[r + 1] = a + 0.5;
            newBeta[r + 1] = b + (k * (x - m) * (x - m)) / (2.0 * (k + 1));
        }

        this.posterior = newPosterior;
        this.mu0 = newMu0;
        this.kappa = newKappa;
        this.alpha = newAlpha;
        this.beta = newBeta;
        this.currentLength = newLength;

        // Check for changepoint
        if (newPosterior[0] > threshold) {
            int prevRunLength = findMostLikelyRunLength(posterior);
            return Optional.of(new Changepoint(
                    obs.timestamp(), newPosterior[0], prevRunLength, sliName));
        }

        return Optional.empty();
    }

    /**
     * Student-t predictive probability density for observation x given run length r.
     *
     * <p>The Normal-Gamma conjugate produces a Student-t predictive:</p>
     * <pre>
     *   ν = 2α, μ = μ₀, σ² = β(κ+1)/(ακ)
     *   π(x|r) = Student-t(x; ν, μ, σ²)
     * </pre>
     */
    double studentTPredictive(double x, int r) {
        double m = mu0[r];
        double k = kappa[r];
        double a = alpha[r];
        double b = beta[r];

        double nu = 2.0 * a;                    // degrees of freedom
        double variance = b * (k + 1.0) / (a * k);  // scale² of Student-t

        if (variance <= 0 || nu <= 0) {
            return 1e-10; // degenerate case
        }

        double stddev = Math.sqrt(variance);
        double t = (x - m) / stddev;

        // Student-t PDF: Γ((ν+1)/2) / (√(νπ) Γ(ν/2)) × (1 + t²/ν)^(-(ν+1)/2) / σ
        double logPdf = logGamma((nu + 1) / 2.0) - logGamma(nu / 2.0)
                - 0.5 * Math.log(nu * Math.PI)
                - Math.log(stddev)
                - ((nu + 1) / 2.0) * Math.log(1.0 + (t * t) / nu);

        return Math.exp(logPdf);
    }

    /**
     * Returns the current changepoint probability P(r_t = 0).
     */
    public double changepointProbability() {
        return currentLength > 0 ? posterior[0] : 0.0;
    }

    /**
     * Returns the most likely current run length.
     */
    public int mostLikelyRunLength() {
        return findMostLikelyRunLength(posterior);
    }

    /** Returns the total number of observations processed. */
    public int observationCount() {
        return observationCount;
    }

    /** Returns the SLI name this detector is monitoring. */
    public String sliName() {
        return sliName;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void resetSufficientStats(int r) {
        mu0[r] = PRIOR_MU0;
        kappa[r] = PRIOR_KAPPA;
        alpha[r] = PRIOR_ALPHA;
        beta[r] = PRIOR_BETA;
    }

    private void updateSufficientStats(double x) {
        // Update stats for the initial observation (before full BOCPD kicks in)
        for (int r = 0; r < currentLength; r++) {
            double k = kappa[r];
            double m = mu0[r];
            beta[r] = beta[r] + (k * (x - m) * (x - m)) / (2.0 * (k + 1));
            mu0[r] = (k * m + x) / (k + 1);
            kappa[r] = k + 1;
            alpha[r] = alpha[r] + 0.5;
        }
    }

    private int findMostLikelyRunLength(double[] dist) {
        int best = 0;
        double bestProb = dist[0];
        for (int r = 1; r < currentLength; r++) {
            if (dist[r] > bestProb) {
                bestProb = dist[r];
                best = r;
            }
        }
        return best;
    }

    /**
     * Stirling's approximation of log-Gamma for positive arguments.
     *
     * <p>Uses the Lanczos approximation for |z| ≤ 10 region and
     * Stirling for large arguments. Sufficient precision for
     * Student-t PDF computation (error &lt; 1e-8 for z &gt; 0.5).</p>
     */
    static double logGamma(double z) {
        if (z <= 0) return 0;

        // Lanczos approximation (g=7, N=9)
        double[] coefficients = {
                0.99999999999980993,
                676.5203681218851,
                -1259.1392167224028,
                771.32342877765313,
                -176.61502916214059,
                12.507343278686905,
                -0.13857109526572012,
                9.9843695780195716e-6,
                1.5056327351493116e-7
        };

        if (z < 0.5) {
            // Reflection formula: Γ(z) = π / (sin(πz) Γ(1-z))
            return Math.log(Math.PI / Math.sin(Math.PI * z)) - logGamma(1 - z);
        }

        z -= 1;
        double x = coefficients[0];
        for (int i = 1; i < coefficients.length; i++) {
            x += coefficients[i] / (z + i);
        }
        double t = z + 7.5; // g + 0.5
        return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(x);
    }
}
