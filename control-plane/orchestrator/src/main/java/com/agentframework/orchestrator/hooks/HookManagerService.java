package com.agentframework.orchestrator.hooks;

import com.agentframework.common.policy.HookPolicy;
import com.agentframework.orchestrator.domain.WorkerType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 */
@Service
public class HookManagerService {

    private static final Logger log = LoggerFactory.getLogger(HookManagerService.class);

    /** planId → (taskKey → HookPolicy) */
    private final Map<UUID, Map<String, HookPolicy>> policiesByPlan = new ConcurrentHashMap<>();

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

            Map<String, HookPolicy> policyMap = new ConcurrentHashMap<>();
            policies.fields().forEachRemaining(entry -> {
                try {
                    HookPolicy policy = objectMapper.treeToValue(entry.getValue(), HookPolicy.class);
                    policyMap.put(entry.getKey(), policy);
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
        Map<String, HookPolicy> planPolicies = policiesByPlan.get(planId);
        if (planPolicies != null) {
            HookPolicy taskPolicy = planPolicies.get(taskKey);
            if (taskPolicy != null) {
                log.debug("Using HM-generated HookPolicy for task {} (plan={})", taskKey, planId);
                return Optional.of(taskPolicy);
            }
        }

        // Fallback: resolve from static config by worker type
        Optional<HookPolicy> fallback = fallbackResolver.resolve(workerType);
        fallback.ifPresent(p ->
            log.debug("Using static HookPolicy fallback for task {} (workerType={})", taskKey, workerType));
        return fallback;
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
