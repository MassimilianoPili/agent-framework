package com.agentframework.common.policy;

import com.agentframework.common.util.HashUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes SHA-256 commitment hashes of {@link HookPolicy} records (#32).
 *
 * <p>Uses a hand-built canonical JSON serialization (no Jackson at runtime — Jackson
 * is {@code provided} scope in agent-common). Keys are sorted alphabetically, list
 * values are sorted before serialization, ensuring deterministic output regardless
 * of insertion order.</p>
 *
 * <p>The resulting hash is the trust anchor for Policy-as-Code: computed at storage
 * time by the orchestrator, propagated in {@code AgentTask.policyHash}, and verified
 * by {@code PolicyEnforcingToolCallback} before enforcing tool constraints.</p>
 *
 * @see HookPolicy
 * @see HashUtil#sha256(String)
 */
public final class PolicyHasher {

    private PolicyHasher() {}

    /**
     * Computes the SHA-256 hex digest of a HookPolicy's canonical JSON.
     *
     * @param policy the policy to hash
     * @return 64-character lowercase hex string, or {@code null} if policy is null
     */
    public static String hash(HookPolicy policy) {
        if (policy == null) return null;
        return HashUtil.sha256(toCanonicalJson(policy));
    }

    /**
     * Verifies that a HookPolicy matches an expected hash.
     *
     * @param policy       the policy to verify
     * @param expectedHash the expected SHA-256 hex digest
     * @return {@code true} if the hash matches; {@code false} if mismatch or either arg is null
     */
    public static boolean verify(HookPolicy policy, String expectedHash) {
        if (policy == null || expectedHash == null) return false;
        return expectedHash.equals(hash(policy));
    }

    /**
     * Produces canonical JSON for a HookPolicy.
     *
     * <p>Canonical form guarantees deterministic output:</p>
     * <ul>
     *   <li>Object keys in alphabetical order</li>
     *   <li>List values sorted alphabetically before serialization</li>
     *   <li>No whitespace (compact)</li>
     *   <li>Nullable Integer/Enum fields emit JSON {@code null} literal</li>
     * </ul>
     *
     * @param p the policy (must not be null)
     * @return compact canonical JSON string
     */
    static String toCanonicalJson(HookPolicy p) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');

        // 1. allowedMcpServers
        sb.append("\"allowedMcpServers\":");
        appendSortedList(sb, p.allowedMcpServers());

        // 2. allowedNetworkHosts
        sb.append(",\"allowedNetworkHosts\":");
        appendSortedList(sb, p.allowedNetworkHosts());

        // 3. allowedTools
        sb.append(",\"allowedTools\":");
        appendSortedList(sb, p.allowedTools());

        // 4. approvalTimeoutMinutes
        sb.append(",\"approvalTimeoutMinutes\":").append(p.approvalTimeoutMinutes());

        // 5. auditEnabled
        sb.append(",\"auditEnabled\":").append(p.auditEnabled());

        // 6. estimatedTokens (nullable)
        sb.append(",\"estimatedTokens\":");
        appendNullableInt(sb, p.estimatedTokens());

        // 7. maxTokenBudget (nullable)
        sb.append(",\"maxTokenBudget\":");
        appendNullableInt(sb, p.maxTokenBudget());

        // 8. ownedPaths
        sb.append(",\"ownedPaths\":");
        appendSortedList(sb, p.ownedPaths());

        // 9. requiredHumanApproval (nullable enum)
        sb.append(",\"requiredHumanApproval\":");
        appendNullableEnum(sb, p.requiredHumanApproval());

        // 10. riskLevel (nullable enum)
        sb.append(",\"riskLevel\":");
        appendNullableEnum(sb, p.riskLevel());

        // 11. shouldSnapshot
        sb.append(",\"shouldSnapshot\":").append(p.shouldSnapshot());

        sb.append('}');
        return sb.toString();
    }

    private static void appendSortedList(StringBuilder sb, List<String> list) {
        if (list == null || list.isEmpty()) {
            sb.append("[]");
            return;
        }
        List<String> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        sb.append('[');
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJsonString(sorted.get(i))).append('"');
        }
        sb.append(']');
    }

    private static void appendNullableInt(StringBuilder sb, Integer value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value.intValue());
        }
    }

    private static void appendNullableEnum(StringBuilder sb, Enum<?> value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(value.name()).append('"');
        }
    }

    /**
     * Escapes special characters for JSON string values.
     * Handles: backslash, double-quote, control characters.
     */
    private static String escapeJsonString(String s) {
        if (s == null) return "";
        StringBuilder escaped = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"'  -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default   -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
