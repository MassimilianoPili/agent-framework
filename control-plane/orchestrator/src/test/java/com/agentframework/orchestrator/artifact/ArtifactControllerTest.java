package com.agentframework.orchestrator.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link ArtifactController} (#48 — CAS retrieval + analytics endpoints).
 */
@WebMvcTest(ArtifactController.class)
@DisplayName("ArtifactController (#48)")
class ArtifactControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArtifactStore artifactStore;

    @MockitoBean
    private ArtifactRepository repository;

    private static final String HASH = "a".repeat(64);
    private static final String CONTENT = "{\"code\":\"public class Foo {}\"}";

    @Test
    @DisplayName("GET /api/v1/artifacts/{hash} — found returns content")
    void getArtifact_found_returnsContent() throws Exception {
        when(artifactStore.get(HASH)).thenReturn(Optional.of(CONTENT));

        mockMvc.perform(get("/api/v1/artifacts/{hash}", HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hash").value(HASH))
                .andExpect(jsonPath("$.content").value(CONTENT));
    }

    @Test
    @DisplayName("GET /api/v1/artifacts/{hash} — not found returns 404")
    void getArtifact_notFound_returns404() throws Exception {
        when(artifactStore.get(HASH)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/artifacts/{hash}", HASH))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/artifacts/{hash} — corrupted throws 500")
    void getArtifact_corrupted_returns500() throws Exception {
        when(artifactStore.get(HASH)).thenThrow(
                new ArtifactCorruptedException("hash mismatch"));

        mockMvc.perform(get("/api/v1/artifacts/{hash}", HASH))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("GET /api/v1/artifacts/{hash}/metadata — found returns metadata")
    void getMetadata_found_returnsMetadata() throws Exception {
        ArtifactBlob blob = new ArtifactBlob(HASH, CONTENT, 42L);
        when(repository.findById(HASH)).thenReturn(Optional.of(blob));

        mockMvc.perform(get("/api/v1/artifacts/{hash}/metadata", HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hash").value(HASH))
                .andExpect(jsonPath("$.sizeBytes").value(42))
                .andExpect(jsonPath("$.accessCount").value(1))
                .andExpect(jsonPath("$.createdAt").isString());
    }

    @Test
    @DisplayName("GET /api/v1/artifacts/{hash}/metadata — not found returns 404")
    void getMetadata_notFound_returns404() throws Exception {
        when(repository.findById(HASH)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/artifacts/{hash}/metadata", HASH))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/analytics/artifact-dedup — returns dedup stats")
    void artifactDedup_returnsStats() throws Exception {
        when(repository.countArtifacts()).thenReturn(100L);
        when(repository.totalReferences()).thenReturn(150L);
        when(repository.totalSizeBytes()).thenReturn(1048576L); // 1 MB

        mockMvc.perform(get("/api/v1/analytics/artifact-dedup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uniqueArtifacts").value(100))
                .andExpect(jsonPath("$.totalReferences").value(150))
                .andExpect(jsonPath("$.deduplicationRatio").isNumber())
                .andExpect(jsonPath("$.totalStorageMb").value(1.0))
                .andExpect(jsonPath("$.estimatedSavedMb").isNumber());
    }
}
