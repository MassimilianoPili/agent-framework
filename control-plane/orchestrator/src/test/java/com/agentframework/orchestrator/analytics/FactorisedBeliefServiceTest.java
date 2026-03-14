package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.FactorisedBeliefService.AgentBelief;
import com.agentframework.orchestrator.analytics.FactorisedBeliefService.EfeScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link FactorisedBeliefService}.
 *
 * <p>Verifies Bayesian belief updates, EFE computation (epistemic vs pragmatic),
 * selection, matrix snapshot, and cold-start behaviour.</p>
 */
class FactorisedBeliefServiceTest {

    private FactorisedBeliefService service;

    @BeforeEach
    void setUp() {
        service = new FactorisedBeliefService();
        ReflectionTestUtils.setField(service, "priorMu", 0.5);
        ReflectionTestUtils.setField(service, "priorSigma2", 1.0);
        ReflectionTestUtils.setField(service, "explorationLambda", 1.0);
    }

    // --- Cold start / prior ---

    @Test
    @DisplayName("getBelief returns prior when no observations exist")
    void getBelief_returnsPrior() {
        AgentBelief belief = service.getBelief("observer", "subject");

        assertThat(belief.mu()).isEqualTo(0.5);
        assertThat(belief.sigma2()).isEqualTo(1.0);
        assertThat(belief.observations()).isZero();
    }

    // --- Belief updates ---

    @Test
    @DisplayName("updateBelief shifts mu toward observed reward")
    void updateBelief_shiftsMuTowardObservation() {
        // Prior: mu=0.5, sigma2=1.0. Observe reward=1.0
        AgentBelief updated = service.updateBelief("A", "B", 1.0);

        // mu should shift toward 1.0
        assertThat(updated.mu()).isGreaterThan(0.5);
        assertThat(updated.mu()).isLessThanOrEqualTo(1.0);
        assertThat(updated.observations()).isEqualTo(1);
    }

    @Test
    @DisplayName("updateBelief reduces variance (increases precision)")
    void updateBelief_reducesSigma2() {
        AgentBelief updated = service.updateBelief("A", "B", 0.8);

        // Variance should decrease after observation
        assertThat(updated.sigma2()).isLessThan(1.0);
    }

    @Test
    @DisplayName("multiple updates converge mu toward observation mean")
    void updateBelief_multipleUpdatesConverge() {
        // Repeatedly observe reward=0.9
        for (int i = 0; i < 20; i++) {
            service.updateBelief("A", "B", 0.9);
        }

        AgentBelief belief = service.getBelief("A", "B");
        assertThat(belief.mu()).isCloseTo(0.9, within(0.05));
        assertThat(belief.sigma2()).isLessThan(0.01); // very confident
        assertThat(belief.observations()).isEqualTo(20);
    }

    @Test
    @DisplayName("precision pooling: high-precision prior resists single observation")
    void updateBelief_precisionPooling() {
        // Set very tight prior (low sigma2 = high precision)
        ReflectionTestUtils.setField(service, "priorSigma2", 0.01);

        AgentBelief updated = service.updateBelief("A", "B", 1.0);

        // With tight prior at 0.5, single observation at 1.0 should barely move mu
        assertThat(updated.mu()).isLessThan(0.6);
    }

    // --- EFE computation ---

    @Test
    @DisplayName("computeEfe returns empty for null/empty candidates")
    void computeEfe_emptyCandidates() {
        assertThat(service.computeEfe("A", List.of(), 0.8)).isEmpty();
        assertThat(service.computeEfe("A", null, 0.8)).isEmpty();
    }

    @Test
    @DisplayName("computeEfe epistemic value is higher for uncertain agents")
    void computeEfe_epistemicHigherForUncertain() {
        // Make B well-known (low sigma2) and C unknown (prior sigma2)
        for (int i = 0; i < 10; i++) {
            service.updateBelief("A", "B", 0.7);
        }
        // C stays at prior

        List<EfeScore> scores = service.computeEfe("A", List.of("B", "C"), 0.7);

        EfeScore scoreB = scores.stream().filter(s -> s.workerType().equals("B")).findFirst().orElseThrow();
        EfeScore scoreC = scores.stream().filter(s -> s.workerType().equals("C")).findFirst().orElseThrow();

        // C (unknown) should have higher epistemic value
        assertThat(scoreC.epistemicValue()).isGreaterThan(scoreB.epistemicValue());
    }

    @Test
    @DisplayName("computeEfe pragmatic value favors agents closer to desired reward")
    void computeEfe_pragmaticFavorsCloserToDesired() {
        // Train B toward 0.9, C toward 0.3
        for (int i = 0; i < 10; i++) {
            service.updateBelief("A", "B", 0.9);
            service.updateBelief("A", "C", 0.3);
        }

        List<EfeScore> scores = service.computeEfe("A", List.of("B", "C"), 0.9);

        EfeScore scoreB = scores.stream().filter(s -> s.workerType().equals("B")).findFirst().orElseThrow();
        EfeScore scoreC = scores.stream().filter(s -> s.workerType().equals("C")).findFirst().orElseThrow();

        // B (mu≈0.9) should have better pragmatic value when desired=0.9
        assertThat(scoreB.pragmaticValue()).isGreaterThan(scoreC.pragmaticValue());
    }

    @Test
    @DisplayName("EFE scores are sorted descending (best first)")
    void computeEfe_sortedDescending() {
        List<EfeScore> scores = service.computeEfe("A", List.of("X", "Y", "Z"), 0.5);

        for (int i = 1; i < scores.size(); i++) {
            assertThat(scores.get(i).totalEfe()).isLessThanOrEqualTo(scores.get(i - 1).totalEfe());
        }
    }

    // --- Selection ---

    @Test
    @DisplayName("selectByEfe returns best candidate")
    void selectByEfe_returnsBest() {
        // Train B as high-performer
        for (int i = 0; i < 10; i++) {
            service.updateBelief("A", "B", 0.9);
            service.updateBelief("A", "C", 0.3);
        }

        Optional<EfeScore> result = service.selectByEfe("A", List.of("B", "C"), 0.9);

        assertThat(result).isPresent();
        assertThat(result.get().workerType()).isEqualTo("B");
    }

    @Test
    @DisplayName("selectByEfe returns empty for no candidates")
    void selectByEfe_emptyForNoCandidates() {
        assertThat(service.selectByEfe("A", List.of(), 0.8)).isEmpty();
    }

    @Test
    @DisplayName("exploration: unknown agent selected over known mediocre with high lambda")
    void selectByEfe_explorationSelectsUnknown() {
        // High exploration weight
        ReflectionTestUtils.setField(service, "explorationLambda", 5.0);

        // Make B well-known but mediocre (mu≈0.5, low sigma2)
        for (int i = 0; i < 20; i++) {
            service.updateBelief("A", "B", 0.5);
        }
        // C is unknown (prior: mu=0.5, sigma2=1.0)

        Optional<EfeScore> result = service.selectByEfe("A", List.of("B", "C"), 0.5);

        // With high lambda, C should be preferred for exploration
        assertThat(result).isPresent();
        assertThat(result.get().workerType()).isEqualTo("C");
    }

    // --- Belief matrix ---

    @Test
    @DisplayName("getBeliefMatrix returns snapshot of all beliefs")
    void getBeliefMatrix_snapshot() {
        service.updateBelief("A", "B", 0.8);
        service.updateBelief("A", "C", 0.6);
        service.updateBelief("X", "Y", 0.9);

        Map<String, Map<String, AgentBelief>> matrix = service.getBeliefMatrix();

        assertThat(matrix).containsKeys("A", "X");
        assertThat(matrix.get("A")).containsKeys("B", "C");
        assertThat(matrix.get("X")).containsKeys("Y");
    }

    @Test
    @DisplayName("getTotalBeliefCount returns correct count")
    void getTotalBeliefCount_correct() {
        service.updateBelief("A", "B", 0.8);
        service.updateBelief("A", "C", 0.6);
        service.updateBelief("X", "Y", 0.9);

        assertThat(service.getTotalBeliefCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("beliefs are partitioned by observer")
    void beliefs_partitionedByObserver() {
        service.updateBelief("A", "B", 0.9);
        service.updateBelief("X", "B", 0.3);

        AgentBelief beliefAB = service.getBelief("A", "B");
        AgentBelief beliefXB = service.getBelief("X", "B");

        // Same subject B, but different observers should have different beliefs
        assertThat(beliefAB.mu()).isNotEqualTo(beliefXB.mu());
    }
}
