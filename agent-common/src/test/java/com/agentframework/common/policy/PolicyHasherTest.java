package com.agentframework.common.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PolicyHasher} (#32 — Policy-as-Code Immutabile).
 */
@DisplayName("PolicyHasher (#32) — Canonical JSON + SHA-256")
class PolicyHasherTest {

    private static final HookPolicy SAMPLE = new HookPolicy(
            List.of("fs_read", "fs_write"), List.of("backend/"), List.of("repo-fs"),
            true, 50000, List.of("api.github.com"),
            ApprovalMode.NONE, 0, RiskLevel.LOW, 30000, false
    );

    @Test
    @DisplayName("null policy → null hash")
    void hash_nullPolicy_returnsNull() {
        assertThat(PolicyHasher.hash(null)).isNull();
    }

    @Test
    @DisplayName("same policy produces same hash (deterministic)")
    void hash_deterministic_samePolicySameHash() {
        HookPolicy p1 = new HookPolicy(
                List.of("fs_read"), List.of("src/"), List.of(),
                true, null, List.of(), ApprovalMode.NONE, 0, RiskLevel.LOW, null, false);
        HookPolicy p2 = new HookPolicy(
                List.of("fs_read"), List.of("src/"), List.of(),
                true, null, List.of(), ApprovalMode.NONE, 0, RiskLevel.LOW, null, false);

        assertThat(PolicyHasher.hash(p1)).isEqualTo(PolicyHasher.hash(p2));
    }

    @Test
    @DisplayName("list order does not affect hash (sorted canonicalization)")
    void hash_deterministicRegardlessOfListOrder() {
        HookPolicy toolsAB = new HookPolicy(
                List.of("fs_read", "fs_write"), List.of(), List.of(),
                true, null, List.of(), null, 0, null, null, false);
        HookPolicy toolsBA = new HookPolicy(
                List.of("fs_write", "fs_read"), List.of(), List.of(),
                true, null, List.of(), null, 0, null, null, false);

        assertThat(PolicyHasher.hash(toolsAB)).isEqualTo(PolicyHasher.hash(toolsBA));
    }

    @Test
    @DisplayName("different policies produce different hashes")
    void hash_differentPolicies_differentHashes() {
        HookPolicy withAudit = new HookPolicy(
                List.of(), List.of(), List.of(),
                true, null, List.of(), null, 0, null, null, false);
        HookPolicy withoutAudit = new HookPolicy(
                List.of(), List.of(), List.of(),
                false, null, List.of(), null, 0, null, null, false);

        assertThat(PolicyHasher.hash(withAudit)).isNotEqualTo(PolicyHasher.hash(withoutAudit));
    }

    @Test
    @DisplayName("verify returns true for matching hash")
    void verify_matching_returnsTrue() {
        String hash = PolicyHasher.hash(SAMPLE);
        assertThat(PolicyHasher.verify(SAMPLE, hash)).isTrue();
    }

    @Test
    @DisplayName("verify returns false for mismatched hash")
    void verify_mismatch_returnsFalse() {
        assertThat(PolicyHasher.verify(SAMPLE, "0000000000000000000000000000000000000000000000000000000000000000"))
                .isFalse();
    }

    @Test
    @DisplayName("toCanonicalJson produces expected output (golden test)")
    void toCanonicalJson_goldenTest() {
        HookPolicy p = new HookPolicy(
                List.of("fs_write", "fs_read"), List.of("src/", "docs/"), List.of("mcp-a"),
                true, 10000, List.of(),
                ApprovalMode.BLOCK, 5, RiskLevel.HIGH, 5000, true
        );

        String json = PolicyHasher.toCanonicalJson(p);

        // Keys alphabetical, lists sorted, no whitespace
        assertThat(json).isEqualTo(
                "{\"allowedMcpServers\":[\"mcp-a\"],"
                + "\"allowedNetworkHosts\":[],"
                + "\"allowedTools\":[\"fs_read\",\"fs_write\"],"
                + "\"approvalTimeoutMinutes\":5,"
                + "\"auditEnabled\":true,"
                + "\"estimatedTokens\":5000,"
                + "\"maxTokenBudget\":10000,"
                + "\"ownedPaths\":[\"docs/\",\"src/\"],"
                + "\"requiredHumanApproval\":\"BLOCK\","
                + "\"riskLevel\":\"HIGH\","
                + "\"shouldSnapshot\":true}"
        );
    }
}
