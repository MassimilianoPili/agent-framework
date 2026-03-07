package com.agentframework.worker.policy;

import com.agentframework.common.policy.HookPolicy;
import com.agentframework.common.policy.ToolNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Enforces path ownership policy for write operations.
 *
 * <p>When a worker declares {@code ownsPaths: [backend/]}, this enforcer ensures
 * that write tools (Write, Edit) can only target files under those prefixes.
 * Read operations are never restricted.</p>
 *
 * <p>Fail-open design: if the JSON input cannot be parsed or the path cannot be
 * extracted, the operation is allowed. The filesystem {@code base-dir} configured
 * in MCP tools remains the hard security boundary; this enforcer is an additional
 * defense-in-depth layer.</p>
 */
public class PathOwnershipEnforcer {

    private static final Logger log = LoggerFactory.getLogger(PathOwnershipEnforcer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Common JSON field names for file paths across MCP tool implementations. */
    private static final List<String> PATH_FIELDS = List.of(
            "filePath", "path", "relativePath", "file_path", "file", "workingDir"
    );

    private final PolicyProperties properties;

    public PathOwnershipEnforcer(PolicyProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks if a tool invocation respects this worker's path ownership.
     * Uses the static {@code ownsPaths} from {@link PolicyProperties}.
     *
     * @param toolName  the name of the tool being invoked
     * @param toolInput the JSON input to the tool
     * @return empty if allowed; present with an error message if denied
     */
    public Optional<String> checkOwnership(String toolName, String toolInput) {
        return checkOwnership(toolName, toolInput, properties.getOwnsPaths());
    }

    /**
     * Checks if a tool invocation respects the provided path ownership list.
     *
     * <p>Used by {@link PolicyEnforcingToolCallback} when a task-level {@link HookPolicy}
     * is present — the task policy overrides the static {@link PolicyProperties} config.</p>
     *
     * @param toolName  the name of the tool being invoked
     * @param toolInput the JSON input to the tool
     * @param ownsPaths the effective path prefixes to enforce (null or empty = no restriction)
     * @return empty if allowed; present with an error message if denied
     */
    public Optional<String> checkOwnership(String toolName, String toolInput, List<String> ownsPaths) {
        // No ownership constraint → everything allowed
        if (ownsPaths == null || ownsPaths.isEmpty()) {
            return Optional.empty();
        }

        // Only check write tools (ToolNames registry + any extra names from config)
        if (!ToolNames.isWriteTool(toolName) && !properties.getWriteToolNames().contains(toolName)) {
            return Optional.empty();
        }

        // Extract path from tool input
        String filePath = extractPath(toolInput);
        if (filePath == null) {
            // Fail-open: can't determine path, allow it
            log.debug("Could not extract path from {} input, allowing (fail-open)", toolName);
            return Optional.empty();
        }

        // Normalize: remove leading slash, resolve relative components
        String normalized = normalizePath(filePath);

        // Check if path starts with any owned prefix
        for (String prefix : ownsPaths) {
            if (normalized.startsWith(prefix)) {
                return Optional.empty();
            }
        }

        String violation = String.format(
                "Path ownership violation: worker profile '%s' cannot write to '%s' "
                        + "(allowed prefixes: %s)",
                properties.getWorkerProfile(), normalized, ownsPaths);
        log.warn(violation);
        return Optional.of(violation);
    }

    /**
     * Checks if a Read tool invocation targets a file within the task's allowed context.
     *
     * <p>Called only when the worker has a non-empty {@code relevantFiles} list (i.e., a
     * CONTEXT_MANAGER task has run and provided an explicit file allowlist). Workers without
     * relevant files (CONTRACT, REVIEW, CONTEXT_MANAGER itself) are unrestricted.
     * Fail-open: if the path cannot be parsed, the read is allowed.</p>
     *
     * @param toolName      the tool being invoked
     * @param toolInput     the JSON input to the tool
     * @param relevantFiles the allowed file paths from AgentContext.relevantFiles()
     * @return empty if allowed; present with an error message if denied
     */
    public Optional<String> checkReadOwnership(String toolName, String toolInput,
                                               List<String> relevantFiles) {
        // Only restrict read tools; only when an explicit allowlist is set
        if (!ToolNames.isReadTool(toolName)
                || relevantFiles == null || relevantFiles.isEmpty()) {
            return Optional.empty();
        }

        String filePath = extractPath(toolInput);
        if (filePath == null) {
            // Fail-open: can't determine path, allow it
            log.debug("Could not extract path from Read input, allowing (fail-open)");
            return Optional.empty();
        }

        String normalized = normalizePath(filePath);

        // Allow if the path matches any allowed context file
        for (String allowed : relevantFiles) {
            String normalizedAllowed = normalizePath(allowed);
            if (normalized.equals(normalizedAllowed) || normalized.startsWith(normalizedAllowed + "/")) {
                return Optional.empty();
            }
        }

        // Also allow reads within the worker's own ownsPaths (worker-owned files)
        List<String> ownsPaths = properties.getOwnsPaths();
        if (ownsPaths != null) {
            for (String prefix : ownsPaths) {
                if (normalized.startsWith(prefix)) {
                    return Optional.empty();
                }
            }
        }

        String violation = String.format(
                "Read access denied: path '%s' is not in task context. "
                        + "Add it to missing_context in your result to request access.",
                normalized);
        log.warn("[{}] {}", properties.getWorkerProfile(), violation);
        return Optional.of(violation);
    }

    /**
     * Extracts a file path from JSON tool input by checking common field names.
     */
    private String extractPath(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(toolInput);
            for (String field : PATH_FIELDS) {
                JsonNode node = root.get(field);
                if (node != null && node.isTextual() && !node.asText().isBlank()) {
                    return node.asText();
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to parse tool input as JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Normalizes a path: removes leading slashes, resolves ".." components,
     * produces a clean relative path for prefix matching.
     */
    private String normalizePath(String filePath) {
        try {
            Path path = Path.of(filePath).normalize();
            String result = path.toString();
            // Strip leading slash to make relative
            while (result.startsWith("/")) {
                result = result.substring(1);
            }
            return result;
        } catch (Exception e) {
            return filePath;
        }
    }
}
