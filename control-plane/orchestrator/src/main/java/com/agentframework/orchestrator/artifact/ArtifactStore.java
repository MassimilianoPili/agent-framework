package com.agentframework.orchestrator.artifact;

import com.agentframework.common.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Content-Addressable Store (CAS) for worker artifacts.
 *
 * <p>Inspired by Git objects: {@code SHA-256(content)} is the primary key.
 * Deduplication is automatic — saving the same content twice increments
 * {@code access_count} without storing a second copy.</p>
 *
 * <p>Integrity verification on read: recomputes the hash and throws
 * {@link ArtifactCorruptedException} if it doesn't match.</p>
 */
@Service
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);
    private final ArtifactRepository repository;

    public ArtifactStore(ArtifactRepository repository) {
        this.repository = repository;
    }

    /**
     * Stores content and returns its SHA-256 hash.
     * If the content already exists, increments access_count (deduplication).
     */
    @Transactional
    public String save(String content) {
        String hash = HashUtil.sha256(content);
        if (hash == null) return null;

        repository.findById(hash).ifPresentOrElse(
            existing -> {
                existing.incrementAccessCount();
                repository.save(existing);
                log.debug("Artifact {} already exists, access_count incremented", hash.substring(0, 12));
            },
            () -> {
                long sizeBytes = content.getBytes(StandardCharsets.UTF_8).length;
                repository.save(new ArtifactBlob(hash, content, sizeBytes));
                log.debug("Artifact {} stored ({} bytes)", hash.substring(0, 12), sizeBytes);
            }
        );
        return hash;
    }

    /**
     * Retrieves content by hash with integrity verification.
     *
     * @throws ArtifactCorruptedException if stored content doesn't match its hash
     */
    @Transactional(readOnly = true)
    public Optional<String> get(String hash) {
        return repository.findById(hash).map(blob -> {
            String recomputed = HashUtil.sha256(blob.getContent());
            if (!hash.equals(recomputed)) {
                throw new ArtifactCorruptedException(
                    "Integrity check failed for artifact " + hash + " (recomputed: " + recomputed + ")");
            }
            return blob.getContent();
        });
    }

    /**
     * Batch retrieval for building dependency context.
     */
    @Transactional(readOnly = true)
    public Map<String, String> getMultiple(Collection<String> hashes) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String hash : hashes) {
            get(hash).ifPresent(content -> result.put(hash, content));
        }
        return result;
    }
}
