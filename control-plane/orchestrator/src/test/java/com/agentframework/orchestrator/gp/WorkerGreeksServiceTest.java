package com.agentframework.orchestrator.gp;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.reward.WorkerEloStats;
import com.agentframework.orchestrator.reward.WorkerEloStatsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkerGreeksService}.
 */
@ExtendWith(MockitoExtension.class)
class WorkerGreeksServiceTest {

    @Mock
    private TaskOutcomeService outcomeService;

    @Mock
    private WorkerEloStatsRepository eloStatsRepository;

    @InjectMocks
    private WorkerGreeksService service;

    private static final float[] EMBEDDING = {0.1f, 0.2f, 0.3f, 0.4f};

    @Test
    void computeGreeks_flatGp_deltaAndGammaZero() {
        // GP returns constant mu regardless of embedding → delta ≈ 0, gamma ≈ 0
        when(outcomeService.predict(any(float[].class), eq("BE"), eq("be-java")))
                .thenReturn(new GpPrediction(0.5, 0.1));
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.empty());

        WorkerGreeks greeks = service.computeGreeks("be-java", "BE", EMBEDDING);

        assertThat(greeks.delta()).isCloseTo(0.0, within(1e-9));
        assertThat(greeks.gamma()).isCloseTo(0.0, within(1e-9));
        assertThat(greeks.baseMu()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void computeGreeks_increasingMu_positiveDelta() {
        // GP returns higher mu for higher embedding magnitudes
        when(outcomeService.predict(any(float[].class), eq("BE"), eq("be-java")))
                .thenAnswer(inv -> {
                    float[] emb = inv.getArgument(0);
                    double magnitude = 0;
                    for (float v : emb) magnitude += v * v;
                    return new GpPrediction(Math.sqrt(magnitude), 0.1);
                });
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.empty());

        WorkerGreeks greeks = service.computeGreeks("be-java", "BE", EMBEDDING);

        assertThat(greeks.delta()).isGreaterThan(0.0);
    }

    @Test
    void computeGreeks_concaveMu_negativeGamma() {
        // GP returns concave mu: mu = -magnitude² → gamma < 0
        when(outcomeService.predict(any(float[].class), eq("BE"), eq("be-java")))
                .thenAnswer(inv -> {
                    float[] emb = inv.getArgument(0);
                    double mag2 = 0;
                    for (float v : emb) mag2 += v * v;
                    return new GpPrediction(-mag2, 0.1);
                });
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.empty());

        WorkerGreeks greeks = service.computeGreeks("be-java", "BE", EMBEDDING);

        assertThat(greeks.gamma()).isLessThan(0.0);
    }

    @Test
    void computeGreeks_zeroSigma_vegaIsZero() {
        // GP returns sigma2 = 0 → vega should be 0 (no uncertainty perturbation)
        when(outcomeService.predict(any(float[].class), eq("BE"), eq("be-java")))
                .thenReturn(new GpPrediction(0.5, 0.0)); // sigma2 = 0
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.empty());

        WorkerGreeks greeks = service.computeGreeks("be-java", "BE", EMBEDDING);

        assertThat(greeks.vega()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void computeGreeks_thetaFromEloStats() {
        when(outcomeService.predict(any(float[].class), eq("BE"), eq("be-java")))
                .thenReturn(new GpPrediction(0.5, 0.1));

        WorkerEloStats stats = new WorkerEloStats("be-java");
        // Set up match data for avgReward
        try {
            var matchField = WorkerEloStats.class.getDeclaredField("matchCount");
            matchField.setAccessible(true);
            matchField.setInt(stats, 10);
            var rewardField = WorkerEloStats.class.getDeclaredField("cumulativeReward");
            rewardField.setAccessible(true);
            rewardField.setDouble(stats, 7.0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.of(stats));

        WorkerGreeks greeks = service.computeGreeks("be-java", "BE", EMBEDDING);

        // theta = avgReward = 7.0 / 10 = 0.7
        assertThat(greeks.theta()).isCloseTo(0.7, within(1e-9));
    }

    @Test
    void scaleEmbedding_correctScaling() {
        float[] emb = {1.0f, 2.0f, 3.0f};
        float[] scaled = WorkerGreeksService.scaleEmbedding(emb, 1.5);

        assertThat(scaled[0]).isCloseTo(1.5f, within(1e-6f));
        assertThat(scaled[1]).isCloseTo(3.0f, within(1e-6f));
        assertThat(scaled[2]).isCloseTo(4.5f, within(1e-6f));
    }
}
