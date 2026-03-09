package com.agentframework.orchestrator.api.dto;

/**
 * Request body for registering a worker's Ed25519 public key (#31).
 *
 * @param workerType      the worker type (e.g. "BE_DEVELOPER", "FE_DEVELOPER")
 * @param workerProfile   optional profile within the worker type
 * @param publicKeyBase64 the Ed25519 public key, Base64-encoded
 */
public record KeyRegistrationRequest(
        String workerType,
        String workerProfile,
        String publicKeyBase64
) {}
