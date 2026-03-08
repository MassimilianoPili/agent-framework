package com.agentframework.orchestrator.artifact;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
public class ArtifactController {

    private final ArtifactRepository repository;

    public ArtifactController(ArtifactRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/artifact-dedup")
    public ResponseEntity<?> artifactDeduplication() {
        long uniqueArtifacts = repository.countArtifacts();
        long totalReferences = repository.totalReferences();
        long totalSizeBytes = repository.totalSizeBytes();

        double dedupRatio = totalReferences > 0
            ? 1.0 - ((double) uniqueArtifacts / totalReferences)
            : 0.0;

        long estimatedSavedBytes = totalReferences > 0
            ? (long) (totalSizeBytes * ((double) (totalReferences - uniqueArtifacts) / uniqueArtifacts))
            : 0;

        return ResponseEntity.ok(Map.of(
            "uniqueArtifacts", uniqueArtifacts,
            "totalReferences", totalReferences,
            "deduplicationRatio", Math.round(dedupRatio * 10000) / 100.0,
            "totalStorageMb", Math.round(totalSizeBytes / 1024.0 / 1024.0 * 100) / 100.0,
            "estimatedSavedMb", Math.round(estimatedSavedBytes / 1024.0 / 1024.0 * 100) / 100.0
        ));
    }
}
