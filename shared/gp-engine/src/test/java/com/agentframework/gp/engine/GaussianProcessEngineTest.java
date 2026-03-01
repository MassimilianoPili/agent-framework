package com.agentframework.gp.engine;

import com.agentframework.gp.math.RbfKernel;
import com.agentframework.gp.model.GpPosterior;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.gp.model.TrainingPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GaussianProcessEngineTest {

    private GaussianProcessEngine engine;

    @BeforeEach
    void setUp() {
        // Signal variance 1.0, length scale 1.0, noise variance 0.01
        engine = new GaussianProcessEngine(new RbfKernel(1.0, 1.0), 0.01);
    }

    @Test
    void fit_emptyData_returnsNull() {
        assertThat(engine.fit(List.of())).isNull();
        assertThat(engine.fit(null)).isNull();
    }

    @Test
    void predict_singleTrainingPoint_muEqualsRewardAtSamePoint() {
        // Train at x=[0,0] with reward 0.8
        var training = List.of(new TrainingPoint(new float[]{0.0f, 0.0f}, 0.8, "be-java"));
        GpPosterior posterior = engine.fit(training);
        assertThat(posterior).isNotNull();

        // Predict at the same point — should interpolate (mu ≈ 0.8)
        GpPrediction pred = engine.predict(posterior, new float[]{0.0f, 0.0f});
        assertThat(pred.mu()).isCloseTo(0.8, within(0.05));
    }

    @Test
    void predict_singleTrainingPoint_sigma2NearZeroAtSamePoint() {
        var training = List.of(new TrainingPoint(new float[]{0.0f, 0.0f}, 0.8, "be-java"));
        GpPosterior posterior = engine.fit(training);

        GpPrediction pred = engine.predict(posterior, new float[]{0.0f, 0.0f});
        // Variance at the training point should be very small (≈ noise variance)
        assertThat(pred.sigma2()).isLessThan(0.05);
    }

    @Test
    void predict_farFromTraining_sigma2ApproachesSelfKernel() {
        var training = List.of(new TrainingPoint(new float[]{0.0f, 0.0f}, 0.8, "be-java"));
        GpPosterior posterior = engine.fit(training);

        // Predict at a point very far from training — max uncertainty
        float[] farAway = new float[]{100.0f, 100.0f};
        GpPrediction pred = engine.predict(posterior, farAway);
        // sigma2 should approach selfKernel (= 1.0 for sv=1.0)
        assertThat(pred.sigma2()).isGreaterThan(0.9);
    }

    @Test
    void predict_twoPoints_muInterpolates() {
        // Two training points: x=[0] → reward 0.2, x=[1] → reward 0.9
        var training = List.of(
                new TrainingPoint(new float[]{0.0f}, 0.2, "be-java"),
                new TrainingPoint(new float[]{1.0f}, 0.9, "be-java")
        );
        GpPosterior posterior = engine.fit(training);

        // At x=[0.5], mu should be between 0.2 and 0.9
        GpPrediction mid = engine.predict(posterior, new float[]{0.5f});
        assertThat(mid.mu()).isBetween(0.2, 0.9);

        // And closer to the mean of 0.55 due to the RBF smoothness
        assertThat(mid.mu()).isCloseTo(0.55, within(0.2));
    }

    @Test
    void prior_returnsConfiguredMeanAndMaxUncertainty() {
        GpPrediction prior = engine.prior(0.5);
        assertThat(prior.mu()).isEqualTo(0.5);
        assertThat(prior.sigma2()).isEqualTo(1.0); // selfKernel = signalVariance = 1.0
    }

    @Test
    void ucb_combinesMuAndSigma() {
        var pred = new GpPrediction(0.5, 0.25);
        // ucb(kappa=2) = 0.5 + 2 * sqrt(0.25) = 0.5 + 2*0.5 = 1.5
        assertThat(pred.ucb(2.0)).isCloseTo(1.5, within(1e-10));
    }

    @Test
    void fit_multiplePoints_producesConsistentPosterior() {
        // Generate 20 random-ish training points
        List<TrainingPoint> training = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            float[] emb = {(float) (i * 0.1), (float) (i * 0.05)};
            double reward = 0.3 + 0.5 * Math.sin(i * 0.3); // non-linear pattern
            training.add(new TrainingPoint(emb, reward, "be-java"));
        }
        GpPosterior posterior = engine.fit(training);
        assertThat(posterior).isNotNull();
        assertThat(posterior.alpha()).hasSize(20);

        // Predictions at training points should have low variance
        for (int i = 0; i < 20; i++) {
            GpPrediction pred = engine.predict(posterior, training.get(i).embedding());
            assertThat(pred.sigma2()).as("sigma2 at training point %d", i).isLessThan(0.1);
        }
    }
}
