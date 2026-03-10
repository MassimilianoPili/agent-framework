package com.agentframework.orchestrator.council;

import com.agentframework.common.util.HashUtil;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Commit-reveal record for a council member's output (#46).
 *
 * <p>The commit-reveal scheme guarantees verifiable deliberation:
 * <ol>
 *   <li><b>Commit</b>: {@code commitHash = SHA-256(rawOutput + "|" + nonce)}</li>
 *   <li><b>Reveal</b>: Before synthesis, {@link #verify()} recomputes the hash and checks equality</li>
 * </ol>
 *
 * <p>A verification failure indicates that the stored output was modified after commit,
 * which is a security event. Failed members are excluded from synthesis.</p>
 */
@Entity
@Table(name = "council_commitments")
public class CouncilCommitment {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "session_type", nullable = false, length = 20)
    private String sessionType;

    @Column(name = "task_key", length = 20)
    private String taskKey;

    @Column(name = "member_profile", nullable = false, length = 100)
    private String memberProfile;

    @Column(name = "commit_hash", nullable = false, length = 64)
    private String commitHash;

    @Column(nullable = false)
    private UUID nonce;

    @Column(name = "raw_output", nullable = false, columnDefinition = "TEXT")
    private String rawOutput;

    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "verification_failed", nullable = false)
    private boolean verificationFailed = false;

    protected CouncilCommitment() {}

    /**
     * Factory method: creates a new commitment with auto-generated nonce and commitHash.
     */
    public static CouncilCommitment create(UUID planId, String sessionType, String taskKey,
                                            String memberProfile, String rawOutput) {
        CouncilCommitment c = new CouncilCommitment();
        c.id = UUID.randomUUID();
        c.planId = planId;
        c.sessionType = sessionType;
        c.taskKey = taskKey;
        c.memberProfile = memberProfile;
        c.rawOutput = rawOutput;
        c.nonce = UUID.randomUUID();
        c.commitHash = HashUtil.sha256(rawOutput + "|" + c.nonce);
        c.committedAt = Instant.now();
        return c;
    }

    /**
     * Verifies the commitment by recomputing the hash and comparing with the stored commitHash.
     * Sets {@code verified} or {@code verificationFailed} flags accordingly.
     *
     * @return true if the hash matches, false otherwise
     */
    public boolean verify() {
        String recomputed = HashUtil.sha256(rawOutput + "|" + nonce);
        if (commitHash.equals(recomputed)) {
            this.verified = true;
            this.verificationFailed = false;
            return true;
        } else {
            this.verified = false;
            this.verificationFailed = true;
            return false;
        }
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public String getSessionType() { return sessionType; }
    public String getTaskKey() { return taskKey; }
    public String getMemberProfile() { return memberProfile; }
    public String getCommitHash() { return commitHash; }
    public UUID getNonce() { return nonce; }
    public String getRawOutput() { return rawOutput; }
    public Instant getCommittedAt() { return committedAt; }
    public boolean isVerified() { return verified; }
    public boolean isVerificationFailed() { return verificationFailed; }

    // ── Test-only setters (package-private) ─────────────────────────────────

    void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }
    void setNonce(UUID nonce) { this.nonce = nonce; }
}
