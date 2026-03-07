package com.agentframework.orchestrator.gp;

import java.util.Arrays;

/**
 * Bayesian logistic regression predictor for task success probability.
 *
 * <p>Feature vector (1029 dimensions):
 * <ul>
 *   <li>[0..1023]: task embedding (mxbai-embed-large, 1024 dim)</li>
 *   <li>[1024]: GP predicted mean μ (default 0.5)</li>
 *   <li>[1025]: GP predicted variance σ² (default 1.0)</li>
 *   <li>[1026]: Elo at dispatch, normalized: (elo - 1600) / 400 (default 0.0)</li>
 *   <li>[1027]: context quality score (default 0.5)</li>
 *   <li>[1028]: log(budget_remaining + 1) (default 0.0)</li>
 * </ul>
 *
 * <p>Training uses SGD logistic regression with learning rate decay.
 * Platt scaling calibrates raw outputs to true probabilities.</p>
 *
 * @see SuccessPrediction
 * @see <a href="https://www.stat.columbia.edu/~gelman/book/">
 *     Gelman et al. (2013), Bayesian Data Analysis, 3rd ed.</a>
 */
public class BayesianSuccessPredictor {

    /** Dimension of the feature vector. */
    public static final int FEATURE_DIM = 1029;

    /** Minimum training examples required for non-prior prediction. */
    public static final int MIN_TRAINING_SIZE = 30;

    /** Reward above this threshold is classified as "success" for training. */
    public static final double SUCCESS_REWARD_THRESHOLD = 0.5;

    private static final double DEFAULT_ETA = 0.01;
    private static final double ETA_DECAY = 0.001;
    private static final int SGD_EPOCHS = 5;

    private final double[] weights;
    private final double bias;
    private final double calibrationA;
    private final double calibrationB;

    public BayesianSuccessPredictor(double[] weights, double bias,
                                     double calibrationA, double calibrationB) {
        this.weights = weights;
        this.bias = bias;
        this.calibrationA = calibrationA;
        this.calibrationB = calibrationB;
    }

    /**
     * Prior predictor (no training data). Always returns P = 0.5.
     * All weights are zero, bias is 0, Platt scaling is identity (a=1, b=0).
     */
    public static BayesianSuccessPredictor prior() {
        return new BayesianSuccessPredictor(new double[FEATURE_DIM], 0, 1.0, 0);
    }

    /**
     * Predicts success probability from a 1029-dim feature vector.
     *
     * @param features 1029-dim feature vector
     * @return prediction with calibrated probability and dispatch recommendation
     */
    public SuccessPrediction predict(double[] features) {
        if (features.length != FEATURE_DIM) {
            throw new IllegalArgumentException("Expected " + FEATURE_DIM + " features, got " + features.length);
        }

        double z = bias;
        for (int i = 0; i < FEATURE_DIM; i++) {
            z += weights[i] * features[i];
        }

        double rawP = sigmoid(z);
        double calibratedP = calibrate(z);

        return new SuccessPrediction(
            calibratedP,
            rawP,
            calibratedP >= SuccessPrediction.DISPATCH_THRESHOLD,
            features
        );
    }

    /**
     * Builds a 1029-dim feature vector from available signals.
     * Null/missing signals use sensible defaults.
     */
    public static double[] buildFeatureVector(float[] taskEmbedding,
                                                Double gpMu, Double gpSigma2,
                                                Double eloAtDispatch,
                                                Double contextQuality,
                                                Long budgetRemaining) {
        double[] features = new double[FEATURE_DIM];

        // [0..1023]: task embedding
        if (taskEmbedding != null) {
            int len = Math.min(taskEmbedding.length, 1024);
            for (int i = 0; i < len; i++) {
                features[i] = taskEmbedding[i];
            }
        }
        // remaining are 0.0 if embedding is shorter or null

        // [1024]: gp_mu
        features[1024] = gpMu != null ? gpMu : 0.5;
        // [1025]: gp_sigma2
        features[1025] = gpSigma2 != null ? gpSigma2 : 1.0;
        // [1026]: elo_at_dispatch normalized
        features[1026] = eloAtDispatch != null ? (eloAtDispatch - 1600.0) / 400.0 : 0.0;
        // [1027]: context_quality
        features[1027] = contextQuality != null ? contextQuality : 0.5;
        // [1028]: log(budget_remaining + 1)
        features[1028] = budgetRemaining != null ? Math.log(budgetRemaining + 1) : 0.0;

        return features;
    }

    /**
     * Trains a predictor from historical data via SGD logistic regression.
     *
     * <p>If fewer than {@link #MIN_TRAINING_SIZE} samples are provided,
     * returns {@link #prior()} (uninformative predictor).</p>
     *
     * @param features  training features, shape [N][1029]
     * @param outcomes  binary outcomes (true = success, false = failure)
     * @return trained predictor with Platt-calibrated outputs
     */
    public static BayesianSuccessPredictor train(double[][] features, boolean[] outcomes) {
        if (features.length < MIN_TRAINING_SIZE) {
            return prior();
        }

        int n = features.length;

        // Split: 80% train, 20% calibration
        int trainSize = (int) (n * 0.8);
        int calSize = n - trainSize;

        // SGD logistic regression on training set
        double[] w = new double[FEATURE_DIM];
        double b = 0;

        int t = 0;
        for (int epoch = 0; epoch < SGD_EPOCHS; epoch++) {
            for (int i = 0; i < trainSize; i++) {
                double eta = DEFAULT_ETA / (1 + ETA_DECAY * t);
                t++;

                double z = b;
                for (int j = 0; j < FEATURE_DIM; j++) {
                    z += w[j] * features[i][j];
                }
                double p = sigmoid(z);
                double y = outcomes[i] ? 1.0 : 0.0;
                double error = p - y;

                b -= eta * error;
                for (int j = 0; j < FEATURE_DIM; j++) {
                    w[j] -= eta * error * features[i][j];
                }
            }
        }

        // Platt scaling on calibration set
        double a = 1.0;
        double bPlatt = 0.0;

        if (calSize >= 5) {
            // Simple least-squares fit: find a, bPlatt such that
            // sigmoid(a * z + bPlatt) ≈ y on the calibration set
            // Note: we fit on the logit z (not rawP) to avoid double-sigmoid
            double sumZ = 0, sumY = 0, sumZY = 0, sumZ2 = 0;
            for (int i = trainSize; i < n; i++) {
                double z = b;
                for (int j = 0; j < FEATURE_DIM; j++) {
                    z += w[j] * features[i][j];
                }
                double y = outcomes[i] ? 1.0 : 0.0;

                sumZ += z;
                sumY += y;
                sumZY += z * y;
                sumZ2 += z * z;
            }
            double denom = calSize * sumZ2 - sumZ * sumZ;
            if (Math.abs(denom) > 1e-12) {
                a = (calSize * sumZY - sumZ * sumY) / denom;
                bPlatt = (sumY - a * sumZ) / calSize;
            }
        }

        return new BayesianSuccessPredictor(w, b, a, bPlatt);
    }

    /** Standard sigmoid: σ(z) = 1 / (1 + e^(-z)). */
    static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** Platt calibration: σ(a × z + b), where z is the raw logit. */
    double calibrate(double z) {
        return sigmoid(calibrationA * z + calibrationB);
    }
}
