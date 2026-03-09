package com.agentframework.worker.crypto;

import com.agentframework.common.crypto.Ed25519Signer;
import com.agentframework.common.crypto.SignedResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Manages an Ed25519 keypair and signs worker results before publication (#31).
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>At construction, loads keys from {@link WorkerCryptoProperties} or generates
 *       an ephemeral keypair (dev mode — logs warning).</li>
 *   <li>{@link #sign(String)} wraps a serialised {@code AgentResult} JSON in a
 *       {@link SignedResultEnvelope} with Ed25519 signature.</li>
 *   <li>The orchestrator's {@code SignatureVerificationService} verifies on receipt.</li>
 * </ol>
 *
 * <p>When signing is disabled ({@code agent.crypto.signing-enabled=false}),
 * {@link #sign(String)} returns {@code null} and the producer publishes raw results.</p>
 */
public class WorkerSigningService {

    private static final Logger log = LoggerFactory.getLogger(WorkerSigningService.class);

    private final boolean enabled;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String publicKeyBase64;

    public WorkerSigningService(WorkerCryptoProperties properties) {
        this.enabled = properties.signingEnabled();

        if (!enabled) {
            this.privateKey = null;
            this.publicKey = null;
            this.publicKeyBase64 = null;
            log.info("Worker result signing DISABLED");
            return;
        }

        if (properties.privateKeyBase64() != null && !properties.privateKeyBase64().isBlank()) {
            // Load from configuration
            this.privateKey = Ed25519Signer.decodePrivateKey(properties.privateKeyBase64());
            this.publicKey = Ed25519Signer.decodePublicKey(properties.publicKeyBase64());
            this.publicKeyBase64 = properties.publicKeyBase64();
            log.info("Worker result signing ENABLED — loaded configured keypair (pubKey={}…)",
                    publicKeyBase64.substring(0, Math.min(20, publicKeyBase64.length())));
        } else {
            // Generate ephemeral keypair
            KeyPair kp = Ed25519Signer.generateKeyPair();
            this.privateKey = kp.getPrivate();
            this.publicKey = kp.getPublic();
            this.publicKeyBase64 = Ed25519Signer.encodePublicKey(publicKey);
            log.warn("Worker result signing ENABLED — generated EPHEMERAL keypair (pubKey={}…). "
                            + "Set agent.crypto.private-key-base64 and public-key-base64 for production.",
                    publicKeyBase64.substring(0, Math.min(20, publicKeyBase64.length())));
        }
    }

    /**
     * Signs a serialised AgentResult JSON, wrapping it in a {@link SignedResultEnvelope}.
     *
     * @param resultJson the AgentResult serialised as JSON
     * @return signed envelope, or {@code null} if signing is disabled
     */
    public SignedResultEnvelope sign(String resultJson) {
        if (!enabled) return null;
        return SignedResultEnvelope.sign(resultJson, privateKey, publicKey);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }
}
