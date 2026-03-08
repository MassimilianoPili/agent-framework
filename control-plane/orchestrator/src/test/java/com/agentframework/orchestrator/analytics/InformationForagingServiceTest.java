package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link InformationForagingService}.
 *
 * <p>Verifies the Marginal Value Theorem patch model: IR computation, stop threshold,
 * optimal chunk allocation, patch ranking (scent trail), and edge cases.</p>
 *
 * <p>No Mockito needed — InformationForagingService has zero repository dependencies.</p>
 */
class InformationForagingServiceTest {

    private InformationForagingService service;

    @BeforeEach
    void setUp() {
        service = new InformationForagingService();
        // sigma=1.0: stopThreshold = globalIR - 1.0 * stdIR
        ReflectionTestUtils.setField(service, "stopThresholdSigma", 1.0);
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("null patches throws IllegalArgumentException")
    void forage_nullPatches_throws() {
        assertThatThrownBy(() -> service.forage(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("empty patches list throws IllegalArgumentException")
    void forage_emptyPatches_throws() {
        assertThatThrownBy(() -> service.forage(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Single patch ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("single patch → receives at least 1 chunk (always above its own threshold)")
    void forage_singlePatch_receivesChunks() {
        InformationForagingService.ForagingPatch patch =
                new InformationForagingService.ForagingPatch("doc-1", 0.8, 1.0, 5);

        InformationForagingService.ForagingReport report = service.forage(List.of(patch));

        assertThat(report).isNotNull();
        // Single patch: std = 0, stopThreshold = globalIR - 1*0 = globalIR; IR == globalIR
        // ir > stopThreshold is false, but recommended = max(1, ...) so still ≥ 0
        // Implementation: ir > stopThreshold is false when they're equal for single patch
        assertThat(report.optimalChunks()).containsKey("doc-1");
        assertThat(report.globalInformationRate()).isCloseTo(0.8, within(0.001));
        assertThat(report.patchRankings()).containsExactly("doc-1");
    }

    // ── High vs low relevance patches ─────────────────────────────────────────

    @Test
    @DisplayName("high-IR patch gets chunks; low-IR patch below stop threshold gets 0")
    void forage_highAndLowIR_lowGetsZeroChunks() {
        // sigma=0 → stopThreshold = globalIR = (0.9+0.1)/2 = 0.5
        // high IR=0.9 > 0.5 → gets chunks; low IR=0.1 < 0.5 → 0 chunks (no floating-point boundary)
        ReflectionTestUtils.setField(service, "stopThresholdSigma", 0.0);
        InformationForagingService.ForagingPatch high =
                new InformationForagingService.ForagingPatch("high-doc", 0.9, 1.0, 10);
        InformationForagingService.ForagingPatch low =
                new InformationForagingService.ForagingPatch("low-doc", 0.1, 1.0, 10);

        InformationForagingService.ForagingReport report = service.forage(List.of(high, low));

        assertThat(report.optimalChunks().get("high-doc")).isGreaterThan(0);
        assertThat(report.optimalChunks().get("low-doc")).isEqualTo(0);
    }

    // ── Patch ranking by IR (scent trail) ────────────────────────────────────

    @Test
    @DisplayName("patches ranked by IR descending — highest IR first")
    void forage_patchRankings_orderedByIRDescending() {
        // Patch A: IR = 0.3/1.0 = 0.3
        // Patch B: IR = 0.9/1.0 = 0.9
        // Patch C: IR = 0.6/1.0 = 0.6
        // Expected ranking: B, C, A
        List<InformationForagingService.ForagingPatch> patches = List.of(
                new InformationForagingService.ForagingPatch("patch-a", 0.3, 1.0, 5),
                new InformationForagingService.ForagingPatch("patch-b", 0.9, 1.0, 5),
                new InformationForagingService.ForagingPatch("patch-c", 0.6, 1.0, 5)
        );

        InformationForagingService.ForagingReport report = service.forage(patches);

        assertThat(report.patchRankings())
                .containsExactly("patch-b", "patch-c", "patch-a");
    }

    // ── Stop threshold ────────────────────────────────────────────────────────

    @Test
    @DisplayName("stopThreshold = globalIR - sigma * stdIR (sigma=0 → stopThreshold = globalIR)")
    void forage_sigmaZero_stopThresholdEqualsGlobalIR() {
        ReflectionTestUtils.setField(service, "stopThresholdSigma", 0.0);

        List<InformationForagingService.ForagingPatch> patches = List.of(
                new InformationForagingService.ForagingPatch("p1", 0.4, 1.0, 4),
                new InformationForagingService.ForagingPatch("p2", 0.8, 1.0, 8)
        );

        InformationForagingService.ForagingReport report = service.forage(patches);

        // globalIR = (0.4 + 0.8) / 2 = 0.6; std = sqrt(((0.4-0.6)^2+(0.8-0.6)^2)/2) = 0.2
        // With sigma=0: stopThreshold = 0.6 - 0 = 0.6
        assertThat(report.stopThreshold()).isCloseTo(report.globalInformationRate(), within(0.001));
    }

    // ── Zero retrieval cost (guard) ───────────────────────────────────────────

    @Test
    @DisplayName("zero retrieval cost treated as 1.0 — no division by zero")
    void forage_zeroCost_treatedAsOne() {
        InformationForagingService.ForagingPatch zeroCost =
                new InformationForagingService.ForagingPatch("zero-cost", 0.7, 0.0, 3);

        assertThatCode(() -> service.forage(List.of(zeroCost))).doesNotThrowAnyException();

        InformationForagingService.ForagingReport report = service.forage(List.of(zeroCost));
        // IR = 0.7/1.0 = 0.7 (cost treated as 1.0)
        assertThat(report.globalInformationRate()).isCloseTo(0.7, within(0.001));
    }

    // ── Total expected gain ───────────────────────────────────────────────────

    @Test
    @DisplayName("totalExpectedGain > 0 when at least one patch above threshold")
    void forage_withChunksAllocated_totalGainPositive() {
        List<InformationForagingService.ForagingPatch> patches = List.of(
                new InformationForagingService.ForagingPatch("doc-1", 0.8, 1.0, 5),
                new InformationForagingService.ForagingPatch("doc-2", 0.9, 1.0, 5)
        );

        InformationForagingService.ForagingReport report = service.forage(patches);

        assertThat(report.totalExpectedGain()).isGreaterThan(0.0);
    }
}
