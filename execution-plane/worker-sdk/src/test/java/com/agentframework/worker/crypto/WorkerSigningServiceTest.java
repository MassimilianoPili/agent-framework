package com.agentframework.worker.crypto;

import com.agentframework.common.crypto.Ed25519Signer;
import com.agentframework.common.crypto.SignedResultEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WorkerSigningService} (#31 — Verifiable Compute, worker-side signing).
 */
@DisplayName("WorkerSigningService (#31)")
class WorkerSigningServiceTest {

    @Test
    @DisplayName("sign with enabled service produces valid envelope")
    void sign_enabled_producesValidEnvelope() {
        WorkerCryptoProperties props = new WorkerCryptoProperties(true, null, null);
        WorkerSigningService service = new WorkerSigningService(props);

        String resultJson = "{\"taskKey\":\"BE-001\",\"success\":true}";
        SignedResultEnvelope envelope = service.sign(resultJson);

        assertThat(envelope).isNotNull();
        assertThat(envelope.resultJson()).isEqualTo(resultJson);
        assertThat(envelope.workerSignature()).isNotBlank();
        assertThat(envelope.workerPublicKey()).isNotBlank();
        assertThat(envelope.verify(null)).isTrue(); // TOFU self-verification
    }

    @Test
    @DisplayName("sign with disabled service returns null")
    void sign_disabled_returnsNull() {
        WorkerCryptoProperties props = new WorkerCryptoProperties(false, null, null);
        WorkerSigningService service = new WorkerSigningService(props);

        SignedResultEnvelope envelope = service.sign("{\"taskKey\":\"BE-001\"}");

        assertThat(envelope).isNull();
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("sign with configured keys uses provided keys")
    void sign_withConfiguredKeys_usesProvidedKeys() {
        KeyPair kp = Ed25519Signer.generateKeyPair();
        String privB64 = Ed25519Signer.encodePrivateKey(kp.getPrivate());
        String pubB64 = Ed25519Signer.encodePublicKey(kp.getPublic());

        WorkerCryptoProperties props = new WorkerCryptoProperties(true, privB64, pubB64);
        WorkerSigningService service = new WorkerSigningService(props);

        assertThat(service.getPublicKeyBase64()).isEqualTo(pubB64);

        SignedResultEnvelope envelope = service.sign("{\"taskKey\":\"test\"}");
        assertThat(envelope).isNotNull();
        assertThat(envelope.workerPublicKey()).isEqualTo(pubB64);
        // Verify against the known public key
        assertThat(envelope.verify(kp.getPublic())).isTrue();
    }

    @Test
    @DisplayName("sign without keys generates ephemeral keypair")
    void sign_withoutKeys_generatesEphemeral() {
        WorkerCryptoProperties props = new WorkerCryptoProperties(true, null, null);
        WorkerSigningService service = new WorkerSigningService(props);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.getPublicKeyBase64()).isNotBlank();

        SignedResultEnvelope envelope = service.sign("{\"taskKey\":\"ephemeral-test\"}");
        assertThat(envelope).isNotNull();
        assertThat(envelope.verify(null)).isTrue();
    }
}
