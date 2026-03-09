package com.agentframework.common.crypto;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 digital signature utility — keygen, sign, verify, key serialisation.
 *
 * <p>Pure Java 21 implementation using {@code java.security} EdDSA support
 * (available since Java 15). No external dependencies (no BouncyCastle).</p>
 *
 * <p>Stateless utility class (like {@link com.agentframework.common.util.HashUtil}).
 * All methods are static and thread-safe.</p>
 *
 * <p>Used by:</p>
 * <ul>
 *   <li>Worker-SDK: signs {@code AgentResult} before publishing to Redis Streams</li>
 *   <li>Orchestrator: verifies signature on received results (tamper detection)</li>
 * </ul>
 *
 * @see SignedResultEnvelope
 */
public final class Ed25519Signer {

    private static final String ALGORITHM = "Ed25519";

    private Ed25519Signer() {}

    /**
     * Generates a new Ed25519 key pair.
     *
     * @return a fresh Ed25519 key pair (32-byte public key, 64-byte private key)
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not available in this JVM", e);
        }
    }

    /**
     * Signs a payload with an Ed25519 private key.
     *
     * @param payload    the bytes to sign
     * @param privateKey Ed25519 private key
     * @return Base64-encoded signature (88 characters for Ed25519's 64-byte signature)
     * @throws IllegalArgumentException if payload or key is null
     */
    public static String sign(byte[] payload, PrivateKey privateKey) {
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        if (privateKey == null) throw new IllegalArgumentException("privateKey must not be null");

        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(privateKey);
            sig.update(payload);
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not available", e);
        } catch (InvalidKeyException | SignatureException e) {
            throw new IllegalArgumentException("Signing failed", e);
        }
    }

    /**
     * Verifies an Ed25519 signature.
     *
     * @param payload      the original bytes that were signed
     * @param signatureB64 Base64-encoded signature to verify
     * @param publicKey    Ed25519 public key
     * @return {@code true} if the signature is valid
     */
    public static boolean verify(byte[] payload, String signatureB64, PublicKey publicKey) {
        if (payload == null || signatureB64 == null || publicKey == null) return false;

        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(payload);
            return sig.verify(Base64.getDecoder().decode(signatureB64));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not available", e);
        } catch (InvalidKeyException | SignatureException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Serialises an Ed25519 public key to Base64 (X.509/SubjectPublicKeyInfo encoding).
     *
     * @param key the public key
     * @return Base64-encoded key
     */
    public static String encodePublicKey(PublicKey key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * Deserialises a Base64-encoded Ed25519 public key.
     *
     * @param base64 the Base64-encoded X.509 key
     * @return the reconstructed public key
     */
    public static PublicKey decodePublicKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("base64 must not be null or blank");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Ed25519 public key", e);
        }
    }

    /**
     * Serialises an Ed25519 private key to Base64 (PKCS#8 encoding).
     *
     * @param key the private key
     * @return Base64-encoded key
     */
    public static String encodePrivateKey(PrivateKey key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * Deserialises a Base64-encoded Ed25519 private key.
     *
     * @param base64 the Base64-encoded PKCS#8 key
     * @return the reconstructed private key
     */
    public static PrivateKey decodePrivateKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("base64 must not be null or blank");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Ed25519 private key", e);
        }
    }
}
