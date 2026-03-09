package com.agentframework.common.federation;

import java.time.Instant;

/**
 * Cryptographic identity of a server in a federated cluster.
 *
 * <p>Each server holds an Ed25519 key pair (see
 * {@link com.agentframework.common.crypto.Ed25519Signer}). The public key
 * is shared with peers during registration and used for mTLS authentication
 * and event signature verification.</p>
 *
 * @param serverId            stable UUID identifying this server instance
 * @param displayName         human-readable label (e.g. "SOL-1", "SOL-2")
 * @param publicKeyBase64     Ed25519 public key, Base64-encoded
 *                            (compatible with {@code Ed25519Signer.encodePublicKey()})
 * @param federationEndpoint  HTTPS URL for the federation API
 *                            (e.g. {@code https://sol-2.example.com/api/v1/federation})
 * @param registeredAt        timestamp when this server joined the federation
 */
public record ServerIdentity(
        String serverId,
        String displayName,
        String publicKeyBase64,
        String federationEndpoint,
        Instant registeredAt
) {}
