package com.agentframework.orchestrator.gp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link BayesianSuccessPredictor} — logistic regression for dispatch admission.
 *
 * @see <a href="https://www.stat.columbia.edu/~gelman/book/">
 *     Gelman et al. (2013), Bayesian Data Analysis, 3rd ed.</a>
 */
class BayesianSuccessPredictorTest {

    @Test
    void prior_returnsHalf() {
        BayesianSuccessPredictor predictor = BayesianSuccessPredictor.prior();
        double[] features = new double[BayesianSuccessPredictor.FEATURE_DIM];

        SuccessPrediction prediction = predictor.predict(features);

        assertThat(prediction.probability()).isCloseTo(0.5, within(0.01));
        assertThat(prediction.shouldDispatch()).isTrue(); // 0.5 >= 0.3
    }

    @Test
    void sigmoid_standardValues() {
        assertThat(BayesianSuccessPredictor.sigmoid(0)).isCloseTo(0.5, within(1e-9));
        assertThat(BayesianSuccessPredictor.sigmoid(10)).isCloseTo(1.0, within(0.001));
        assertThat(BayesianSuccessPredictor.sigmoid(-10)).isCloseTo(0.0, within(0.001));
    }

    @Test
    void buildFeatureVector_allPresent_correct1029Dim() {
        float[] embedding = new float[1024];
        for (int i = 0; i < 1024; i++) embedding[i] = 0.01f * i;

        double[] features = BayesianSuccessPredictor.buildFeatureVector(
                embedding, 0.7, 0.3, 1800.0, 0.85, 5000L);

        assertThat(features).hasSize(BayesianSuccessPredictor.FEATURE_DIM);
        // Check scalar positions
        assertThat(features[1024]).isCloseTo(0.7, within(1e-9));   // gp_mu
        assertThat(features[1025]).isCloseTo(0.3, within(1e-9));   // gp_sigma2
        assertThat(features[1026]).isCloseTo(0.5, within(1e-9));   // (1800-1600)/400 = 0.5
        assertThat(features[1027]).isCloseTo(0.85, within(1e-9));  // context_quality
        assertThat(features[1028]).isCloseTo(Math.log(5001), within(1e-9)); // log(5000+1)
        // Check embedding copied correctly
        assertThat(features[0]).isCloseTo(0.0, within(1e-6));
        assertThat(features[100]).isCloseTo(1.0f, within(0.01));
    }

    @Test
    void buildFeatureVector_nullScalars_usesDefaults() {
        double[] features = BayesianSuccessPredictor.buildFeatureVector(
                null, null, null, null, null, null);

        assertThat(features).hasSize(BayesianSuccessPredictor.FEATURE_DIM);
        assertThat(features[1024]).isCloseTo(0.5, within(1e-9));   // default gp_mu
        assertThat(features[1025]).isCloseTo(1.0, within(1e-9));   // default gp_sigma2
        assertThat(features[1026]).isCloseTo(0.0, within(1e-9));   // default elo
        assertThat(features[1027]).isCloseTo(0.5, within(1e-9));   // default context_quality
        assertThat(features[1028]).isCloseTo(0.0, within(1e-9));   // default budget
    }

    @Test
    void predict_highConfidence_highProbability() {
        // Create a predictor with positive weights on the scalar features
        double[] weights = new double[BayesianSuccessPredictor.FEATURE_DIM];
        weights[1024] = 5.0;  // high weight on gp_mu
        BayesianSuccessPredictor predictor = new BayesianSuccessPredictor(weights, 0, 1.0, 0);

        double[] features = new double[BayesianSuccessPredictor.FEATURE_DIM];
        features[1024] = 1.0;  // strong gp_mu signal

        SuccessPrediction prediction = predictor.predict(features);

        // sigmoid(5.0 * 1.0) ≈ 0.993
        assertThat(prediction.rawProbability()).isGreaterThan(0.9);
        assertThat(prediction.shouldDispatch()).isTrue();
    }

    @Test
    void predict_belowDispatchThreshold_shouldDispatchFalse() {
        // Create a predictor that produces very low probability
        double[] weights = new double[BayesianSuccessPredictor.FEATURE_DIM];
        weights[1024] = -10.0;  // strong negative weight on gp_mu
        BayesianSuccessPredictor predictor = new BayesianSuccessPredictor(weights, 0, 1.0, 0);

        double[] features = new double[BayesianSuccessPredictor.FEATURE_DIM];
        features[1024] = 1.0;

        SuccessPrediction prediction = predictor.predict(features);

        // sigmoid(-10) ≈ 0.00005
        assertThat(prediction.probability()).isLessThan(SuccessPrediction.DISPATCH_THRESHOLD);
        assertThat(prediction.shouldDispatch()).isFalse();
    }
}
