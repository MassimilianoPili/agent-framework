package com.agentframework.orchestrator.artifact;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoints for the Content-Addressable Storage (CAS) layer (#48).
 *
 * <ul>
 *   <li>{@code GET /api/v1/artifacts/{hash}} — retrieve artifact content with integrity check</li>
 *   <li>{@code GET /api/v1/artifacts/{hash}/metadata} — artifact metadata (size, access count, created)</li>
 *   <li>{@code GET /api/v1/analytics/artifact-dedup} — deduplication statistics</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class ArtifactController {

    private final ArtifactStore artifactStore;
    private final ArtifactRepository repository;

    public ArtifactController(ArtifactStore artifactStore, ArtifactRepository repository) {
        this.artifactStore = artifactStore;
        this.repository = repository;
    }

    /**
     * Retrieves artifact content by its SHA-256 hash.
     * Integrity is verified on read — throws 500 if content is corrupted.
     */
    @GetMapping("/artifacts/{hash}")
    public ResponseEntity<Map<String, Object>> getArtifact(@PathVariable String hash) {
        return artifactStore.get(hash)
                .map(content -> ResponseEntity.ok(Map.<String, Object>of(
                        "hash", hash,
                        "content", content
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns artifact metadata without the (potentially large) content.
     * Useful for checking existence, size, and popularity before fetching.
     */
    @GetMapping("/artifacts/{hash}/metadata")
    public ResponseEntity<Map<String, Object>> getArtifactMetadata(@PathVariable String hash) {
        return repository.findById(hash)
                .map(blob -> {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("hash", blob.getContentHash());
                    meta.put("sizeBytes", blob.getSizeBytes());
                    meta.put("createdAt", blob.getCreatedAt().toString());
                    meta.put("accessCount", blob.getAccessCount());
                    return ResponseEntity.ok(meta);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Deduplication analytics: unique artifacts, total references, storage savings.
     */
    @GetMapping("/analytics/artifact-dedup")
    public ResponseEntity<Map<String, Object>> artifactDeduplication() {
        long uniqueArtifacts = repository.countArtifacts();
        long totalReferences = repository.totalReferences();
        long totalSizeBytes = repository.totalSizeBytes();

        double dedupRatio = totalReferences > 0
            ? 1.0 - ((double) uniqueArtifacts / totalReferences)
            : 0.0;

        long estimatedSavedBytes = totalReferences > 0
            ? (long) (totalSizeBytes * ((double) (totalReferences - uniqueArtifacts) / uniqueArtifacts))
            : 0;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("uniqueArtifacts", uniqueArtifacts);
        stats.put("totalReferences", totalReferences);
        stats.put("deduplicationRatio", Math.round(dedupRatio * 10000) / 100.0);
        stats.put("totalStorageMb", Math.round(totalSizeBytes / 1024.0 / 1024.0 * 100) / 100.0);
        stats.put("estimatedSavedMb", Math.round(estimatedSavedBytes / 1024.0 / 1024.0 * 100) / 100.0);
        return ResponseEntity.ok(stats);
    }
}
