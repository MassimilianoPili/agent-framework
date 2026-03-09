package com.agentframework.common.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PolicyLattice} — meet-semilattice properties and field composition (#39).
 */
@DisplayName("PolicyLattice (#39) — meet-semilattice")
class PolicyLatticeTest {

    // ── Test fixtures ──────────────────────────────────────────────────────────

    private static final HookPolicy POLICY_A = new HookPolicy(
            List.of("Read", "Write", "Bash"), List.of("/src", "/test"), List.of("git", "docker"), true,
            5000, List.of("api.github.com", "registry.npmjs.org"), ApprovalMode.NONE, 30,
            RiskLevel.MEDIUM, 2000, false);

    private static final HookPolicy POLICY_B = new HookPolicy(
            List.of("Read", "Grep", "Bash"), List.of("/src", "/docs"), List.of("git", "slack"), false,
            3000, List.of("api.github.com", "slack.com"), ApprovalMode.BLOCK, 10,
            RiskLevel.HIGH, 4000, true);

    private static final HookPolicy POLICY_C = new HookPolicy(
            List.of("Read"), List.of("/src"), List.of("git"), true,
            8000, List.of("api.github.com"), ApprovalMode.NOTIFY_TIMEOUT, 60,
            RiskLevel.LOW, 1000, false);

    // ── Algebraic properties ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Lattice axioms")
    class LatticeAxioms {

        @Test
        @DisplayName("meet(a, a) == a — idempotence")
        void meet_idempotent() {
            assertThat(PolicyLattice.meet(POLICY_A, POLICY_A)).isEqualTo(POLICY_A);
            assertThat(PolicyLattice.meet(POLICY_B, POLICY_B)).isEqualTo(POLICY_B);
        }

        @Test
        @DisplayName("meet(a, b) == meet(b, a) — commutativity")
        void meet_commutative() {
            assertThat(PolicyLattice.meet(POLICY_A, POLICY_B))
                    .isEqualTo(PolicyLattice.meet(POLICY_B, POLICY_A));
        }

        @Test
        @DisplayName("meet(meet(a, b), c) == meet(a, meet(b, c)) — associativity")
        void meet_associative() {
            HookPolicy left = PolicyLattice.meet(PolicyLattice.meet(POLICY_A, POLICY_B), POLICY_C);
            HookPolicy right = PolicyLattice.meet(POLICY_A, PolicyLattice.meet(POLICY_B, POLICY_C));
            assertThat(left).isEqualTo(right);
        }
    }

    // ── TOP and BOTTOM ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Lattice constants")
    class LatticeConstants {

        @Test
        @DisplayName("meet(TOP, a) == a — TOP is identity")
        void top_isIdentity() {
            assertThat(PolicyLattice.meet(PolicyLattice.TOP, POLICY_A)).isEqualTo(POLICY_A);
            assertThat(PolicyLattice.meet(POLICY_B, PolicyLattice.TOP)).isEqualTo(POLICY_B);
        }

        @Test
        @DisplayName("meet(BOTTOM, a) == BOTTOM — BOTTOM is absorbing")
        void bottom_isAbsorbing() {
            assertThat(PolicyLattice.meet(PolicyLattice.BOTTOM, POLICY_A))
                    .isEqualTo(PolicyLattice.BOTTOM);
            assertThat(PolicyLattice.meet(POLICY_B, PolicyLattice.BOTTOM))
                    .isEqualTo(PolicyLattice.BOTTOM);
        }
    }

    // ── Field-level composition ────────────────────────────────────────────────

    @Nested
    @DisplayName("Field composition rules")
    class FieldComposition {

        @Test
        @DisplayName("List fields are intersected")
        void meet_intersectsLists() {
            HookPolicy result = PolicyLattice.meet(POLICY_A, POLICY_B);

            // A: [Read, Write, Bash] ∩ B: [Read, Grep, Bash] = [Read, Bash]
            assertThat(result.allowedTools()).containsExactlyInAnyOrder("Read", "Bash");
            // A: [/src, /test] ∩ B: [/src, /docs] = [/src]
            assertThat(result.ownedPaths()).containsExactly("/src");
            // A: [git, docker] ∩ B: [git, slack] = [git]
            assertThat(result.allowedMcpServers()).containsExactly("git");
            // A: [api.github.com, registry.npmjs.org] ∩ B: [api.github.com, slack.com] = [api.github.com]
            assertThat(result.allowedNetworkHosts()).containsExactly("api.github.com");
        }

        @Test
        @DisplayName("Boolean fields use OR (more restrictive = true)")
        void meet_booleanOr() {
            HookPolicy result = PolicyLattice.meet(POLICY_A, POLICY_B);

            // A: audit=true, snapshot=false | B: audit=false, snapshot=true
            assertThat(result.auditEnabled()).isTrue();     // true || false
            assertThat(result.shouldSnapshot()).isTrue();    // false || true
        }

        @Test
        @DisplayName("Nullable integers use min (null = no limit)")
        void meet_minNullable() {
            HookPolicy result = PolicyLattice.meet(POLICY_A, POLICY_B);

            // A: maxTokenBudget=5000, B: 3000 → min = 3000
            assertThat(result.maxTokenBudget()).isEqualTo(3000);
            // A: estimatedTokens=2000, B: 4000 → min = 2000
            assertThat(result.estimatedTokens()).isEqualTo(2000);
        }

        @Test
        @DisplayName("Nullable min: null means no limit")
        void meet_minNullable_withNull() {
            HookPolicy noLimit = new HookPolicy(
                    List.of("Read"), List.of(), List.of(), false,
                    null, List.of(), ApprovalMode.NONE, 30,
                    RiskLevel.LOW, null, false);

            HookPolicy result = PolicyLattice.meet(POLICY_A, noLimit);

            // A: 5000, noLimit: null → 5000 (null = infinity, min(5000, ∞) = 5000)
            assertThat(result.maxTokenBudget()).isEqualTo(5000);
            assertThat(result.estimatedTokens()).isEqualTo(2000);
        }

        @Test
        @DisplayName("RiskLevel uses max ordinal (CRITICAL > HIGH > MEDIUM > LOW)")
        void meet_maxEnum_riskLevel() {
            HookPolicy result = PolicyLattice.meet(POLICY_A, POLICY_B);

            // A: MEDIUM, B: HIGH → max = HIGH
            assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        }

        @Test
        @DisplayName("ApprovalMode uses max ordinal (BLOCK > NOTIFY_TIMEOUT > NONE)")
        void meet_maxEnum_approvalMode() {
            HookPolicy result = PolicyLattice.meet(POLICY_A, POLICY_B);

            // A: NONE, B: BLOCK → max = BLOCK
            assertThat(result.requiredHumanApproval()).isEqualTo(ApprovalMode.BLOCK);
        }

        @Test
        @DisplayName("approvalTimeoutMinutes uses min")
        void meet_minTimeout() {
            HookPolicy result = PolicyLattice.meet(POLICY_A, POLICY_B);

            // A: 30, B: 10 → min = 10
            assertThat(result.approvalTimeoutMinutes()).isEqualTo(10);
        }
    }

    // ── Compose ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Multi-source composition")
    class Compose {

        @Test
        @DisplayName("compose(a, b, c) equals iterated meet")
        void compose_multiSource() {
            HookPolicy composed = PolicyLattice.compose(POLICY_A, POLICY_B, POLICY_C);
            HookPolicy iterated = PolicyLattice.meet(PolicyLattice.meet(POLICY_A, POLICY_B), POLICY_C);

            assertThat(composed).isEqualTo(iterated);
        }

        @Test
        @DisplayName("compose skips null policies")
        void compose_skipsNull() {
            HookPolicy withNull = PolicyLattice.compose(POLICY_A, null, POLICY_B);
            HookPolicy withoutNull = PolicyLattice.meet(POLICY_A, POLICY_B);

            assertThat(withNull).isEqualTo(withoutNull);
        }

        @Test
        @DisplayName("compose with no args returns TOP")
        void compose_empty_returnsTop() {
            assertThat(PolicyLattice.compose()).isEqualTo(PolicyLattice.TOP);
        }
    }

    // ── Wildcard handling ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Wildcard list handling")
    class WildcardHandling {

        @Test
        @DisplayName("intersect([\"*\"], x) returns x — wildcard is TOP for lists")
        void meet_wildcardList_returnsOther() {
            HookPolicy wildcard = new HookPolicy(
                    List.of("*"), List.of("*"), List.of("*"), false,
                    null, List.of("*"), ApprovalMode.NONE, 60,
                    RiskLevel.LOW, null, false);

            HookPolicy result = PolicyLattice.meet(wildcard, POLICY_A);

            assertThat(result.allowedTools()).isEqualTo(POLICY_A.allowedTools());
            assertThat(result.ownedPaths()).isEqualTo(POLICY_A.ownedPaths());
        }

        @Test
        @DisplayName("intersect([\"*\"], [\"*\"]) returns [\"*\"]")
        void meet_bothWildcard_returnsWildcard() {
            List<String> result = PolicyLattice.intersect(List.of("*"), List.of("*"));
            assertThat(result).containsExactly("*");
        }
    }
}
