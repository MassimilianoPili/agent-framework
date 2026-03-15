package com.agentframework.orchestrator.runtime;

import com.agentframework.orchestrator.runtime.ExecutionResult.ResourceUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks resource usage per execution session via Docker stats / cgroups v2.
 *
 * <p>Collects CPU time, peak memory, and I/O bytes after each sandbox execution.
 * Persists metrics to {@code execution_sessions} for cost accounting and
 * capacity planning. Falls back gracefully when Docker stats are unavailable.</p>
 *
 * <p>Feeds data to CostAccounting (#160) when available.</p>
 */
@Service
@ConditionalOnProperty(prefix = "execution-runtime", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ResourceAccountingService {

    private static final Logger log = LoggerFactory.getLogger(ResourceAccountingService.class);

    private static final Pattern DOCKER_STATS_PATTERN = Pattern.compile(
            "([\\d.]+)%\\s+([\\d.]+[kKmMgG]i?[bB]?)\\s*/\\s*[\\d.]+[kKmMgG]i?[bB]?\\s+" +
            "([\\d.]+[kKmMgG]i?[bB]?)\\s*/\\s*([\\d.]+[kKmMgG]i?[bB]?)");

    private final JdbcTemplate jdbcTemplate;

    public ResourceAccountingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Collects resource usage for a Docker container by ID.
     *
     * <p>Uses {@code docker stats --no-stream} for a snapshot. Returns null
     * if stats cannot be collected (container already removed, Docker unavailable).</p>
     *
     * @param containerId Docker container ID or name
     * @return resource usage metrics, or null if unavailable
     */
    @Nullable
    public ResourceUsage collect(String containerId) {
        if (containerId == null || containerId.isBlank()) return null;

        try {
            Process p = new ProcessBuilder(
                    "docker", "stats", "--no-stream", "--format",
                    "{{.CPUPerc}} {{.MemUsage}} {{.NetIO}}", containerId)
                    .redirectErrorStream(true)
                    .start();

            boolean completed = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed || p.exitValue() != 0) {
                return null;
            }

            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return parseDockerStats(output);

        } catch (IOException | InterruptedException e) {
            log.debug("Resource collection failed for {}: {}", containerId, e.getMessage());
            return null;
        }
    }

    /**
     * Records execution session metrics to the database.
     *
     * @param sessionId  execution session UUID
     * @param planId     associated plan UUID (nullable)
     * @param itemId     associated plan item UUID (nullable)
     * @param language   execution language
     * @param exitCode   process exit code
     * @param durationMs execution duration in ms
     * @param usage      resource usage (nullable)
     */
    public void recordSession(UUID sessionId, @Nullable UUID planId, @Nullable UUID itemId,
                               String language, int exitCode, long durationMs,
                               @Nullable ResourceUsage usage) {
        try {
            String resourceJson = usage != null
                    ? String.format("{\"cpuSeconds\":%.3f,\"peakMemoryMb\":%d,\"ioReadBytes\":%d,\"ioWriteBytes\":%d}",
                    usage.cpuSeconds(), usage.peakMemoryMb(), usage.ioReadBytes(), usage.ioWriteBytes())
                    : null;

            jdbcTemplate.update("""
                INSERT INTO execution_sessions (id, plan_id, item_id, language, exit_code, duration_ms, resource_usage)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?::jsonb)
                """,
                    sessionId.toString(),
                    planId != null ? planId.toString() : null,
                    itemId != null ? itemId.toString() : null,
                    language, exitCode, durationMs, resourceJson);

        } catch (Exception e) {
            log.warn("Failed to record execution session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Records a structured error from an execution session.
     */
    public void recordError(UUID sessionId, ExecutionResult.ParsedError error) {
        try {
            jdbcTemplate.update("""
                INSERT INTO execution_errors (id, session_id, error_type, file, line, message, stack_trace)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?)
                """,
                    UUID.randomUUID().toString(), sessionId.toString(),
                    error.severity(), error.file(), error.line(),
                    error.message(), error.stackTrace());
        } catch (Exception e) {
            log.debug("Failed to record execution error: {}", e.getMessage());
        }
    }

    // --- Private helpers ---

    @Nullable
    private ResourceUsage parseDockerStats(String output) {
        // Format: "0.15% 42.3MiB / 512MiB 1.2kB / 3.4kB"
        // We approximate: CPU% → cpu seconds (rough), memory usage, net I/O as proxy for disk I/O
        try {
            String[] parts = output.split("\\s+");
            if (parts.length < 5) return null;

            double cpuPercent = Double.parseDouble(parts[0].replace("%", ""));
            long memoryBytes = parseSize(parts[1]);
            long ioRead = parts.length > 3 ? parseSize(parts[3]) : 0;
            long ioWrite = parts.length > 5 ? parseSize(parts[5]) : 0;

            return new ResourceUsage(
                    cpuPercent / 100.0, // rough approximation
                    memoryBytes / (1024 * 1024),
                    ioRead,
                    ioWrite);
        } catch (Exception e) {
            log.debug("Failed to parse docker stats output: {}", output);
            return null;
        }
    }

    private static long parseSize(String size) {
        if (size == null || size.isEmpty()) return 0;
        size = size.trim().toUpperCase();

        double multiplier = 1;
        if (size.endsWith("GIB") || size.endsWith("GB")) {
            multiplier = 1024L * 1024L * 1024L;
            size = size.replaceAll("[GMKIB]+$", "");
        } else if (size.endsWith("MIB") || size.endsWith("MB")) {
            multiplier = 1024L * 1024L;
            size = size.replaceAll("[GMKIB]+$", "");
        } else if (size.endsWith("KIB") || size.endsWith("KB")) {
            multiplier = 1024L;
            size = size.replaceAll("[GMKIB]+$", "");
        } else if (size.endsWith("B")) {
            size = size.replaceAll("B$", "");
        }

        try {
            return (long) (Double.parseDouble(size) * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
