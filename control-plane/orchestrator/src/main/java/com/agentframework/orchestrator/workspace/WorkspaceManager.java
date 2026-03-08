package com.agentframework.orchestrator.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.UUID;

/**
 * Manages plan-scoped workspace directories on the shared bind-mount.
 *
 * <p>Each plan gets a subdirectory under {@code basePath} named after
 * the first 8 characters of its UUID. Workers mount the same host directory
 * and can read/write files relative to {@code /workspace/{name}/}.</p>
 *
 * <p>The orchestrator container has the bind-mount as RW; domain workers as RW;
 * review workers as RO.</p>
 */
@Service
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    @Value("${agent.workspace.base-path:/workspace}")
    private String basePath;

    /**
     * Creates a workspace directory for the given plan.
     *
     * @param planId the plan UUID
     * @return the workspace name (short UUID prefix), usable as {@code /workspace/{name}/}
     */
    public String createWorkspace(UUID planId) {
        String name = planId.toString().substring(0, 8);
        Path dir = Path.of(basePath, name);
        try {
            Files.createDirectories(dir);
            log.info("Created workspace {} at {}", name, dir);
        } catch (IOException e) {
            log.warn("Failed to create workspace {}: {}", name, e.getMessage());
        }
        return name;
    }

    /**
     * Destroys a workspace directory and all its contents.
     *
     * @param name the workspace name (short UUID prefix)
     * @throws IllegalArgumentException if the name would escape the base path
     */
    public void destroyWorkspace(String name) {
        Path dir = Path.of(basePath, name).normalize();

        // Guard: prevent path traversal
        if (!dir.startsWith(Path.of(basePath).normalize())) {
            throw new IllegalArgumentException(
                "Workspace name would escape base path: " + name);
        }

        if (!Files.exists(dir)) {
            log.debug("Workspace {} does not exist, nothing to destroy", name);
            return;
        }

        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Destroyed workspace {}", name);
        } catch (IOException e) {
            log.warn("Failed to fully destroy workspace {}: {}", name, e.getMessage());
        }
    }
}
