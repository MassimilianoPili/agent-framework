package com.agentframework.orchestrator.hooks;

import com.agentframework.common.policy.ApprovalMode;
import com.agentframework.common.policy.HookPolicy;
import com.agentframework.common.policy.PolicyHasher;
import com.agentframework.common.policy.RiskLevel;
import com.agentframework.orchestrator.domain.WorkerType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime service that bridges the HOOK_MANAGER worker result to the dispatch pipeline.
 *
 * <p>When the HOOK_MANAGER worker completes, its result JSON contains a
 * {@code "policies"} map of {@code taskKey → HookPolicy}. This service stores
 * those policies keyed by planId and makes them available to
 * {@link com.agentframework.orchestrator.orchestration.OrchestrationService}
 * when dispatching subsequent tasks.</p>
 *
 * <p>Storage is in-memory (ConcurrentHashMap). Plans are evicted after completion
 * to prevent memory growth. This is intentional: if the orchestrator restarts,
 * the HM worker's result is re-read from the database via the completed results
 * map in {@code dispatchReadyItems()}.</p>
 *
 * <p>Each stored policy is paired with its SHA-256 commitment hash (#32), computed
 * at storage time via {@link PolicyHasher}. The hash is propagated in
 * {@code AgentTask.policyHash} and verified by the worker before enforcement.</p>
 */
@Service
public class HookManagerService {

    private static final Logger log = LoggerFactory.getLogger(HookManagerService.class);

    /**
     * A policy paired with its SHA-256 commitment hash (#32).
     *
     * @param policy the HookPolicy
     * @param hash   SHA-256 hex digest of the policy's canonical JSON
     */
    public record HashedPolicy(HookPolicy policy, String hash) {}

    /** planId → (taskKey → HashedPolicy) */
    private final Map<UUID, Map<String, HashedPolicy>> policiesByPlan = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final HookPolicyResolver fallbackResolver;

    public HookManagerService(ObjectMapper objectMapper, HookPolicyResolver fallbackResolver) {
        this.objectMapper = objectMapper;
        this.fallbackResolver = fallbackResolver;
    }

    /**
     * Parses and stores HookPolicy entries from a HOOK_MANAGER worker result.
     *
     * <p>Expected result format:</p>
     * <pre>{@code
     * {
     *   "policies": {
     *     "be-task-001": { "allowedTools": [...], "ownedPaths": [...], "allowedMcpServers": [...], "auditEnabled": true },
     *     "fe-task-002": { ... }
     *   }
     * }
     * }</pre>
     *
     * @param planId    the plan this HM result belongs to
     * @param resultJson the HM worker's AgentResult.resultJson
     */
    public void storePolicies(UUID planId, String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            log.warn("HookManager result for plan {} is empty — no policies stored", planId);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            JsonNode policies = root.get("policies");
            if (policies == null || !policies.isObject()) {
                log.warn("HookManager result for plan {} has no 'policies' object — no policies stored", planId);
                return;
            }

            Map<String, HashedPolicy> policyMap = new ConcurrentHashMap<>();
            policies.fields().forEachRemaining(entry -> {
                try {
                    HookPolicy policy = objectMapper.treeToValue(entry.getValue(), HookPolicy.class);
                    String hash = PolicyHasher.hash(policy);
                    policyMap.put(entry.getKey(), new HashedPolicy(policy, hash));
                    log.debug("Policy hash for task {} (plan {}): {}", entry.getKey(), planId, hash);
                } catch (Exception e) {
                    log.warn("Failed to parse HookPolicy for task '{}' in plan {}: {}",
                             entry.getKey(), planId, e.getMessage());
                }
            });

            policiesByPlan.put(planId, policyMap);
            log.info("Stored {} HookPolicies for plan {} from HOOK_MANAGER result", policyMap.size(), planId);

        } catch (Exception e) {
            log.error("Failed to parse HookManager result for plan {}: {}", planId, e.getMessage());
        }
    }

    /**
     * Resolves the HookPolicy for a specific task.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Task-level policy from stored HM worker result (most specific)</li>
     *   <li>Static fallback from {@link HookPolicyResolver} by worker type</li>
     * </ol>
     *
     * @param planId     the plan the task belongs to
     * @param taskKey    the task key to look up
     * @param workerType the worker type (used for fallback resolution)
     * @return the resolved HookPolicy, or empty if no policy applies
     */
    public Optional<HookPolicy> resolvePolicy(UUID planId, String taskKey, WorkerType workerType) {
        return resolvePolicyWithHash(planId, taskKey, workerType)
                .map(HashedPolicy::policy);
    }

    /**
     * Resolves the HookPolicy and its commitment hash for a specific task (#32).
     *
     * <p>Same resolution order as {@link #resolvePolicy(UUID, String, WorkerType)},
     * but returns the {@link HashedPolicy} pair (policy + SHA-256 hash).</p>
     *
     * @param planId     the plan the task belongs to
     * @param taskKey    the task key to look up
     * @param workerType the worker type (used for fallback resolution)
     * @return the resolved policy with hash, or empty if no policy applies
     */
    public Optional<HashedPolicy> resolvePolicyWithHash(UUID planId, String taskKey, WorkerType workerType) {
        Map<String, HashedPolicy> planPolicies = policiesByPlan.get(planId);
        if (planPolicies != null) {
            HashedPolicy hashed = planPolicies.get(taskKey);
            if (hashed != null) {
                log.debug("Using HM-generated HookPolicy for task {} (plan={})", taskKey, planId);
                return Optional.of(hashed);
            }
        }

        // Fallback: resolve from static config by worker type, compute hash on-the-fly
        Optional<HookPolicy> fallback = fallbackResolver.resolve(workerType);
        return fallback.map(p -> {
            log.debug("Using static HookPolicy fallback for task {} (workerType={})", taskKey, workerType);
            return new HashedPolicy(p, PolicyHasher.hash(p));
        });
    }

    /**
     * Parses and stores a per-task HookPolicy from a TOOL_MANAGER worker result.
     *
     * <p>Unlike {@link #storePolicies(UUID, String)}, which replaces the entire plan-level
     * policy map, this method performs a <em>per-task merge</em>: only the target task's
     * entry is written. Policies for other tasks (e.g., set by a previous HOOK_MANAGER
     * result) are preserved. This allows TOOL_MANAGER results to coexist with and
     * override HOOK_MANAGER results on a per-task basis.</p>
     *
     * <p>Expected result format:</p>
     * <pre>{@code
     * {
     *   "target_task_key":   "BE-001",
     *   "allowedTools":      ["fs_read", "fs_write", "fs_list"],
     *   "ownedPaths":        ["/workspace/plan/src/"],
     *   "allowedMcpServers": ["repo-fs"],
     *   "rationale":         "BE task generates Java files — needs read + write access"
     * }
     * }</pre>
     *
     * @param planId     the plan this TM result belongs to
     * @param resultJson the TOOL_MANAGER worker's AgentResult.resultJson
     */
    public void storeToolManagerResult(UUID planId, String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            log.warn("ToolManager result for plan {} is empty — no policy stored", planId);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            String targetTaskKey = root.path("target_task_key").asText(null);
            if (targetTaskKey == null || targetTaskKey.isBlank()) {
                log.warn("ToolManager result for plan {} has no 'target_task_key' — no policy stored", planId);
                return;
            }

            List<String> allowedTools      = parseStringList(root, "allowedTools");
            List<String> ownedPaths        = parseStringList(root, "ownedPaths");
            List<String> allowedMcpServers = parseStringList(root, "allowedMcpServers");

            HookPolicy policy = new HookPolicy(
                    allowedTools, ownedPaths, allowedMcpServers, true,
                    null, List.of(), ApprovalMode.NONE, 0, RiskLevel.LOW, null, false);
            String hash = PolicyHasher.hash(policy);

            policiesByPlan.computeIfAbsent(planId, k -> new ConcurrentHashMap<>())
                          .put(targetTaskKey, new HashedPolicy(policy, hash));

            log.info("Tool Manager policy stored for task {} (plan {}): {} tools, {} paths, hash={}",
                     targetTaskKey, planId, allowedTools.size(), ownedPaths.size(), hash);

        } catch (Exception e) {
            log.warn("Failed to parse ToolManager result for plan {} — policy not stored: {}",
                     planId, e.getMessage());
        }
    }

    private List<String> parseStringList(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(n -> result.add(n.asText()));
        return result;
    }

    /**
     * Dynamically updates the HookPolicy for a specific task at runtime (#10 L3).
     *
     * <p>Allows operators or automated systems to tighten or modify policy constraints
     * while a plan is executing. The new policy replaces the existing one (from HM, TM,
     * or static fallback) and its commitment hash is recomputed.</p>
     *
     * <p>Use cases:</p>
     * <ul>
     *   <li>Tightening permissions after a violation is detected</li>
     *   <li>Relaxing constraints after manual review of a blocked task</li>
     *   <li>Adding network host restrictions discovered during execution</li>
     * </ul>
     *
     * @param planId  the plan
     * @param taskKey the task key to update
     * @param policy  the new HookPolicy
     * @return the hashed policy that was stored
     */
    public HashedPolicy updatePolicy(UUID planId, String taskKey, HookPolicy policy) {
        String hash = PolicyHasher.hash(policy);
        HashedPolicy hashed = new HashedPolicy(policy, hash);
        policiesByPlan.computeIfAbsent(planId, k -> new ConcurrentHashMap<>())
                      .put(taskKey, hashed);
        log.info("Runtime policy update for task {} (plan {}): hash={}", taskKey, planId, hash);
        return hashed;
    }

    /**
     * Returns the current policy for a task, if one is stored (HM, TM, or runtime update).
     * Does not fall back to static — use {@link #resolvePolicy} for full resolution.
     *
     * @param planId  the plan
     * @param taskKey the task key
     * @return the stored policy, or empty if none
     */
    public Optional<HashedPolicy> getStoredPolicy(UUID planId, String taskKey) {
        Map<String, HashedPolicy> planPolicies = policiesByPlan.get(planId);
        if (planPolicies == null) return Optional.empty();
        return Optional.ofNullable(planPolicies.get(taskKey));
    }

    /**
     * Removes all stored policies for a completed plan (memory cleanup).
     *
     * @param planId the plan whose policies can be discarded
     */
    public void evictPlan(UUID planId) {
        policiesByPlan.remove(planId);
        log.debug("Evicted HookPolicy cache for completed plan {}", planId);
    }
}
