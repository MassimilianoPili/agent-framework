package com.agentframework.orchestrator.api;

import com.agentframework.orchestrator.api.dto.KeyRegistrationRequest;
import com.agentframework.orchestrator.api.dto.WorkerKeyDto;
import com.agentframework.orchestrator.crypto.WorkerKey;
import com.agentframework.orchestrator.crypto.WorkerKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST API for managing trusted worker Ed25519 keys (#31 — Verifiable Compute).
 *
 * <p>Supports explicit key registration (alternative to TOFU), listing, and revocation.
 */
@RestController
@RequestMapping("/api/v1/workers/keys")
public class WorkerKeyController {

    private static final Logger log = LoggerFactory.getLogger(WorkerKeyController.class);

    private final WorkerKeyRepository repository;

    public WorkerKeyController(WorkerKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers a worker's public key, or updates {@code lastSeenAt} if it already exists.
     */
    @PostMapping
    public ResponseEntity<WorkerKeyDto> registerKey(@RequestBody KeyRegistrationRequest req) {
        Optional<WorkerKey> existing = repository
                .findByPublicKeyBase64AndDisabledFalse(req.publicKeyBase64());

        if (existing.isPresent()) {
            WorkerKey key = existing.get();
            key.touchLastSeen();
            repository.save(key);
            log.info("Updated lastSeenAt for existing key (id={}, workerType={})",
                     key.getId(), key.getWorkerType());
            return ResponseEntity.ok(WorkerKeyDto.from(key));
        }

        WorkerKey newKey = new WorkerKey(req.workerType(), req.workerProfile(), req.publicKeyBase64());
        repository.save(newKey);
        log.info("Registered new worker key (id={}, workerType={}, publicKey={}...)",
                 newKey.getId(), newKey.getWorkerType(),
                 req.publicKeyBase64().substring(0, Math.min(16, req.publicKeyBase64().length())));
        return ResponseEntity.status(201).body(WorkerKeyDto.from(newKey));
    }

    /**
     * Lists active (non-disabled) keys, optionally filtered by worker type.
     */
    @GetMapping
    public List<WorkerKeyDto> listKeys(@RequestParam(required = false) String workerType) {
        List<WorkerKey> keys = (workerType != null && !workerType.isBlank())
                ? repository.findByWorkerTypeAndDisabledFalse(workerType)
                : repository.findByDisabledFalse();
        return keys.stream().map(WorkerKeyDto::from).toList();
    }

    /**
     * Soft-deletes a key by setting {@code disabled = true}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disableKey(@PathVariable UUID id) {
        Optional<WorkerKey> key = repository.findById(id);
        if (key.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        key.get().disable();
        repository.save(key.get());
        log.info("Disabled worker key (id={})", id);
        return ResponseEntity.noContent().build();
    }
}
