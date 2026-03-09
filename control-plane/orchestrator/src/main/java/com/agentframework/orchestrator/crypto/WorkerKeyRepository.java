package com.agentframework.orchestrator.crypto;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Ed25519 worker public keys (#31).
 */
public interface WorkerKeyRepository extends JpaRepository<WorkerKey, UUID> {

    Optional<WorkerKey> findByPublicKeyBase64AndDisabledFalse(String publicKeyBase64);

    List<WorkerKey> findByWorkerTypeAndDisabledFalse(String workerType);

    List<WorkerKey> findByDisabledFalse();
}
