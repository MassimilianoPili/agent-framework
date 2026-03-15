package com.agentframework.orchestrator.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link WorkspaceManager} — plan-scoped workspace directory lifecycle.
 */
class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        manager = new WorkspaceManager();
        ReflectionTestUtils.setField(manager, "basePath", tempDir.toString());
    }

    @Test
    void createWorkspace_createsDirectoryAndReturnsShortUuid() {
        UUID planId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

        String name = manager.createWorkspace(planId);

        assertThat(name).isEqualTo("a1b2c3d4");
        assertThat(tempDir.resolve("a1b2c3d4")).isDirectory();
    }

    @Test
    void createWorkspace_idempotent() {
        UUID planId = UUID.randomUUID();

        String first = manager.createWorkspace(planId);
        String second = manager.createWorkspace(planId);

        assertThat(first).isEqualTo(second);
        assertThat(tempDir.resolve(first)).isDirectory();
    }

    @Test
    void destroyWorkspace_removesDirectoryAndContents() throws IOException {
        String name = "abcd1234";
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("file.txt"), "content");

        manager.destroyWorkspace(name);

        assertThat(dir).doesNotExist();
    }

    @Test
    void destroyWorkspace_nonExistentDirectory_noop() {
        assertThatCode(() -> manager.destroyWorkspace("nonexistent"))
                .doesNotThrowAnyException();
    }

    @Test
    void destroyWorkspace_pathTraversal_throws() {
        assertThatThrownBy(() -> manager.destroyWorkspace("../../etc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escape base path");
    }

    @Test
    void destroyWorkspace_nestedFiles_allRemoved() throws IOException {
        String name = "deep1234";
        Path dir = tempDir.resolve(name);
        Path nested = dir.resolve("sub/deep");
        Files.createDirectories(nested);
        Files.writeString(dir.resolve("root.txt"), "root");
        Files.writeString(nested.resolve("leaf.txt"), "leaf");

        manager.destroyWorkspace(name);

        assertThat(dir).doesNotExist();
    }
}
