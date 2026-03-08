package com.agentframework.orchestrator.artifact;

import com.agentframework.common.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ArtifactStore} — CAS save/get, deduplication, integrity check.
 */
@ExtendWith(MockitoExtension.class)
class ArtifactStoreTest {

    @Mock private ArtifactRepository repository;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new ArtifactStore(repository);
    }

    @Test
    void save_newContent_storesAndReturnsHash() {
        String content = "{\"code\": \"ok\"}";
        String expectedHash = HashUtil.sha256(content);

        when(repository.findById(expectedHash)).thenReturn(Optional.empty());
        when(repository.save(any(ArtifactBlob.class))).thenAnswer(inv -> inv.getArgument(0));

        String hash = store.save(content);

        assertThat(hash).isEqualTo(expectedHash);

        ArgumentCaptor<ArtifactBlob> captor = ArgumentCaptor.forClass(ArtifactBlob.class);
        verify(repository).save(captor.capture());
        ArtifactBlob saved = captor.getValue();
        assertThat(saved.getContentHash()).isEqualTo(expectedHash);
        assertThat(saved.getContent()).isEqualTo(content);
        assertThat(saved.getAccessCount()).isEqualTo(1);
    }

    @Test
    void save_duplicateContent_incrementsAccessCount() {
        String content = "{\"code\": \"ok\"}";
        String expectedHash = HashUtil.sha256(content);
        ArtifactBlob existing = new ArtifactBlob(expectedHash, content, content.length());

        when(repository.findById(expectedHash)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        String hash = store.save(content);

        assertThat(hash).isEqualTo(expectedHash);
        assertThat(existing.getAccessCount()).isEqualTo(2);
        verify(repository).save(existing);
    }

    @Test
    void save_nullContent_returnsNull() {
        assertThat(store.save(null)).isNull();
        verify(repository, never()).findById(any());
    }

    @Test
    void get_existingContent_returnsContent() {
        String content = "test artifact content";
        String hash = HashUtil.sha256(content);
        ArtifactBlob blob = new ArtifactBlob(hash, content, content.length());

        when(repository.findById(hash)).thenReturn(Optional.of(blob));

        Optional<String> result = store.get(hash);

        assertThat(result).isPresent().contains(content);
    }

    @Test
    void get_missingHash_returnsEmpty() {
        when(repository.findById("nonexistent")).thenReturn(Optional.empty());

        assertThat(store.get("nonexistent")).isEmpty();
    }

    @Test
    void get_corruptedContent_throwsException() {
        String originalContent = "original";
        String hash = HashUtil.sha256(originalContent);
        // Store corrupted content under the original hash
        ArtifactBlob corrupted = new ArtifactBlob(hash, "tampered content", 16);

        when(repository.findById(hash)).thenReturn(Optional.of(corrupted));

        assertThatThrownBy(() -> store.get(hash))
                .isInstanceOf(ArtifactCorruptedException.class)
                .hasMessageContaining("Integrity check failed");
    }

    @Test
    void getMultiple_returnsBatchResults() {
        String content1 = "artifact-1";
        String content2 = "artifact-2";
        String hash1 = HashUtil.sha256(content1);
        String hash2 = HashUtil.sha256(content2);

        when(repository.findById(hash1)).thenReturn(
                Optional.of(new ArtifactBlob(hash1, content1, content1.length())));
        when(repository.findById(hash2)).thenReturn(
                Optional.of(new ArtifactBlob(hash2, content2, content2.length())));

        Map<String, String> results = store.getMultiple(List.of(hash1, hash2));

        assertThat(results).hasSize(2);
        assertThat(results.get(hash1)).isEqualTo(content1);
        assertThat(results.get(hash2)).isEqualTo(content2);
    }
}
