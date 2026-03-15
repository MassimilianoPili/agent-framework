package com.agentframework.orchestrator.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-warm pool of Docker images for sandbox execution.
 *
 * <p>Maintains a set of "warm" (pre-pulled) Docker images to minimize cold-start
 * latency. Uses LRU eviction when pool capacity is exceeded.</p>
 *
 * <p>Supported language images are configurable via {@link ExecutionRuntimeConfig}.
 * Each image is periodically health-checked to ensure availability.</p>
 */
@Component
@ConditionalOnProperty(prefix = "execution-runtime", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ContainerPool {

    private static final Logger log = LoggerFactory.getLogger(ContainerPool.class);

    /** Default Docker images per language. */
    private static final Map<String, String> DEFAULT_IMAGES = Map.of(
            "java", "maven:3.9-eclipse-temurin-21-alpine",
            "go", "golang:1.22-alpine",
            "python", "python:3.12-alpine",
            "node", "node:22-alpine",
            "rust", "rust:1.77-alpine",
            "cpp", "gcc:14",
            "dotnet", "mcr.microsoft.com/dotnet/sdk:8.0-alpine",
            "cobol", "ghcr.io/nicholasgasior/gnucobol:3.2"
    );

    private final ExecutionRuntimeConfig config;

    /** LRU-ordered map: language → image status. Access order = true for LRU semantics. */
    private final LinkedHashMap<String, ImageStatus> pool;

    /** Last pull timestamp per image (for staleness detection). */
    private final ConcurrentHashMap<String, Instant> lastPulled = new ConcurrentHashMap<>();

    public ContainerPool(ExecutionRuntimeConfig config) {
        this.config = config;
        int capacity = config.pool() != null ? config.pool().size() : 4;
        this.pool = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ImageStatus> eldest) {
                if (size() > capacity) {
                    log.info("ContainerPool LRU eviction: {} ({})", eldest.getKey(), eldest.getValue().image());
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * Returns the Docker image for a language, pulling if necessary.
     *
     * @param language language key (java, go, python, node, etc.)
     * @return Docker image name, or null if language unsupported
     */
    public String getImage(String language) {
        if (language == null) return null;
        String lang = language.toLowerCase();

        synchronized (pool) {
            ImageStatus status = pool.get(lang);
            if (status != null && status.available()) {
                return status.image();
            }
        }

        // Not in pool or not available — try to pull
        String image = resolveImage(lang);
        if (image == null) {
            log.warn("ContainerPool: no image configured for language '{}'", lang);
            return null;
        }

        boolean pulled = pullImage(image);
        synchronized (pool) {
            pool.put(lang, new ImageStatus(image, pulled, Instant.now()));
        }
        return pulled ? image : null;
    }

    /**
     * Pre-warms the pool by pulling configured language images.
     * Called on startup and periodically for health monitoring.
     */
    @Scheduled(fixedDelayString = "${execution-runtime.pool.health-check-interval-ms:300000}")
    public void warmPool() {
        List<String> languages = config.pool() != null && config.pool().languages() != null
                ? config.pool().languages()
                : List.of("java", "go", "python", "node");

        log.debug("ContainerPool warmPool: checking {} languages", languages.size());

        for (String lang : languages) {
            String image = resolveImage(lang);
            if (image == null) continue;

            boolean available = isImageAvailable(image);
            if (!available) {
                available = pullImage(image);
            }

            synchronized (pool) {
                pool.put(lang, new ImageStatus(image, available, Instant.now()));
            }
        }
    }

    /**
     * Returns pool status for monitoring.
     */
    public Map<String, ImageStatus> status() {
        synchronized (pool) {
            return new LinkedHashMap<>(pool);
        }
    }

    /**
     * Returns the number of available (warm) images.
     */
    public int warmCount() {
        synchronized (pool) {
            return (int) pool.values().stream().filter(ImageStatus::available).count();
        }
    }

    // --- Private helpers ---

    private String resolveImage(String language) {
        // Check config override first
        if (config.pool() != null && config.pool().imageOverrides() != null) {
            String override = config.pool().imageOverrides().get(language);
            if (override != null) return override;
        }
        return DEFAULT_IMAGES.get(language);
    }

    private boolean isImageAvailable(String image) {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", image)
                    .redirectErrorStream(true)
                    .start();
            boolean completed = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return completed && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private boolean pullImage(String image) {
        try {
            log.info("ContainerPool pulling image: {}", image);
            Process p = new ProcessBuilder("docker", "pull", "--quiet", image)
                    .redirectErrorStream(true)
                    .start();
            boolean completed = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                p.destroyForcibly();
                log.warn("ContainerPool pull timeout: {}", image);
                return false;
            }
            boolean success = p.exitValue() == 0;
            if (success) {
                lastPulled.put(image, Instant.now());
                log.info("ContainerPool pulled: {}", image);
            } else {
                log.warn("ContainerPool pull failed: {} (exit {})", image, p.exitValue());
            }
            return success;
        } catch (IOException | InterruptedException e) {
            log.error("ContainerPool pull error: {} — {}", image, e.getMessage());
            return false;
        }
    }

    /**
     * Image status in the pool.
     *
     * @param image     Docker image name
     * @param available true if image is pulled and ready
     * @param checkedAt last health check timestamp
     */
    public record ImageStatus(String image, boolean available, Instant checkedAt) {}
}
