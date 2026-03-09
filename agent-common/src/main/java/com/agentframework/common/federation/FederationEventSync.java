package com.agentframework.common.federation;

import java.util.List;
import java.util.UUID;

/**
 * SPI for synchronising plan events between federated servers.
 *
 * <p>The event sync protocol leverages the hash chain established by feature #30
 * to enable efficient Merkle-tree reconciliation:</p>
 * <ol>
 *   <li>Each server maintains a Merkle root over its local event sequence.</li>
 *   <li>On sync, peers compare roots; if they diverge, only the differing
 *       subtrees are exchanged via {@link #requestMissing}.</li>
 *   <li>Received events are verified (Ed25519 signature from the sender's
 *       {@link ServerIdentity#publicKeyBase64()}) before merging into the
 *       local sequence.</li>
 * </ol>
 *
 * <p><strong>Conflict resolution</strong>: plan item status uses CRDT semantics —
 * {@code max(status_A, status_B)} with the ordering
 * {@code WAITING < DISPATCHED < DONE < FAILED}. This guarantees convergence
 * without semantic conflicts.</p>
 *
 * <p>No concrete implementation exists yet. This interface establishes the
 * contract for future federation providers.</p>
 */
public interface FederationEventSync {

    /**
     * Broadcasts a local event to all federated peers.
     *
     * @param event     the event to broadcast
     * @param signature Ed25519 signature of the event hash, proving origin authenticity
     */
    void broadcast(FederatedEvent event, String signature);

    /**
     * Receives an event from a federated peer.
     *
     * <p>Implementations must verify the signature against the sender's
     * registered public key before merging the event into the local store.</p>
     *
     * @param event     the received event
     * @param signature Ed25519 signature from the sender
     * @param sender    identity of the originating server
     */
    void receive(FederatedEvent event, String signature, ServerIdentity sender);

    /**
     * Requests events missing from the local sequence (late-join or reconnection).
     *
     * <p>The peer returns all events for the given plan with sequence numbers
     * strictly greater than {@code fromSequence}.</p>
     *
     * @param planId       the plan whose events are needed
     * @param fromSequence the last known sequence number (exclusive)
     * @param peer         the server to request from
     * @return ordered list of missing events
     */
    List<FederatedEvent> requestMissing(UUID planId, long fromSequence, ServerIdentity peer);
}
