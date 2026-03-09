package com.agentframework.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;

/**
 * Signed envelope wrapping an {@code AgentResult} JSON payload with Ed25519 signature.
 *
 * <p>The signed payload is deterministic: {@code resultJson + "|" + signedAt}.
 * The {@code "|"} separator is safe because {@code signedAt} is ISO-8601
 * (no {@code "|"} character) and the payload boundary is unambiguous.</p>
 *
 * <p>Verification modes:</p>
 * <ul>
 *   <li><b>TOFU (Trust-On-First-Use)</b>: pass {@code null} as trusted key — uses
 *       the embedded {@code workerPublicKey}. Suitable for key discovery.</li>
 *   <li><b>Registered key</b>: pass the pre-registered public key — ignores the
 *       embedded key. Required for production deployments.</li>
 * </ul>
 *
 * @param resultJson      the serialised AgentResult (JSON string)
 * @param workerSignature Ed25519 signature, Base64-encoded
 * @param workerPublicKey Ed25519 public key, Base64-encoded (for key discovery)
 * @param signedAt        ISO-8601 timestamp when the signature was created
 */
public record SignedResultEnvelope(
        String resultJson,
        String workerSignature,
        String workerPublicKey,
        String signedAt
) {

    /**
     * Creates a signed envelope from a result JSON payload.
     *
     * @param resultJson the AgentResult serialised as JSON
     * @param privateKey the worker's Ed25519 private key
     * @param publicKey  the worker's Ed25519 public key (embedded for discovery)
     * @return a new signed envelope
     */
    public static SignedResultEnvelope sign(String resultJson, PrivateKey privateKey, PublicKey publicKey) {
        if (resultJson == null) throw new IllegalArgumentException("resultJson must not be null");
        if (privateKey == null) throw new IllegalArgumentException("privateKey must not be null");
        if (publicKey == null) throw new IllegalArgumentException("publicKey must not be null");

        String signedAt = Instant.now().toString();
        String payload = resultJson + "|" + signedAt;
        String signature = Ed25519Signer.sign(payload.getBytes(StandardCharsets.UTF_8), privateKey);
        String pubKeyB64 = Ed25519Signer.encodePublicKey(publicKey);

        return new SignedResultEnvelope(resultJson, signature, pubKeyB64, signedAt);
    }

    /**
     * Verifies the envelope's signature.
     *
     * @param trustedPublicKey pre-registered public key; if {@code null}, uses
     *                         the embedded {@link #workerPublicKey} (TOFU mode)
     * @return {@code true} if the signature is valid against the resolved key
     */
    public boolean verify(PublicKey trustedPublicKey) {
        PublicKey key = trustedPublicKey != null
                ? trustedPublicKey
                : Ed25519Signer.decodePublicKey(workerPublicKey);

        byte[] payload = signedPayload().getBytes(StandardCharsets.UTF_8);
        return Ed25519Signer.verify(payload, workerSignature, key);
    }

    /**
     * Reconstructs the deterministic payload that was signed.
     *
     * @return {@code resultJson + "|" + signedAt}
     */
    String signedPayload() {
        return resultJson + "|" + signedAt;
    }
}
