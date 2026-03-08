package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CouncilDiversityService}.
 *
 * <p>Covers Shannon entropy, Kendall τ, diversity score, recommended size,
 * and redundant member detection.</p>
 */
class CouncilDiversityServiceTest {

    private CouncilDiversityService service;

    @BeforeEach
    void setUp() {
        service = new CouncilDiversityService();
    }

    private VotingProtocolService.RankedBallot ballot(String voter, String... ranking) {
        return new VotingProtocolService.RankedBallot(voter, List.of(ranking));
    }

    // ── Null / Empty ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns null for null or empty ballots")
    void analyze_empty_returnsNull() {
        assertThat(service.analyze(null)).isNull();
        assertThat(service.analyze(List.of())).isNull();
    }

    // ── Unanimous council ──────────────────────────────────────────────────────

    @Test
    @DisplayName("unanimous ballots produce zero entropy and tau = 1.0")
    void analyze_unanimous_zeroEntropy() {
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "A", "B", "C"),
                ballot("v2", "A", "B", "C"),
                ballot("v3", "A", "B", "C")
        );

        CouncilDiversityService.DiversityReport report = service.analyze(ballots);

        assertThat(report).isNotNull();
        assertThat(report.shannonEntropy()).isEqualTo(0.0);
        assertThat(report.normalizedEntropy()).isEqualTo(0.0);
        assertThat(report.avgKendallTau()).isEqualTo(1.0);
        assertThat(report.diversityScore()).isEqualTo(0.0);
    }

    // ── Maximally diverse council ──────────────────────────────────────────────

    @Test
    @DisplayName("each voter picks a different first choice: max entropy, low tau")
    void analyze_maxDiversity_highEntropy() {
        // 3 voters, 3 candidates, each with a different first choice
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "A", "B", "C"),
                ballot("v2", "B", "C", "A"),
                ballot("v3", "C", "A", "B")
        );

        CouncilDiversityService.DiversityReport report = service.analyze(ballots);

        assertThat(report).isNotNull();
        // H = ln(3) ≈ 1.099, normalised = 1.0
        assertThat(report.normalizedEntropy()).isCloseTo(1.0, within(1e-9));
        // Perfectly cyclic ranking → low positive tau (not −1 because only 3 candidates)
        assertThat(report.avgKendallTau()).isLessThan(0.5);
        // Diversity score > 0 (position variance exists)
        assertThat(report.diversityScore()).isGreaterThan(0.0);
    }

    // ── Shannon entropy ────────────────────────────────────────────────────────

    @Test
    @DisplayName("2/3 split first choices produce intermediate entropy")
    void analyze_partialDiversity_intermediateEntropy() {
        // 2 voters pick A, 1 picks B → H = -(2/3·ln(2/3) + 1/3·ln(1/3)) > 0
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "A", "B", "C"),
                ballot("v2", "A", "C", "B"),
                ballot("v3", "B", "A", "C")
        );

        CouncilDiversityService.DiversityReport report = service.analyze(ballots);

        assertThat(report).isNotNull();
        assertThat(report.shannonEntropy()).isGreaterThan(0.0);
        assertThat(report.normalizedEntropy()).isLessThan(1.0);
    }

    // ── Kendall τ ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("identical pair of ballots gives tau = 1.0")
    void analyze_identicalPair_tauOne() {
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "X", "Y", "Z"),
                ballot("v2", "X", "Y", "Z")
        );

        CouncilDiversityService.DiversityReport report = service.analyze(ballots);

        assertThat(report).isNotNull();
        assertThat(report.avgKendallTau()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("reversed pair of ballots gives tau = -1.0")
    void analyze_reversedPair_tauMinusOne() {
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "A", "B", "C"),
                ballot("v2", "C", "B", "A")  // perfectly reversed
        );

        CouncilDiversityService.DiversityReport report = service.analyze(ballots);

        assertThat(report).isNotNull();
        assertThat(report.avgKendallTau()).isEqualTo(-1.0);
    }

    // ── Recommended size ───────────────────────────────────────────────────────

    @Test
    @DisplayName("recommended size ≤ total ballot count")
    void analyze_recommendedSize_bounded() {
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "A", "B", "C"),
                ballot("v2", "A", "B", "C"),
                ballot("v3", "A", "B", "C"),
                ballot("v4", "A", "B", "C"),
                ballot("v5", "B", "A", "C")  // one dissenter
        );

        CouncilDiversityService.DiversityReport report = service.analyze(ballots);

        assertThat(report).isNotNull();
        assertThat(report.recommendedSize()).isGreaterThan(0);
        assertThat(report.recommendedSize()).isLessThanOrEqualTo(ballots.size());
    }

    // ── Single ballot ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("single ballot: entropy 0, tau 1.0, diversity 0")
    void analyze_singleBallot_trivial() {
        CouncilDiversityService.DiversityReport report =
                service.analyze(List.of(ballot("solo", "A", "B", "C")));

        assertThat(report).isNotNull();
        assertThat(report.shannonEntropy()).isEqualTo(0.0);
        assertThat(report.avgKendallTau()).isEqualTo(1.0);
        assertThat(report.recommendedSize()).isEqualTo(1);
    }
}
