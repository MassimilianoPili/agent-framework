package com.agentframework.common.privacy;

import java.security.SecureRandom;

/**
 * Laplace mechanism for (ε)-differential privacy (#43).
 *
 * <p>Adds noise drawn from Laplace(0, sensitivity/ε) to the true value.
 * The Laplace distribution has PDF {@code f(x) = (1/2b)·e^(-|x|/b)} where
 * {@code b = sensitivity/ε}.</p>
 *
 * <p>Sampling uses the inverse CDF method:
 * {@code X = -b · sign(U) · ln(1 - 2|U|)} where U ~ Uniform(-0.5, 0.5).
 * This is exact (no rejection) and uses {@link SecureRandom} for
 * cryptographic-quality randomness.</p>
 *
 * <p>Thread-safe: {@link SecureRandom} is safe for concurrent use.</p>
 */
public class LaplaceMechanism implements DifferentialPrivacyMechanism {

    private final SecureRandom random;

    public LaplaceMechanism() {
        this.random = new SecureRandom();
    }

    /** Constructor with injectable random source (for testing). */
    LaplaceMechanism(SecureRandom random) {
        this.random = random;
    }

    @Override
    public double privatize(double trueValue, double sensitivity, double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("epsilon must be > 0, got: " + epsilon);
        }
        if (sensitivity < 0) {
            throw new IllegalArgumentException("sensitivity must be >= 0, got: " + sensitivity);
        }
        if (sensitivity == 0) {
            return trueValue; // no noise needed — output is independent of any single record
        }

        double scale = sensitivity / epsilon;
        double u = random.nextDouble() - 0.5;
        // Inverse CDF of Laplace distribution
        double noise = -scale * Math.signum(u) * Math.log(1.0 - 2.0 * Math.abs(u));
        return trueValue + noise;
    }

    @Override
    public double remainingBudget(double initialEpsilon, int queriesUsed) {
        if (initialEpsilon <= 0) {
            throw new IllegalArgumentException("initialEpsilon must be > 0");
        }
        if (queriesUsed < 0) {
            throw new IllegalArgumentException("queriesUsed must be >= 0");
        }
        // Basic sequential composition: total cost = K × ε
        // Budget = dailyBudget - totalCost
        // Since we don't know dailyBudget here, return the fraction of "1 query = 1 unit"
        // The caller (PrivacyBudget) manages the actual accounting.
        double consumed = queriesUsed * initialEpsilon;
        return Math.max(0.0, 1.0 - consumed); // normalised: 1.0 = full budget
    }
}
