package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link VotingProtocolService}.
 *
 * <p>Covers all four voting methods (Plurality, Borda, Schulze, Approval),
 * Condorcet winner detection, and IIA violation flags.</p>
 */
class VotingProtocolServiceTest {

    private VotingProtocolService service;

    @BeforeEach
    void setUp() {
        service = new VotingProtocolService();
        ReflectionTestUtils.setField(service, "defaultMethod", "SCHULZE");
    }

    private VotingProtocolService.RankedBallot ballot(String voter, String... ranking) {
        return new VotingProtocolService.RankedBallot(voter, List.of(ranking));
    }

    // ── Plurality ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Plurality — candidate with most first-place votes wins")
    void plurality_mostFirstPlaceVotes_wins() {
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "A", "B", "C"),
                ballot("v2", "A", "C", "B"),
                ballot("v3", "B", "A", "C")
        );

        VotingProtocolService.VotingResult result =
                service.aggregate(ballots, VotingProtocolService.VotingMethod.PLURALITY);

        assertThat(result.winner()).isEqualTo("A");  // A got 2 first-place votes
        assertThat(result.methodUsed()).isEqualTo(VotingProtocolService.VotingMethod.PLURALITY);
    }

    // ── Borda Count ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Borda — consensus candidate scores highest across all positions")
    void borda_consensusCandidate_wins() {
        // A is consistently second → high Borda score; B is polarising (1st or last)
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "B", "A", "C"),
                ballot("v2", "A", "B", "C"),
                ballot("v3", "A", "B", "C"),
                ballot("v4", "C", "A", "B")
        );

        VotingProtocolService.VotingResult result =
                service.aggregate(ballots, VotingProtocolService.VotingMethod.BORDA);

        assertThat(result.winner()).isEqualTo("A");
        assertThat(result.scores().get("A")).isGreaterThan(result.scores().get("B"));
    }

    // ── Schulze ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Schulze — elects the Condorcet winner when one exists")
    void schulze_condorcetWinnerExists_electsIt() {
        // Classic Condorcet scenario: A beats B and C in pairwise majorities
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "A", "B", "C"),
                ballot("v2", "A", "C", "B"),
                ballot("v3", "B", "C", "A"),
                ballot("v4", "A", "B", "C")
        );

        VotingProtocolService.VotingResult result =
                service.aggregate(ballots, VotingProtocolService.VotingMethod.SCHULZE);

        assertThat(result.winner()).isEqualTo("A");
        assertThat(result.condorcetWinner()).contains("A");
        assertThat(result.iiaViolationDetected()).isFalse();
    }

    @Test
    @DisplayName("Schulze — handles Condorcet cycle (A>B>C>A) without crashing")
    void schulze_condorcetCycle_returnsWinner() {
        // Classic Condorcet paradox: A>B, B>C, C>A (no Condorcet winner)
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "A", "B", "C"),  // A>B>C
                ballot("v2", "B", "C", "A"),  // B>C>A
                ballot("v3", "C", "A", "B")   // C>A>B
        );

        VotingProtocolService.VotingResult result =
                service.aggregate(ballots, VotingProtocolService.VotingMethod.SCHULZE);

        // No Condorcet winner — but Schulze still returns someone
        assertThat(result).isNotNull();
        assertThat(result.winner()).isNotNull();
        assertThat(result.condorcetWinner()).isEmpty();
    }

    // ── Approval ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Approval — candidate with most top-50% approvals wins")
    void approval_mostApprovals_wins() {
        // 3 candidates → top-50% = top 2 (ceil(3 × 0.5) = 2)
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "A", "B", "C"),   // approves A, B
                ballot("v2", "A", "C", "B"),   // approves A, C
                ballot("v3", "B", "A", "C"),   // approves B, A
                ballot("v4", "A", "B", "C")    // approves A, B
        );

        VotingProtocolService.VotingResult result =
                service.aggregate(ballots, VotingProtocolService.VotingMethod.APPROVAL);

        assertThat(result.winner()).isEqualTo("A");  // A approved 4 times
    }

    // ── Cross-method ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ranking list covers all candidates in every method")
    void aggregate_allMethods_rankingCoversAllCandidates() {
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "X", "Y", "Z"),
                ballot("v2", "Y", "Z", "X"),
                ballot("v3", "Z", "X", "Y")
        );

        for (VotingProtocolService.VotingMethod method : VotingProtocolService.VotingMethod.values()) {
            VotingProtocolService.VotingResult result = service.aggregate(ballots, method);
            assertThat(result.ranking()).containsExactlyInAnyOrder("X", "Y", "Z");
        }
    }

    @Test
    @DisplayName("empty ballot list throws IllegalArgumentException")
    void aggregate_emptyBallots_throws() {
        assertThatThrownBy(() -> service.aggregate(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("default method (Schulze) is used when no method specified")
    void aggregate_defaultMethod_isSchulze() {
        List<VotingProtocolService.RankedBallot> ballots = List.of(
                ballot("v1", "A", "B"),
                ballot("v2", "A", "B")
        );

        VotingProtocolService.VotingResult result = service.aggregate(ballots);

        assertThat(result.methodUsed()).isEqualTo(VotingProtocolService.VotingMethod.SCHULZE);
    }
}
