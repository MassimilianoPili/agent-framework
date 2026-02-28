package com.agentframework.worker;

import java.util.List;

/**
 * Sealed type representing the MCP tool policy for a worker.
 *
 * <p>Replaces the old {@code List<String>} / {@code null} convention
 * with a type-safe Null Object pattern:</p>
 * <ul>
 *   <li>{@link All} — all auto-discovered tools are available (default)</li>
 *   <li>{@link Explicit} — only the named tools are available</li>
 * </ul>
 */
public sealed interface ToolAllowlist {

    /** Singleton: all auto-discovered tools are available. */
    ToolAllowlist ALL = new All();

    record All() implements ToolAllowlist {}

    record Explicit(List<String> tools) implements ToolAllowlist {
        public Explicit { tools = List.copyOf(tools); }
    }
}
