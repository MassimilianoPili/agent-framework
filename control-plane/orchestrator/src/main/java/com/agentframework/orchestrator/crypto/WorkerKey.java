package com.agentframework.orchestrator.crypto;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Trusted Ed25519 public key for a worker (#31).
 *
 * <p>Registered via TOFU (Trust-On-First-Use) on first signed result,
 * or explicitly via {@code POST /api/v1/workers/keys}. Subsequent results
 * from the same worker are verified against the registered key.</p>
 */
@Entity
@Table(name = "worker_keys")
public class WorkerKey {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "worker_type", nullable = false, length = 50)
    private String workerType;

    @Column(name = "worker_profile", length = 100)
    private String workerProfile;

    @Column(name = "public_key_base64", nullable = false, unique = true, length = 256)
    private String publicKeyBase64;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false)
    private boolean disabled;

    protected WorkerKey() {}

    public WorkerKey(String workerType, String workerProfile, String publicKeyBase64) {
        this.workerType = workerType;
        this.workerProfile = workerProfile;
        this.publicKeyBase64 = publicKeyBase64;
        this.registeredAt = Instant.now();
        this.lastSeenAt = Instant.now();
        this.disabled = false;
    }

    public UUID getId() { return id; }
    public String getWorkerType() { return workerType; }
    public String getWorkerProfile() { return workerProfile; }
    public String getPublicKeyBase64() { return publicKeyBase64; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public boolean isDisabled() { return disabled; }

    public void touchLastSeen() { this.lastSeenAt = Instant.now(); }
    public void disable() { this.disabled = true; }
}
