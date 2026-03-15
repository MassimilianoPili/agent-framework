package com.agentframework.orchestrator.analytics.mast;

import com.agentframework.orchestrator.analytics.mast.MastTaxonomy.FailureClassification;
import com.agentframework.orchestrator.analytics.mast.MastTaxonomy.FailureMode;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Heuristic detectors for each MAST failure mode.
 *
 * <p>Each detector analyzes the failed item and/or plan state to determine
 * if a specific failure mode is present. Detectors return an
 * {@link Optional} classification with confidence score.</p>
 *
 * <p>Detection is pattern-based (heuristic), not ML-based — this keeps the
 * system transparent and debuggable. Confidence scores reflect how strongly
 * the evidence matches each pattern.</p>
 */
public final class MastDetectors {

    private MastDetectors() {}

    // ── FC1: Specification Failures ──────────────────────────────────────────

    /**
     * FM1: Ambiguous specification — spec is too short or too vague.
     *
     * <p>Heuristic: spec length &lt; minLength, or contains many question marks,
     * or error message mentions "ambiguous" / "unclear".</p>
     */
    public static Optional<FailureClassification> detectAmbiguousSpec(
            PlanItem item, Plan plan, int minSpecLength) {

        String spec = plan.getSpec();
        if (spec == null) return Optional.empty();

        double confidence = 0.0;
        List<String> evidence = new ArrayList<>();

        // Short spec
        if (spec.length() < minSpecLength) {
            confidence += 0.4;
            evidence.add("spec length " + spec.length() + " < " + minSpecLength);
        }

        // Question marks in spec suggest uncertainty
        long questionMarks = spec.chars().filter(c -> c == '?').count();
        if (questionMarks >= 2) {
            confidence += 0.2;
            evidence.add(questionMarks + " question marks in spec");
        }

        // Error message patterns
        String error = extractErrorMessage(item);
        if (error != null && (error.contains("ambiguous") || error.contains("unclear")
                || error.contains("not specified") || error.contains("which one"))) {
            confidence += 0.4;
            evidence.add("error mentions ambiguity: " + truncate(error, 100));
        }

        if (confidence >= 0.4) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM1_AMBIGUOUS_SPEC,
                    Math.min(confidence, 1.0),
                    String.join("; ", evidence)));
        }
        return Optional.empty();
    }

    /**
     * FM2: Incomplete requirements — worker reports missing information.
     */
    public static Optional<FailureClassification> detectIncompleteRequirements(PlanItem item) {
        String error = extractErrorMessage(item);
        if (error == null) return Optional.empty();

        if (error.contains("missing_context") || error.contains("need more information")
                || error.contains("not found") || error.contains("no such file")) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM2_INCOMPLETE_REQ, 0.7,
                    "worker error indicates missing information: " + truncate(error, 100)));
        }
        return Optional.empty();
    }

    // ── FC2: Inter-Agent Failures ────────────────────────────────────────────

    /**
     * FM5: Communication breakdown — missing_context status.
     */
    public static Optional<FailureClassification> detectCommunicationBreakdown(PlanItem item) {
        // Check if the item has the specific missing_context marker
        String result = item.getResult();
        if (result != null && result.contains("missing_context")) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM5_COMMUNICATION_BREAKDOWN, 0.9,
                    "worker explicitly reported missing_context"));
        }

        String error = extractErrorMessage(item);
        if (error != null && error.contains("dependency result not available")) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM5_COMMUNICATION_BREAKDOWN, 0.8,
                    "dependency result unavailable: " + truncate(error, 100)));
        }
        return Optional.empty();
    }

    /**
     * FM6: Coordination deadlock — circular dependencies in the plan.
     *
     * <p>Detects cycles using DFS on the dependency graph. A cycle means
     * no task in the cycle can progress, causing permanent deadlock.</p>
     */
    public static Optional<FailureClassification> detectCoordinationDeadlock(Plan plan) {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (PlanItem item : plan.getItems()) {
            deps.put(item.getTaskKey(), new LinkedHashSet<>(item.getDependsOn()));
        }

        // DFS cycle detection
        Set<String> visited = new LinkedHashSet<>();
        Set<String> inStack = new LinkedHashSet<>();

        for (String node : deps.keySet()) {
            if (hasCycle(node, deps, visited, inStack)) {
                return Optional.of(new FailureClassification(
                        FailureMode.FM6_COORDINATION_DEADLOCK, 0.95,
                        "circular dependency detected in DAG"));
            }
        }
        return Optional.empty();
    }

    /**
     * FM7: Resource contention — token budget exhausted.
     */
    public static Optional<FailureClassification> detectResourceContention(PlanItem item) {
        String error = extractErrorMessage(item);
        if (error == null) return Optional.empty();

        if (error.contains("token budget") || error.contains("budget exceeded")
                || error.contains("rate limit") || error.contains("429")) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM7_RESOURCE_CONTENTION, 0.85,
                    "resource exhaustion detected: " + truncate(error, 100)));
        }
        return Optional.empty();
    }

    /**
     * FM8: Protocol violation — unexpected response format.
     */
    public static Optional<FailureClassification> detectProtocolViolation(PlanItem item) {
        String error = extractErrorMessage(item);
        if (error == null) return Optional.empty();

        if (error.contains("JSON") || error.contains("parse error")
                || error.contains("unexpected format") || error.contains("deserialization")) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM8_PROTOCOL_VIOLATION, 0.8,
                    "protocol/format violation: " + truncate(error, 100)));
        }
        return Optional.empty();
    }

    /**
     * FM9: Trust degradation — worker ELO below threshold.
     */
    public static Optional<FailureClassification> detectTrustDegradation(
            PlanItem item, double eloThreshold) {
        // ELO is tracked per worker profile, not directly on PlanItem
        // This detector checks context retry count as a proxy for trust degradation
        int retries = item.getContextRetryCount();
        if (retries >= 3) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM9_TRUST_DEGRADATION, 0.6,
                    "task retried " + retries + " times — possible trust degradation",
                    Map.of("retryCount", retries)));
        }
        return Optional.empty();
    }

    // ── FC3: Emergent Failures ───────────────────────────────────────────────

    /**
     * FM10: Cascading failure — chain of failures along dependency edges.
     */
    public static Optional<FailureClassification> detectCascadingFailure(
            Plan plan, PlanItem failedItem, int maxDepth) {

        Map<String, PlanItem> itemsByKey = plan.getItems().stream()
                .collect(Collectors.toMap(PlanItem::getTaskKey, i -> i, (a, b) -> a));

        // Count consecutive failures along the dependency chain
        int chainLength = countFailureChain(failedItem, itemsByKey, 0, maxDepth);

        if (chainLength >= 2) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM10_CASCADING_FAILURE,
                    Math.min(0.5 + chainLength * 0.15, 0.95),
                    "failure chain of length " + chainLength + " along dependencies",
                    Map.of("chainLength", chainLength)));
        }
        return Optional.empty();
    }

    /**
     * FM11: Oscillation — Ralph-Loop or self-refine non-convergence.
     *
     * <p>Uses the FlipRateMonitor's flip rate if available,
     * otherwise checks retry count as a proxy.</p>
     */
    public static Optional<FailureClassification> detectOscillation(
            PlanItem item, double flipRate, int oscillationWindow) {

        if (flipRate > 0.5) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM11_OSCILLATION, 0.85,
                    "flip rate " + String.format("%.4f", flipRate) + " > 0.5 — non-convergent refinement",
                    Map.of("flipRate", flipRate)));
        }

        // Fallback: high retry count without improvement suggests oscillation
        int retries = item.getContextRetryCount();
        if (retries >= oscillationWindow) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM11_OSCILLATION, 0.5,
                    retries + " retries (>= " + oscillationWindow + ") — possible oscillation"));
        }
        return Optional.empty();
    }

    /**
     * FM13: Partial completion — some tasks done, plan stuck.
     */
    public static Optional<FailureClassification> detectPartialCompletion(
            Plan plan, double threshold) {

        long totalItems = plan.getItems().size();
        if (totalItems == 0) return Optional.empty();

        long doneItems = plan.getItems().stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE)
                .count();
        long failedItems = plan.getItems().stream()
                .filter(i -> i.getStatus() == ItemStatus.FAILED)
                .count();
        long stuckItems = plan.getItems().stream()
                .filter(i -> i.getStatus() == ItemStatus.WAITING || i.getStatus() == ItemStatus.DISPATCHED)
                .count();

        double doneRatio = (double) doneItems / totalItems;
        boolean hasFailures = failedItems > 0;
        boolean hasStuck = stuckItems > 0;

        if (doneRatio >= threshold && hasFailures && hasStuck) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM13_PARTIAL_COMPLETION, 0.8,
                    String.format("partial completion: %d/%d done, %d failed, %d stuck",
                            doneItems, totalItems, failedItems, stuckItems),
                    Map.of("doneRatio", doneRatio, "failedCount", failedItems, "stuckCount", stuckItems)));
        }
        return Optional.empty();
    }

    /**
     * FM14: Recovery failure — compensation or retry itself failed.
     */
    public static Optional<FailureClassification> detectRecoveryFailure(PlanItem item) {
        // Check if the item is a compensation task that also failed
        if (item.getWorkerType() != null
                && "COMPENSATOR_MANAGER".equals(item.getWorkerType().name())
                && item.getStatus() == ItemStatus.FAILED) {
            return Optional.of(new FailureClassification(
                    FailureMode.FM14_RECOVERY_FAILURE, 0.9,
                    "compensation task itself failed — meta-failure"));
        }

        // Check for repeated retry failures
        int retries = item.getContextRetryCount();
        if (retries >= 5) {
            String error = extractErrorMessage(item);
            return Optional.of(new FailureClassification(
                    FailureMode.FM14_RECOVERY_FAILURE, 0.7,
                    "task failed after " + retries + " retries — recovery exhausted"
                            + (error != null ? ": " + truncate(error, 80) : "")));
        }
        return Optional.empty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static String extractErrorMessage(PlanItem item) {
        String result = item.getResult();
        if (result == null) return null;
        // Try to extract error field from JSON result
        int errorIdx = result.indexOf("\"error\"");
        if (errorIdx >= 0) {
            int start = result.indexOf(':', errorIdx) + 1;
            int end = Math.min(result.length(), start + 200);
            return result.substring(start, end).trim();
        }
        // Fallback: check if result itself looks like an error
        if (result.startsWith("{") && result.contains("exception")) {
            return truncate(result, 200);
        }
        return null;
    }

    private static boolean hasCycle(String node, Map<String, Set<String>> deps,
                                     Set<String> visited, Set<String> inStack) {
        if (inStack.contains(node)) return true;
        if (visited.contains(node)) return false;

        visited.add(node);
        inStack.add(node);

        for (String dep : deps.getOrDefault(node, Set.of())) {
            if (deps.containsKey(dep) && hasCycle(dep, deps, visited, inStack)) {
                return true;
            }
        }

        inStack.remove(node);
        return false;
    }

    private static int countFailureChain(PlanItem item, Map<String, PlanItem> itemsByKey,
                                          int depth, int maxDepth) {
        if (depth >= maxDepth) return depth;
        if (item.getStatus() != ItemStatus.FAILED) return depth;

        int maxChain = depth;
        for (String depKey : item.getDependsOn()) {
            PlanItem dep = itemsByKey.get(depKey);
            if (dep != null && dep.getStatus() == ItemStatus.FAILED) {
                maxChain = Math.max(maxChain,
                        countFailureChain(dep, itemsByKey, depth + 1, maxDepth));
            }
        }
        return maxChain;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
