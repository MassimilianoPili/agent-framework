package com.agentframework.orchestrator.artifact;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Content-addressable artifact entry.
 * Primary key is SHA-256 of the content — guarantees deduplication and integrity.
 */
@Entity
@Table(name = "artifact_store")
public class ArtifactBlob {

    @Id
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "access_count", nullable = false)
    private long accessCount;

    protected ArtifactBlob() {}

    public ArtifactBlob(String contentHash, String content, long sizeBytes) {
        this.contentHash = contentHash;
        this.content = content;
        this.sizeBytes = sizeBytes;
        this.createdAt = Instant.now();
        this.accessCount = 1;
    }

    public String getContentHash() { return contentHash; }
    public String getContent() { return content; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public long getAccessCount() { return accessCount; }

    public void incrementAccessCount() {
        this.accessCount++;
    }
}
