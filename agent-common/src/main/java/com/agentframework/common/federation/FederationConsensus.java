package com.agentframework.common.federation;

import java.util.concurrent.CompletableFuture;

/**
 * SPI for federated consensus on critical operations.
 *
 * <p>Certain operations in a federated cluster require agreement from multiple
 * servers before proceeding. Examples:</p>
 * <ul>
 *   <li>Plan cancellation — all servers executing tasks for the plan must agree</li>
 *   <li>Policy override — security-sensitive changes require quorum</li>
 *   <li>Server eviction — removing a misbehaving peer from the federation</li>
 * </ul>
 *
 * <p>The consensus protocol is asynchronous: {@link #propose} returns a
 * {@link CompletableFuture} that completes when the quorum is reached
 * (or times out). The implementation details (Raft, PBFT, simple majority)
 * are left to future providers.</p>
 *
 * <p>No concrete implementation exists yet. This interface establishes the
 * contract for future federation providers.</p>
 */
public interface FederationConsensus {

    /**
     * Proposes an action that requires federated consensus.
     *
     * <p>The proposal is broadcast to all peers. Each peer votes approve or
     * reject. The future completes when the quorum threshold is met.</p>
     *
     * @param action      semantic action identifier (e.g. "cancel-plan", "override-policy")
     * @param payloadJson JSON-encoded details of the proposed action
     * @return future that resolves to the consensus outcome
     */
    CompletableFuture<ConsensusResult> propose(String action, String payloadJson);
}
