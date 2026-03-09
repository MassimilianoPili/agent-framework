package com.agentframework.orchestrator.crypto;

/**
 * Result of verifying a signed worker result (#31).
 *
 * @param valid           whether the cryptographic signature is valid
 * @param mode            how verification was performed
 * @param workerPublicKey the Base64-encoded public key used for verification
 */
public record VerificationResult(
        boolean valid,
        Mode mode,
        String workerPublicKey
) {

    public enum Mode {
        /** Signature verified against a pre-registered trusted key. */
        TRUSTED,
        /** First contact — key was accepted and registered (Trust-On-First-Use). */
        TOFU,
        /** Result arrived without a signature envelope. */
        UNSIGNED
    }

    public static VerificationResult trusted(String workerPublicKey) {
        return new VerificationResult(true, Mode.TRUSTED, workerPublicKey);
    }

    public static VerificationResult tofu(String workerPublicKey) {
        return new VerificationResult(true, Mode.TOFU, workerPublicKey);
    }

    public static VerificationResult failed(String workerPublicKey) {
        return new VerificationResult(false, null, workerPublicKey);
    }

    public static VerificationResult unsigned() {
        return new VerificationResult(true, Mode.UNSIGNED, null);
    }
}
