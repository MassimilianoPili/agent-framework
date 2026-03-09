package com.agentframework.worker.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Ed25519 result signing (#31).
 *
 * <p>When {@code signing-enabled} is {@code true} (default), each worker signs
 * its {@code AgentResult} before publishing to Redis Streams. The orchestrator
 * verifies the signature on receipt.</p>
 *
 * <p>Keys can be provided via environment variables or generated at startup:</p>
 * <pre>
 * agent.crypto:
 *   signing-enabled: true
 *   private-key-base64: ${WORKER_PRIVATE_KEY_BASE64:}
 *   public-key-base64: ${WORKER_PUBLIC_KEY_BASE64:}
 * </pre>
 *
 * <p>If both key properties are empty/null, a fresh ephemeral keypair is generated
 * at startup (suitable for development; logs a warning).</p>
 */
@ConfigurationProperties(prefix = "agent.crypto")
public record WorkerCryptoProperties(
        boolean signingEnabled,
        String privateKeyBase64,
        String publicKeyBase64
) {
    public WorkerCryptoProperties {
        // Default: signing enabled
        // No validation on keys — null means "generate ephemeral"
        if (signingEnabled && privateKeyBase64 != null && !privateKeyBase64.isBlank()
                && (publicKeyBase64 == null || publicKeyBase64.isBlank())) {
            throw new IllegalArgumentException(
                    "agent.crypto: if private-key-base64 is set, public-key-base64 must also be set");
        }
    }
}
