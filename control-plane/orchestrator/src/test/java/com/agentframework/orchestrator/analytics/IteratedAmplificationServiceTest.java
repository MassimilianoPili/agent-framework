package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.IteratedAmplificationService.AmplificationDecision;
import com.agentframework.orchestrator.analytics.IteratedAmplificationService.FeedbackLevel;
import com.agentframework.orchestrator.analytics.IteratedAmplificationService.OversightStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link IteratedAmplificationService}.
 *
 * <p>Verifies feedback level cascade (AUTO → MODEL → HUMAN),
 * spot-check scheduling, accuracy tracking, and stats aggregation.</p>
 */
class IteratedAmplificationServiceTest {

    private IteratedAmplificationService service;

    @BeforeEach
    void setUp() {
        service = new IteratedAmplificationService();
        ReflectionTestUtils.setField(service, "autoThreshold", 0.3);
        ReflectionTestUtils.setField(service, "modelThreshold", 0.7);
        ReflectionTestUtils.setField(service, "spotCheckRatio", 0.1);
    }

    // --- Feedback level cascade ---

    @Test
    @DisplayName("simple task with low uncertainty gets AUTO level")
    void decideFeedbackLevel_simpleTaskAuto() {
        AmplificationDecision decision = service.decideFeedbackLevel("BE", 0.1, 0.1);

        // First decision — not a spot-check interval
        assertThat(decision.level()).isIn(FeedbackLevel.AUTO, FeedbackLevel.HUMAN_SPOT_CHECK);
        assertThat(decision.confidence()).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("medium task gets MODEL level")
    void decideFeedbackLevel_mediumTaskModel() {
        AmplificationDecision decision = service.decideFeedbackLevel("BE", 0.5, 0.4);

        assertThat(decision.level()).isEqualTo(FeedbackLevel.MODEL);
        assertThat(decision.rationale()).contains("Model review");
    }

    @Test
    @DisplayName("complex task gets HUMAN_FULL level")
    void decideFeedbackLevel_complexTaskHuman() {
        AmplificationDecision decision = service.decideFeedbackLevel("BE", 0.9, 0.8);

        assertThat(decision.level()).isEqualTo(FeedbackLevel.HUMAN_FULL);
        assertThat(decision.rationale()).contains("Human review");
    }

    @Test
    @DisplayName("high uncertainty alone triggers HUMAN_FULL")
    void decideFeedbackLevel_highUncertaintyHuman() {
        AmplificationDecision decision = service.decideFeedbackLevel("BE", 0.2, 0.9);

        // gpUncertainty >= modelThreshold → HUMAN_FULL
        assertThat(decision.level()).isEqualTo(FeedbackLevel.HUMAN_FULL);
    }

    @Test
    @DisplayName("low complexity but medium uncertainty triggers MODEL")
    void decideFeedbackLevel_lowComplexityMediumUncertainty() {
        AmplificationDecision decision = service.decideFeedbackLevel("BE", 0.1, 0.5);

        assertThat(decision.level()).isEqualTo(FeedbackLevel.MODEL);
    }

    // --- Spot-check scheduling ---

    @Test
    @DisplayName("spot-check triggers on every Nth AUTO decision")
    void decideFeedbackLevel_spotCheckTriggers() {
        // spotCheckRatio=0.1 → every 10th AUTO decision
        FeedbackLevel spotCheckLevel = null;
        int spotCheckCount = 0;

        for (int i = 0; i < 20; i++) {
            AmplificationDecision decision = service.decideFeedbackLevel("BE", 0.1, 0.1);
            if (decision.level() == FeedbackLevel.HUMAN_SPOT_CHECK) {
                spotCheckCount++;
                spotCheckLevel = decision.level();
            }
        }

        assertThat(spotCheckLevel).isEqualTo(FeedbackLevel.HUMAN_SPOT_CHECK);
        assertThat(spotCheckCount).isEqualTo(2); // 10th and 20th
    }

    // --- Accuracy tracking ---

    @Test
    @DisplayName("recordOversightOutcome tracks accuracy per level")
    void recordOversightOutcome_tracksAccuracy() {
        service.decideFeedbackLevel("BE", 0.1, 0.1); // generate at least one decision

        service.recordOversightOutcome("t1", FeedbackLevel.AUTO, true);
        service.recordOversightOutcome("t2", FeedbackLevel.AUTO, true);
        service.recordOversightOutcome("t3", FeedbackLevel.AUTO, false);

        OversightStats stats = service.getOversightStats();
        Double autoAccuracy = stats.accuracy().get(FeedbackLevel.AUTO);
        assertThat(autoAccuracy).isCloseTo(0.6667, within(0.01));
    }

    // --- Stats aggregation ---

    @Test
    @DisplayName("getOversightStats returns distribution and cost")
    void getOversightStats_correctDistribution() {
        // Generate decisions at different levels
        service.decideFeedbackLevel("BE", 0.1, 0.1); // AUTO
        service.decideFeedbackLevel("BE", 0.5, 0.4); // MODEL
        service.decideFeedbackLevel("BE", 0.9, 0.8); // HUMAN_FULL

        OversightStats stats = service.getOversightStats();

        assertThat(stats.totalRequests()).isEqualTo(3);
        assertThat(stats.distribution().get(FeedbackLevel.MODEL)).isEqualTo(1);
        assertThat(stats.distribution().get(FeedbackLevel.HUMAN_FULL)).isEqualTo(1);
        assertThat(stats.avgCostPerTask()).isGreaterThan(1.0); // not all AUTO
    }

    @Test
    @DisplayName("getOversightStats on empty service returns zero counts")
    void getOversightStats_emptyService() {
        OversightStats stats = service.getOversightStats();

        assertThat(stats.totalRequests()).isZero();
        assertThat(stats.avgCostPerTask()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("HUMAN_FULL has higher cost impact than AUTO")
    void getOversightStats_costModel() {
        // 10 AUTO decisions
        for (int i = 0; i < 10; i++) {
            service.decideFeedbackLevel("BE", 0.1, 0.1);
        }
        double autoOnlyCost = service.getOversightStats().avgCostPerTask();

        // Reset and do 10 HUMAN_FULL
        IteratedAmplificationService service2 = new IteratedAmplificationService();
        ReflectionTestUtils.setField(service2, "autoThreshold", 0.3);
        ReflectionTestUtils.setField(service2, "modelThreshold", 0.7);
        ReflectionTestUtils.setField(service2, "spotCheckRatio", 0.1);
        for (int i = 0; i < 10; i++) {
            service2.decideFeedbackLevel("BE", 0.9, 0.9);
        }
        double humanOnlyCost = service2.getOversightStats().avgCostPerTask();

        assertThat(humanOnlyCost).isGreaterThan(autoOnlyCost);
    }
}
