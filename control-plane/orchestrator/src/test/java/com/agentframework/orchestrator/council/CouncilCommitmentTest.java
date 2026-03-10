package com.agentframework.orchestrator.council;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CouncilCommitment} (#46 — Verifiable Council Deliberation).
 */
@DisplayName("CouncilCommitment (#46) — Commit-reveal scheme")
class CouncilCommitmentTest {

    private static final UUID PLAN_ID = UUID.randomUUID();

    @Test
    @DisplayName("create() produces a valid hash that passes verify()")
    void create_producesValidHash() {
        CouncilCommitment c = CouncilCommitment.create(
                PLAN_ID, "PRE_PLANNING", null, "be-manager", "Use Spring Boot 3.4");

        assertThat(c.getCommitHash()).isNotNull().hasSize(64);
        assertThat(c.getNonce()).isNotNull();
        assertThat(c.verify()).isTrue();
        assertThat(c.isVerified()).isTrue();
        assertThat(c.isVerificationFailed()).isFalse();
    }

    @Test
    @DisplayName("verify() fails when rawOutput is tampered")
    void verify_tamperedOutput() {
        CouncilCommitment c = CouncilCommitment.create(
                PLAN_ID, "PRE_PLANNING", null, "security-specialist", "Use OAuth2");

        // Tamper with the output
        c.setRawOutput("Use basic auth instead");

        assertThat(c.verify()).isFalse();
        assertThat(c.isVerified()).isFalse();
        assertThat(c.isVerificationFailed()).isTrue();
    }

    @Test
    @DisplayName("verify() fails when nonce is tampered")
    void verify_tamperedNonce() {
        CouncilCommitment c = CouncilCommitment.create(
                PLAN_ID, "TASK", "CM_001", "be-manager", "Implement REST API");

        // Tamper with the nonce
        c.setNonce(UUID.randomUUID());

        assertThat(c.verify()).isFalse();
        assertThat(c.isVerificationFailed()).isTrue();
    }

    @Test
    @DisplayName("verify() is idempotent — double verify still returns true")
    void verify_idempotent() {
        CouncilCommitment c = CouncilCommitment.create(
                PLAN_ID, "PRE_PLANNING", null, "fe-manager", "Use React 19");

        assertThat(c.verify()).isTrue();
        assertThat(c.verify()).isTrue();
        assertThat(c.isVerified()).isTrue();
        assertThat(c.isVerificationFailed()).isFalse();
    }

    @Test
    @DisplayName("same output with different nonces produces different commitHashes")
    void differentNonces_differentHashes() {
        String sameOutput = "Use PostgreSQL for persistence";

        CouncilCommitment c1 = CouncilCommitment.create(
                PLAN_ID, "PRE_PLANNING", null, "database-specialist", sameOutput);
        CouncilCommitment c2 = CouncilCommitment.create(
                PLAN_ID, "PRE_PLANNING", null, "database-specialist", sameOutput);

        // Different nonces → different hashes (with overwhelming probability)
        assertThat(c1.getCommitHash()).isNotEqualTo(c2.getCommitHash());
        // But both verify
        assertThat(c1.verify()).isTrue();
        assertThat(c2.verify()).isTrue();
    }

    @Test
    @DisplayName("verify() sets correct flags on success and failure")
    void verify_setsFlags() {
        CouncilCommitment c = CouncilCommitment.create(
                PLAN_ID, "TASK", "BE_001", "be-manager", "Original output");

        // Before verification
        assertThat(c.isVerified()).isFalse();
        assertThat(c.isVerificationFailed()).isFalse();

        // Successful verification
        c.verify();
        assertThat(c.isVerified()).isTrue();
        assertThat(c.isVerificationFailed()).isFalse();

        // Tamper and re-verify
        c.setRawOutput("Tampered output");
        c.verify();
        assertThat(c.isVerified()).isFalse();
        assertThat(c.isVerificationFailed()).isTrue();
    }
}
