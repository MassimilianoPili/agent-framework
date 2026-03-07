package com.agentframework.worker.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads Markdown skill files with filesystem override support.
 *
 * Resolution order:
 * 1. Filesystem override: {@code ${FS_SKILLS_DIR}/<path>} if the env var is set and the file exists.
 * 2. Classpath fallback: files packed into the worker JAR at build time.
 * 3. If neither exists: throws RuntimeException (fail-fast at startup or first use).
 *
 * Results are cached in memory (skills are immutable at runtime).
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    private final String skillsDir;

    public SkillLoader(@Value("${FS_SKILLS_DIR:}") String skillsDir) {
        this.skillsDir = skillsDir;
    }

    /**
     * Loads a skill file by path (e.g., "skills/be-worker.agent.md").
     * Tries filesystem override first, then classpath.
     */
    public String load(String resourcePath) {
        return cache.computeIfAbsent(resourcePath, this::resolve);
    }

    private String resolve(String resourcePath) {
        // 1. Filesystem override
        if (skillsDir != null && !skillsDir.isBlank()) {
            Path fsPath = Path.of(skillsDir, resourcePath);
            if (Files.isRegularFile(fsPath)) {
                try {
                    String content = Files.readString(fsPath, StandardCharsets.UTF_8);
                    log.info("Loaded '{}' from override ({})", resourcePath, fsPath);
                    return stripFrontmatter(content);
                } catch (IOException e) {
                    log.warn("Filesystem override exists but failed to read: {}", fsPath, e);
                    // Fall through to classpath
                }
            } else if (Files.isDirectory(fsPath)) {
                // Directory path: scan all .md files recursively, concatenate with HR separators
                return resolveDirectory(resourcePath, fsPath);
            }
        }

        // 2. Classpath fallback
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new RuntimeException(
                "Skill file not found: '" + resourcePath + "'. "
                + "Not found on filesystem (FS_SKILLS_DIR=" + (skillsDir.isBlank() ? "<unset>" : skillsDir)
                + ") nor on classpath. Ensure the file is in the JAR or set FS_SKILLS_DIR.");
        }
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            log.info("Loaded '{}' from classpath", resourcePath);
            return stripFrontmatter(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill file from classpath: " + resourcePath, e);
        }
    }

    /**
     * Recursively scans a directory for .md files, loads and concatenates them
     * with horizontal rule separators. Files are sorted by path for deterministic ordering.
     */
    private String resolveDirectory(String resourcePath, Path dirPath) {
        try (Stream<Path> walk = Files.walk(dirPath)) {
            String composed = walk
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .sorted()
                .map(p -> {
                    try {
                        return stripFrontmatter(Files.readString(p, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        log.warn("Failed to read skill file in directory: {}", p, e);
                        return "";
                    }
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n---\n\n"));

            if (composed.isBlank()) {
                log.warn("Directory '{}' contains no .md files", resourcePath);
                return "";
            }
            log.info("Loaded '{}' from directory override ({}) — composed from .md files", resourcePath, dirPath);
            return composed;
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan skill directory: " + dirPath, e);
        }
    }

    /**
     * Strips YAML frontmatter (--- ... ---) from a Markdown file so that only
     * the body is passed to the LLM as the system prompt. Files without frontmatter
     * are returned unchanged.
     */
    private static String stripFrontmatter(String content) {
        if (!content.startsWith("---")) return content;
        int end = content.indexOf("\n---", 3);
        return end < 0 ? content : content.substring(end + 4).stripLeading();
    }
}
