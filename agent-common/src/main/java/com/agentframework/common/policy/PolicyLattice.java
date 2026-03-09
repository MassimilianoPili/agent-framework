package com.agentframework.common.policy;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Meet-semilattice for {@link HookPolicy} composition (#39).
 *
 * <p>{@code meet(a, b)} produces the most restrictive policy satisfying both {@code a}
 * and {@code b}. The operation is idempotent, commutative, and associative — composition
 * is deterministic regardless of source ordering.</p>
 *
 * <h3>Composition rules per field:</h3>
 * <ul>
 *   <li>Lists (tools, paths, servers, hosts): <b>intersection</b> (wildcard {@code "*"} = permit all)</li>
 *   <li>Booleans (audit, snapshot): <b>OR</b> (true if either requires it)</li>
 *   <li>Nullable integers (budget, tokens): <b>min</b> (null = no limit)</li>
 *   <li>Enums (risk, approval): <b>max ordinal</b> (higher = more restrictive)</li>
 *   <li>Timeout: <b>min</b> (stricter timeout wins)</li>
 * </ul>
 *
 * <p>Not a Spring bean — pure static utility, usable in agent-common without Spring context.</p>
 */
public final class PolicyLattice {

    private static final String WILDCARD = "*";

    /** Top element (⊤): permits everything. Identity of meet: {@code meet(TOP, x) = x}. */
    public static final HookPolicy TOP = new HookPolicy(
            List.of(WILDCARD), List.of(WILDCARD), List.of(WILDCARD), false,
            null, List.of(WILDCARD), ApprovalMode.NONE, Integer.MAX_VALUE,
            RiskLevel.LOW, null, false);

    /** Bottom element (⊥): forbids everything. Absorbing: {@code meet(BOTTOM, x) = BOTTOM}. */
    public static final HookPolicy BOTTOM = new HookPolicy(
            List.of(), List.of(), List.of(), true,
            0, List.of(), ApprovalMode.BLOCK, 0,
            RiskLevel.CRITICAL, 0, true);

    private PolicyLattice() {}

    /**
     * Meet operation (⊓): returns the greatest lower bound (most restrictive policy
     * satisfying both {@code a} and {@code b}).
     *
     * @param a first policy
     * @param b second policy
     * @return the most restrictive composition of a and b
     */
    public static HookPolicy meet(HookPolicy a, HookPolicy b) {
        return new HookPolicy(
                intersect(a.allowedTools(), b.allowedTools()),
                intersect(a.ownedPaths(), b.ownedPaths()),
                intersect(a.allowedMcpServers(), b.allowedMcpServers()),
                a.auditEnabled() || b.auditEnabled(),
                minNullable(a.maxTokenBudget(), b.maxTokenBudget()),
                intersect(a.allowedNetworkHosts(), b.allowedNetworkHosts()),
                maxEnum(a.requiredHumanApproval(), b.requiredHumanApproval(), ApprovalMode.NONE),
                Math.min(a.approvalTimeoutMinutes(), b.approvalTimeoutMinutes()),
                maxEnum(a.riskLevel(), b.riskLevel(), RiskLevel.LOW),
                minNullable(a.estimatedTokens(), b.estimatedTokens()),
                a.shouldSnapshot() || b.shouldSnapshot()
        );
    }

    /**
     * Composes multiple policies via iterated meet. Null policies are skipped.
     * Returns {@link #TOP} if no non-null policies are provided.
     *
     * @param policies variable number of policies (nulls allowed)
     * @return the most restrictive composition of all non-null inputs
     */
    public static HookPolicy compose(HookPolicy... policies) {
        return Arrays.stream(policies)
                .filter(Objects::nonNull)
                .reduce(TOP, PolicyLattice::meet);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * List intersection with wildcard support.
     * A list containing {@code "*"} is treated as TOP (permits everything).
     */
    static List<String> intersect(List<String> a, List<String> b) {
        if (a.contains(WILDCARD)) return List.copyOf(b);
        if (b.contains(WILDCARD)) return List.copyOf(a);

        Set<String> setB = new LinkedHashSet<>(b);
        List<String> result = a.stream()
                .filter(setB::contains)
                .collect(Collectors.toList());
        return List.copyOf(result);
    }

    /**
     * Minimum of two nullable integers. Null means "no limit" (= +∞ in the lattice).
     */
    static Integer minNullable(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }

    /**
     * Maximum of two enums by ordinal (higher ordinal = more restrictive).
     * Null is treated as the default value (least restrictive).
     */
    static <E extends Enum<E>> E maxEnum(E a, E b, E defaultValue) {
        E effectiveA = a != null ? a : defaultValue;
        E effectiveB = b != null ? b : defaultValue;
        return effectiveA.ordinal() >= effectiveB.ordinal() ? effectiveA : effectiveB;
    }
}
