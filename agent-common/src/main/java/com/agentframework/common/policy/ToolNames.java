package com.agentframework.common.policy;

import java.util.List;

/**
 * Centralised registry for MCP tool names used in policy enforcement.
 *
 * <p>All tool names referenced by {@link HookPolicy}, policy properties,
 * path-ownership checks, and the agent-compiler Mustache templates should
 * use constants from this class. This prevents the silent drift that occurs
 * when string literals are duplicated across modules.</p>
 *
 * <p>Only tools relevant to the policy layer are listed here.  Domain-specific
 * tools (azure_*, devops_*, docker_*, etc.) are governed by each worker's
 * manifest allowlist and do not appear in this registry.</p>
 */
public final class ToolNames {

    private ToolNames() {}

    // ── MCP filesystem tools (mcp-filesystem-tools) ────────────────────────

    public static final String FS_LIST   = "fs_list";
    public static final String FS_READ   = "fs_read";
    public static final String FS_WRITE  = "fs_write";
    public static final String FS_SEARCH = "fs_search";
    public static final String FS_GREP   = "fs_grep";

    // ── Categories ─────────────────────────────────────────────────────────

    /** Tools that modify files — checked by {@code PathOwnershipEnforcer}. */
    public static final List<String> WRITE_TOOLS = List.of(FS_WRITE);

    /** Tools that read file content. */
    public static final List<String> READ_TOOLS = List.of(FS_READ, FS_GREP);

    /** All five filesystem tools. */
    public static final List<String> ALL_FS_TOOLS = List.of(
            FS_LIST, FS_READ, FS_WRITE, FS_SEARCH, FS_GREP);

    /** Filesystem tools minus write — safe for read-only workers. */
    public static final List<String> READONLY_FS_TOOLS = List.of(
            FS_LIST, FS_READ, FS_SEARCH, FS_GREP);

    // ── Queries ────────────────────────────────────────────────────────────

    public static boolean isWriteTool(String name) {
        return WRITE_TOOLS.contains(name);
    }

    public static boolean isReadTool(String name) {
        return READ_TOOLS.contains(name);
    }
}
