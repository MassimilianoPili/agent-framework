package com.agentframework.orchestrator.eventsourcing;

/**
 * Result of a hash chain integrity verification for a plan's event log (#30).
 *
 * <p>Produced by {@link HashChainVerifier#verify(java.util.UUID)}.
 * If {@code valid} is {@code false}, {@code brokenAtSequence} indicates the first
 * event where the chain breaks and {@code reason} describes the mismatch.</p>
 *
 * @param valid             true if the entire chain is consistent
 * @param eventCount        number of events verified (-1 if broken before full scan)
 * @param brokenAtSequence  sequence number of the first broken event, or null if valid
 * @param reason            human-readable description of the break, or null if valid
 */
public record HashChainVerificationResult(
        boolean valid,
        int eventCount,
        Long brokenAtSequence,
        String reason
) {
    public static HashChainVerificationResult valid(int count) {
        return new HashChainVerificationResult(true, count, null, null);
    }

    public static HashChainVerificationResult empty() {
        return new HashChainVerificationResult(true, 0, null, null);
    }

    public static HashChainVerificationResult broken(long seq, String reason) {
        return new HashChainVerificationResult(false, -1, seq, reason);
    }
}
