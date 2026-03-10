package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.council.CouncilCommitment;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit-safe view of a council commitment (#46).
 * Omits rawOutput and nonce (sensitive audit metadata).
 */
public record CouncilCommitmentDto(
        UUID id,
        String sessionType,
        String taskKey,
        String memberProfile,
        String commitHash,
        Instant committedAt,
        boolean verified,
        boolean verificationFailed
) {
    public static CouncilCommitmentDto from(CouncilCommitment c) {
        return new CouncilCommitmentDto(
                c.getId(), c.getSessionType(), c.getTaskKey(),
                c.getMemberProfile(), c.getCommitHash(), c.getCommittedAt(),
                c.isVerified(), c.isVerificationFailed());
    }
}
