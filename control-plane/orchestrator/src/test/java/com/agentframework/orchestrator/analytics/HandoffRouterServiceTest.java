package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.HandoffRouterService.HandoffDecision;
import com.agentframework.orchestrator.analytics.HandoffRouterService.HandoffRequest;
import com.agentframework.orchestrator.analytics.HandoffRouterService.HandoffStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HandoffRouterService}.
 *
 * <p>Verifies confidence-based routing, chain depth limits, anti-cycle detection,
 * outcome tracking, and statistics.</p>
 */
class HandoffRouterServiceTest {

    private HandoffRouterService service;

    @BeforeEach
    void setUp() {
        service = new HandoffRouterService();
        ReflectionTestUtils.setField(service, "confidenceThreshold", 0.7);
        ReflectionTestUtils.setField(service, "maxChainLength", 5);
    }

    // --- Direct handoff ---

    @Test
    @DisplayName("high confidence routes directly to candidate")
    void route_highConfidence_directHandoff() {
        HandoffRequest request = new HandoffRequest(
                "BE", List.of("FE", "AI_TASK"), "needs frontend", 0.9, 0, Set.of());

        HandoffDecision decision = service.route(request);

        assertThat(decision.directHandoff()).isTrue();
        assertThat(decision.selectedWorker()).isIn("FE", "AI_TASK");
    }

    @Test
    @DisplayName("low confidence falls back to centralized routing")
    void route_lowConfidence_centralized() {
        HandoffRequest request = new HandoffRequest(
                "BE", List.of("FE"), "uncertain", 0.3, 0, Set.of());

        HandoffDecision decision = service.route(request);

        assertThat(decision.directHandoff()).isFalse();
        assertThat(decision.selectedWorker()).isNull();
        assertThat(decision.routingReason()).contains("centralized");
    }

    // --- Chain depth limit ---

    @Test
    @DisplayName("chain depth at max triggers centralized routing")
    void route_chainDepthExceeded() {
        HandoffRequest request = new HandoffRequest(
                "BE", List.of("FE"), "deep chain", 0.99, 5, Set.of());

        HandoffDecision decision = service.route(request);

        assertThat(decision.directHandoff()).isFalse();
        assertThat(decision.routingReason()).contains("Chain depth");
    }

    @Test
    @DisplayName("depth-scaled threshold requires higher confidence for deeper chains")
    void route_depthScaledThreshold() {
        // At depth 0: threshold = 0.7, confidence 0.75 → direct
        HandoffDecision shallow = service.route(new HandoffRequest(
                "BE", List.of("FE"), "reason", 0.75, 0, Set.of()));
        assertThat(shallow.directHandoff()).isTrue();

        // At depth 2: threshold = 0.7 + 0.2 = 0.9, confidence 0.75 → centralized
        HandoffDecision deep = service.route(new HandoffRequest(
                "BE", List.of("FE"), "reason", 0.75, 2, Set.of()));
        assertThat(deep.directHandoff()).isFalse();
    }

    // --- Anti-cycle ---

    @Test
    @DisplayName("visited workers are excluded from candidates")
    void route_antiCycleFiltersVisited() {
        HandoffRequest request = new HandoffRequest(
                "BE", List.of("FE", "AI_TASK"), "reason", 0.9, 1, Set.of("FE"));

        HandoffDecision decision = service.route(request);

        assertThat(decision.directHandoff()).isTrue();
        assertThat(decision.selectedWorker()).isEqualTo("AI_TASK");
    }

    @Test
    @DisplayName("all candidates visited triggers centralized routing")
    void route_allCandidatesVisited() {
        HandoffRequest request = new HandoffRequest(
                "BE", List.of("FE", "AI_TASK"), "reason", 0.9, 1, Set.of("FE", "AI_TASK"));

        HandoffDecision decision = service.route(request);

        assertThat(decision.directHandoff()).isFalse();
        assertThat(decision.routingReason()).contains("cycle");
    }

    // --- Empty candidates ---

    @Test
    @DisplayName("empty candidates triggers centralized routing")
    void route_emptyCandidates() {
        HandoffRequest request = new HandoffRequest(
                "BE", List.of(), "no targets", 0.9, 0, Set.of());

        HandoffDecision decision = service.route(request);

        assertThat(decision.directHandoff()).isFalse();
    }

    @Test
    @DisplayName("null candidates triggers centralized routing")
    void route_nullCandidates() {
        HandoffRequest request = new HandoffRequest(
                "BE", null, "no targets", 0.9, 0, Set.of());

        HandoffDecision decision = service.route(request);

        assertThat(decision.directHandoff()).isFalse();
    }

    // --- Outcome tracking ---

    @Test
    @DisplayName("recordOutcome tracks pair success rates")
    void recordOutcome_tracksPairRates() {
        service.recordOutcome("BE", "FE", true);
        service.recordOutcome("BE", "FE", true);
        service.recordOutcome("BE", "FE", false);

        assertThat(service.getPairSuccessRate("BE", "FE")).isCloseTo(0.6667, within(0.01));
    }

    @Test
    @DisplayName("getPairSuccessRate returns NaN for unknown pair")
    void getPairSuccessRate_unknownPair() {
        assertThat(service.getPairSuccessRate("X", "Y")).isNaN();
    }

    @Test
    @DisplayName("route prefers candidate with better historical success")
    void route_prefersBetterCandidate() {
        // Build history: BE→FE has 90% success, BE→AI_TASK has 30%
        for (int i = 0; i < 9; i++) service.recordOutcome("BE", "FE", true);
        service.recordOutcome("BE", "FE", false);
        for (int i = 0; i < 3; i++) service.recordOutcome("BE", "AI_TASK", true);
        for (int i = 0; i < 7; i++) service.recordOutcome("BE", "AI_TASK", false);

        HandoffDecision decision = service.route(new HandoffRequest(
                "BE", List.of("AI_TASK", "FE"), "reason", 0.9, 0, Set.of()));

        assertThat(decision.selectedWorker()).isEqualTo("FE");
    }

    // --- Stats ---

    @Test
    @DisplayName("getStats returns correct aggregation")
    void getStats_correctAggregation() {
        service.route(new HandoffRequest("BE", List.of("FE"), "r", 0.9, 0, Set.of())); // direct
        service.route(new HandoffRequest("BE", List.of("FE"), "r", 0.3, 0, Set.of())); // centralized

        HandoffStats stats = service.getStats();

        assertThat(stats.totalRequests()).isEqualTo(2);
        assertThat(stats.directHandoffs()).isEqualTo(1);
        assertThat(stats.centralizedFallbacks()).isEqualTo(1);
        assertThat(stats.avgConfidence()).isCloseTo(0.6, within(0.01));
    }
}
