package com.agentframework.orchestrator.crypto;

import com.agentframework.common.crypto.Ed25519Signer;
import com.agentframework.common.crypto.SignedResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.PublicKey;
import java.util.Optional;

/**
 * Verifies Ed25519 signatures on worker results (#31 — Verifiable Compute).
 *
 * <p>Two verification modes:
 * <ol>
 *   <li><b>Trusted key</b>: if the worker's public key is already registered in
 *       {@code worker_keys}, the signature is verified against the stored key.</li>
 *   <li><b>TOFU (Trust-On-First-Use)</b>: if the key is unknown, it's verified
 *       using the embedded key in the envelope and then registered for future use.</li>
 * </ol>
 *
 * <p>Active by default; disable with {@code agent.crypto.verification-enabled=false}.
 */
@Service
@ConditionalOnProperty(name = "agent.crypto.verification-enabled", havingValue = "true", matchIfMissing = true)
public class SignatureVerificationService {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerificationService.class);

    private final WorkerKeyRepository keyRepository;

    public SignatureVerificationService(WorkerKeyRepository keyRepository) {
        this.keyRepository = keyRepository;
    }

    /**
     * Verifies a signed result envelope.
     *
     * @param envelope the signed envelope from a worker
     * @return verification result with mode (TRUSTED, TOFU) and validity
     */
    @Transactional
    public VerificationResult verify(SignedResultEnvelope envelope) {
        String embeddedKeyB64 = envelope.workerPublicKey();

        // 1. Lookup trusted key from DB
        Optional<WorkerKey> existing = keyRepository
                .findByPublicKeyBase64AndDisabledFalse(embeddedKeyB64);

        if (existing.isPresent()) {
            // Trusted key — verify against the registered key
            WorkerKey workerKey = existing.get();
            PublicKey trustedKey = Ed25519Signer.decodePublicKey(workerKey.getPublicKeyBase64());

            boolean valid = envelope.verify(trustedKey);
            if (valid) {
                workerKey.touchLastSeen();
                keyRepository.save(workerKey);
                log.debug("Signature verified (TRUSTED) for key {}", truncate(embeddedKeyB64));
                return VerificationResult.trusted(embeddedKeyB64);
            } else {
                log.warn("Signature verification FAILED for trusted key {}", truncate(embeddedKeyB64));
                return VerificationResult.failed(embeddedKeyB64);
            }
        }

        // 2. TOFU mode — verify with embedded key, then register
        boolean valid = envelope.verify(null); // null = use embedded key
        if (!valid) {
            log.warn("Signature verification FAILED in TOFU mode for key {}", truncate(embeddedKeyB64));
            return VerificationResult.failed(embeddedKeyB64);
        }

        // Register the new key
        WorkerKey newKey = new WorkerKey("UNKNOWN", null, embeddedKeyB64);
        keyRepository.save(newKey);
        log.info("TOFU: registered new worker key {} (id={})", truncate(embeddedKeyB64), newKey.getId());

        return VerificationResult.tofu(embeddedKeyB64);
    }

    private static String truncate(String base64Key) {
        return base64Key.length() > 16 ? base64Key.substring(0, 16) + "..." : base64Key;
    }
}
