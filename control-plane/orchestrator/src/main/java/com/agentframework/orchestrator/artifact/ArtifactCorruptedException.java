package com.agentframework.orchestrator.artifact;

/**
 * Thrown when an artifact's content does not match its stored hash.
 * Indicates data corruption or tampering in the artifact store.
 */
public class ArtifactCorruptedException extends RuntimeException {
    public ArtifactCorruptedException(String message) {
        super(message);
    }
}
