package com.agentframework.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Ed25519Signer} and {@link SignedResultEnvelope} (#31 — Verifiable Compute).
 */
@DisplayName("Ed25519Signer (#31) — Verifiable Compute")
class Ed25519SignerTest {

    // ── KeyPair ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("KeyPair generation & serialisation")
    class KeyPairTests {

        @Test
        @DisplayName("generateKeyPair returns Ed25519 keys")
        void generateKeyPair_returnsEd25519() {
            KeyPair kp = Ed25519Signer.generateKeyPair();

            assertThat(kp).isNotNull();
            assertThat(kp.getPublic().getAlgorithm()).isEqualTo("EdDSA");
            assertThat(kp.getPrivate().getAlgorithm()).isEqualTo("EdDSA");
        }

        @Test
        @DisplayName("encode → decode public key: round-trip preserves key")
        void encodeDecodePublicKey_roundTrip() {
            KeyPair kp = Ed25519Signer.generateKeyPair();
            String encoded = Ed25519Signer.encodePublicKey(kp.getPublic());

            PublicKey decoded = Ed25519Signer.decodePublicKey(encoded);

            assertThat(decoded).isEqualTo(kp.getPublic());
            assertThat(decoded.getEncoded()).isEqualTo(kp.getPublic().getEncoded());
        }

        @Test
        @DisplayName("encode → decode private key: round-trip preserves key")
        void encodeDecodePrivateKey_roundTrip() {
            KeyPair kp = Ed25519Signer.generateKeyPair();
            String encoded = Ed25519Signer.encodePrivateKey(kp.getPrivate());

            PrivateKey decoded = Ed25519Signer.decodePrivateKey(encoded);

            assertThat(decoded).isEqualTo(kp.getPrivate());
            assertThat(decoded.getEncoded()).isEqualTo(kp.getPrivate().getEncoded());
        }
    }

    // ── Sign / Verify ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Sign and verify")
    class SignVerifyTests {

        private final KeyPair kp = Ed25519Signer.generateKeyPair();
        private final byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);

        @Test
        @DisplayName("valid signature verifies successfully")
        void signAndVerify_validPayload() {
            String sig = Ed25519Signer.sign(payload, kp.getPrivate());

            assertThat(Ed25519Signer.verify(payload, sig, kp.getPublic())).isTrue();
        }

        @Test
        @DisplayName("tampered payload fails verification")
        void verify_tamperedPayload_returnsFalse() {
            String sig = Ed25519Signer.sign(payload, kp.getPrivate());
            byte[] tampered = "hello WORLD".getBytes(StandardCharsets.UTF_8);

            assertThat(Ed25519Signer.verify(tampered, sig, kp.getPublic())).isFalse();
        }

        @Test
        @DisplayName("tampered signature fails verification")
        void verify_tamperedSignature_returnsFalse() {
            String sig = Ed25519Signer.sign(payload, kp.getPrivate());
            // Flip a character in the Base64 signature
            char[] chars = sig.toCharArray();
            chars[0] = chars[0] == 'A' ? 'B' : 'A';
            String tampered = new String(chars);

            assertThat(Ed25519Signer.verify(payload, tampered, kp.getPublic())).isFalse();
        }

        @Test
        @DisplayName("wrong key fails verification")
        void verify_wrongKey_returnsFalse() {
            KeyPair otherKp = Ed25519Signer.generateKeyPair();
            String sig = Ed25519Signer.sign(payload, kp.getPrivate());

            assertThat(Ed25519Signer.verify(payload, sig, otherKp.getPublic())).isFalse();
        }
    }

    // ── SignedResultEnvelope ─────────────────────────────────────────────────

    @Nested
    @DisplayName("SignedResultEnvelope")
    class EnvelopeTests {

        private final KeyPair kp = Ed25519Signer.generateKeyPair();
        private final String resultJson = "{\"taskKey\":\"BE-001\",\"status\":\"COMPLETED\"}";

        @Test
        @DisplayName("sign creates a valid, self-verifying envelope")
        void sign_createsValidEnvelope() {
            SignedResultEnvelope envelope = SignedResultEnvelope.sign(
                    resultJson, kp.getPrivate(), kp.getPublic());

            assertThat(envelope.resultJson()).isEqualTo(resultJson);
            assertThat(envelope.workerSignature()).isNotBlank();
            assertThat(envelope.workerPublicKey()).isNotBlank();
            assertThat(envelope.signedAt()).isNotBlank();
            // TOFU verification (null trusted key → uses embedded key)
            assertThat(envelope.verify(null)).isTrue();
        }

        @Test
        @DisplayName("tampered resultJson fails verification")
        void verify_tamperedResultJson_fails() {
            SignedResultEnvelope envelope = SignedResultEnvelope.sign(
                    resultJson, kp.getPrivate(), kp.getPublic());

            // Create a tampered envelope with modified resultJson
            SignedResultEnvelope tampered = new SignedResultEnvelope(
                    "{\"taskKey\":\"BE-001\",\"status\":\"FAILED\"}",
                    envelope.workerSignature(),
                    envelope.workerPublicKey(),
                    envelope.signedAt()
            );

            assertThat(tampered.verify(null)).isFalse();
        }

        @Test
        @DisplayName("tampered signedAt fails verification")
        void verify_tamperedSignedAt_fails() {
            SignedResultEnvelope envelope = SignedResultEnvelope.sign(
                    resultJson, kp.getPrivate(), kp.getPublic());

            SignedResultEnvelope tampered = new SignedResultEnvelope(
                    envelope.resultJson(),
                    envelope.workerSignature(),
                    envelope.workerPublicKey(),
                    "2025-01-01T00:00:00Z"  // different timestamp
            );

            assertThat(tampered.verify(null)).isFalse();
        }

        @Test
        @DisplayName("verify with trusted key succeeds")
        void verify_withTrustedKey_succeeds() {
            SignedResultEnvelope envelope = SignedResultEnvelope.sign(
                    resultJson, kp.getPrivate(), kp.getPublic());

            // Verify with the same key passed explicitly (not from envelope)
            assertThat(envelope.verify(kp.getPublic())).isTrue();
        }

        @Test
        @DisplayName("verify with wrong trusted key fails")
        void verify_withWrongTrustedKey_fails() {
            SignedResultEnvelope envelope = SignedResultEnvelope.sign(
                    resultJson, kp.getPrivate(), kp.getPublic());

            KeyPair otherKp = Ed25519Signer.generateKeyPair();
            assertThat(envelope.verify(otherKp.getPublic())).isFalse();
        }
    }
}
