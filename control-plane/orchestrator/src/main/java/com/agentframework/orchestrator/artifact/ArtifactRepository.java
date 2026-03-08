package com.agentframework.orchestrator.artifact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArtifactRepository extends JpaRepository<ArtifactBlob, String> {

    @Modifying
    @Query("UPDATE ArtifactBlob a SET a.accessCount = a.accessCount + 1 WHERE a.contentHash = :hash")
    void incrementAccessCount(@Param("hash") String hash);

    @Query("SELECT COUNT(a) FROM ArtifactBlob a")
    long countArtifacts();

    @Query("SELECT COALESCE(SUM(a.accessCount), 0) FROM ArtifactBlob a")
    long totalReferences();

    @Query("SELECT COALESCE(SUM(a.sizeBytes), 0) FROM ArtifactBlob a")
    long totalSizeBytes();
}
