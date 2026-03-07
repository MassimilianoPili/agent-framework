package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.FisherInformation.UncertaintyDecomposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link FisherInformation}.
 *
 * <p>Verifies Fisher information formulas, Cramér-Rao bounds,
 * uncertainty reducibility, information gain, Fisher distance,
 * and uncertainty decomposition.</p>
 */
@DisplayName("Fisher Information — uncertainty decomposition")
class FisherInformationTest {

    @Test
    void fisherInfoMean_normalCase_returnsNOverSigma2() {
        // I(mu) = n / sigma2 = 10 / 0.5 = 20.0
        double info = FisherInformation.fisherInfoMean(10, 0.5);
        assertThat(info).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void fisherInfoMean_zeroVariance_returnsMaxValue() {
        double info = FisherInformation.fisherInfoMean(10, 0.0);
        assertThat(info).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void fisherInfoVariance_normalCase_returnsFormula() {
        // I(sigma2) = n / (2 * sigma2^2) = 10 / (2 * 0.25) = 20.0
        double info = FisherInformation.fisherInfoVariance(10, 0.5);
        assertThat(info).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void cramerRaoLowerBound_matchesFormula() {
        // CRLB = sigma2 / n = 0.5 / 10 = 0.05
        double crlb = FisherInformation.cramerRaoLowerBound(10, 0.5);
        assertThat(crlb).isCloseTo(0.05, within(1e-9));
    }

    @Test
    void isUncertaintyReducible_highVariance_returnsTrue() {
        // sigma2=1.0, n=5, adding 20 samples
        // CRLB(5) = 1.0/5 = 0.2, CRLB(25) = 1.0/25 = 0.04
        // Relative reduction = (0.2 - 0.04) / 0.2 = 0.8 > threshold=0.3
        boolean reducible = FisherInformation.isUncertaintyReducible(1.0, 5, 20, 0.3);
        assertThat(reducible).isTrue();
    }

    @Test
    void isUncertaintyReducible_atFloor_returnsFalse() {
        // sigma2=1.0, n=100, adding 1 sample
        // CRLB(100) = 0.01, CRLB(101) = 0.0099
        // Relative reduction ≈ 0.01 < threshold=0.3
        boolean reducible = FisherInformation.isUncertaintyReducible(1.0, 100, 1, 0.3);
        assertThat(reducible).isFalse();
    }

    @Test
    void expectedInformationGain_positiveAndDecreasing() {
        // IG = sigma2 * k / (n * (n+k))
        double ig5 = FisherInformation.expectedInformationGain(1.0, 10, 5);
        double ig10 = FisherInformation.expectedInformationGain(1.0, 10, 10);
        double ig100 = FisherInformation.expectedInformationGain(1.0, 10, 100);

        assertThat(ig5).isPositive();
        assertThat(ig10).isPositive();
        assertThat(ig100).isPositive();

        // IG per additional sample should decrease (diminishing returns)
        double igPerSample5 = ig5 / 5;
        double igPerSample100 = ig100 / 100;
        assertThat(igPerSample5).isGreaterThan(igPerSample100);
    }

    @Test
    void fisherDistance_identicalDistributions_returnsZero() {
        double d = FisherInformation.fisherDistance(0.5, 0.3, 0.5, 0.3);
        assertThat(d).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void fisherDistance_differentMeans_returnsPositive() {
        double d = FisherInformation.fisherDistance(0.3, 0.5, 0.8, 0.5);
        assertThat(d).isPositive();
        // d = sqrt((0.3-0.8)^2 / 0.25 + 0) = sqrt(0.25/0.25) = sqrt(1.0) = 1.0
        assertThat(d).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void decompose_withObservations_returnsCorrectDecomposition() {
        // Observations with known spread
        double[] obs = {0.5, 0.6, 0.7, 0.8, 0.9, 0.5, 0.6, 0.7, 0.8, 0.9};
        double noiseVariance = 0.01; // small noise floor

        UncertaintyDecomposition result = FisherInformation.decompose(obs, noiseVariance);

        assertThat(result.totalUncertainty()).isPositive();
        assertThat(result.irreducibleFloor()).isCloseTo(0.01, within(1e-9));
        assertThat(result.reducibleUncertainty())
                .isCloseTo(result.totalUncertainty() - result.irreducibleFloor(), within(1e-9));
        assertThat(result.fisherInfoMean()).isPositive();
        assertThat(result.cramerRaoBound()).isPositive();
        assertThat(result.worthExploring()).isTrue(); // lots of reducible uncertainty
    }

    @Test
    void decompose_emptyObservations_returnsZeroDecomposition() {
        UncertaintyDecomposition result = FisherInformation.decompose(new double[]{}, 0.1);
        assertThat(result.totalUncertainty()).isCloseTo(0.0, within(1e-9));
        assertThat(result.worthExploring()).isFalse();
    }
}
