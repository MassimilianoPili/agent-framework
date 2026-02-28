package com.agentframework.orchestrator.reward;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Core reward computation service.
 *
 * <p>Aggregates three orthogonal reward sources into a single {@code aggregatedReward}
 * for each {@link PlanItem}. All computation is zero-cost (no extra LLM calls):
 * <ul>
 *   <li><b>processScore</b> (weight 0.30) — deterministic from Provenance metrics</li>
 *   <li><b>reviewScore</b> (weight 0.50) — parsed from REVIEW worker JSON output</li>
 *   <li><b>qualityGateScore</b> (weight 0.20) — fallback from binary QualityGateReport</li>
 * </ul>
 * Weights are re-normalised when sources are unavailable (e.g. no REVIEW task in the plan).
 * </p>
 */
@Service
public class RewardComputationService {

    private static final Logger log = LoggerFactory.getLogger(RewardComputationService.class);

    private final PlanItemRepository planItemRepository;
    private final ObjectMapper objectMapper;

    public RewardComputationService(PlanItemRepository planItemRepository,
                                    ObjectMapper objectMapper) {
        this.planItemRepository = planItemRepository;
        this.objectMapper = objectMapper;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Computes and persists the processScore for a task that just transitioned to DONE.
     * Called by OrchestrationService immediately after {@code item.transitionTo(DONE)}.
     *
     * @param item   the completed PlanItem (not yet saved)
     * @param result the AgentResult carrying timing and token usage
     */
    @Transactional
    public void computeProcessScore(PlanItem item, AgentResult result) {
        float score = calculateProcessScore(result, item);
        item.setProcessScore(score);
        recomputeAggregatedReward(item);
        planItemRepository.save(item);
        log.debug("processScore={} aggregatedReward={} (task={}, plan={})",
                  score, item.getAggregatedReward(), item.getTaskKey(), result.planId());
    }

    /**
     * Parses the REVIEW worker's JSON result and distributes per-task review scores
     * to the items referenced in {@code per_task}. Falls back to broadcast (same score
     * for all items in the plan's dependency graph) when {@code per_task} is absent.
     *
     * <p>Called by OrchestrationService when {@code item.getWorkerType() == WorkerType.REVIEW}.</p>
     */
    @Transactional
    public void distributeReviewScore(PlanItem reviewItem) {
        String resultJson = reviewItem.getResult();
        if (resultJson == null || resultJson.isBlank()) {
            log.warn("REVIEW item {} has no result — skipping review score distribution", reviewItem.getTaskKey());
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(resultJson);
            UUID planId = reviewItem.getPlan().getId();

            if (root.has("per_task") && root.get("per_task").isObject()) {
                distributePerTask(root.get("per_task"), planId);
            } else {
                // Fallback: broadcast global severity to all non-REVIEW items in the plan
                float globalScore = severityToScore(root.has("severity") ? root.get("severity").asText() : "PASS");
                broadcastReviewScore(planId, globalScore, reviewItem.getTaskKey());
            }
        } catch (Exception e) {
            log.warn("Failed to parse REVIEW result for task {}: {}", reviewItem.getTaskKey(), e.getMessage());
        }
    }

    /**
     * Applies the binary quality gate signal as a fallback reward source for items
     * that do not yet have a reviewScore (i.e. were not individually reviewed).
     *
     * <p>Called by QualityGateService after saving the QualityGateReport.</p>
     */
    @Transactional
    public void distributeQualityGateSignal(UUID planId, boolean passed) {
        float qgScore = passed ? 1.0f : -1.0f;
        List<PlanItem> items = planItemRepository.findByPlanId(planId);

        int updated = 0;
        for (PlanItem item : items) {
            // Only apply qualityGate as fallback where reviewScore is absent
            if (item.getStatus() == ItemStatus.DONE && item.getReviewScore() == null) {
                // Inject qualityGate into the rewardSources JSON, then recompute aggregate
                injectQualityGateScore(item, qgScore);
                recomputeAggregatedReward(item);
                planItemRepository.save(item);
                updated++;
            }
        }

        log.info("Quality gate signal ({}) applied to {}/{} items without review scores (plan={})",
                 passed ? "PASS" : "FAIL", updated, items.size(), planId);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Deterministic processScore from Provenance metrics. Range: [0.0, 1.0].
     *
     * <pre>
     *   tokenEff     = 1 / log₁₀(tokensUsed + 10)   — penalises verbosity logarithmically
     *   retryPenalty = max(0, 1 - retries × 0.25)    — each retry costs 0.25 points
     *   durationEff  = sigmoid(-(ms - 60_000)/30_000) — ~1.0 if <1min, ~0.5 at 2min, ~0 if >5min
     * </pre>
     */
    private float calculateProcessScore(AgentResult result, PlanItem item) {
        long tokens = 0L;
        if (result.tokensUsed() != null) {
            tokens = result.tokensUsed();
        } else if (result.provenance() != null
                && result.provenance().tokenUsage() != null
                && result.provenance().tokenUsage().totalTokens() != null) {
            tokens = result.provenance().tokenUsage().totalTokens();
        }

        float tokenEff = tokens > 0
                ? (float) (1.0 / Math.log10(tokens + 10.0))
                : 0.5f;   // neutral when unknown

        float retryPenalty = Math.max(0f, 1f - item.getContextRetryCount() * 0.25f);

        long ms = result.durationMs();
        float durationEff = (float) (1.0 / (1.0 + Math.exp((ms - 60_000.0) / 30_000.0)));

        return tokenEff * 0.4f + retryPenalty * 0.3f + durationEff * 0.3f;
    }

    /**
     * Distributes review scores to items listed in the {@code per_task} JSON map.
     * Expected format: {@code {"BE-001": {"score": 0.8, "issues": [...]}}}
     */
    private void distributePerTask(JsonNode perTask, UUID planId) {
        List<PlanItem> planItems = planItemRepository.findByPlanId(planId);
        Map<String, PlanItem> byKey = new HashMap<>();
        for (PlanItem pi : planItems) {
            byKey.put(pi.getTaskKey(), pi);
        }

        int assigned = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = perTask.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String taskKey = entry.getKey();
            JsonNode taskNode = entry.getValue();

            PlanItem target = byKey.get(taskKey);
            if (target == null) {
                log.debug("REVIEW per_task references unknown task key '{}' (plan={})", taskKey, planId);
                continue;
            }

            float score = taskNode.has("score")
                    ? (float) taskNode.get("score").asDouble()
                    : severityToScore(taskNode.has("severity") ? taskNode.get("severity").asText() : "PASS");

            score = Math.max(-1.0f, Math.min(1.0f, score)); // clamp to [-1,+1]
            target.setReviewScore(score);
            recomputeAggregatedReward(target);
            planItemRepository.save(target);
            assigned++;
        }

        log.info("Distributed per-task review scores to {}/{} items (plan={})", assigned, byKey.size(), planId);
    }

    /**
     * Broadcasts a single review score to all non-REVIEW DONE items in the plan.
     */
    private void broadcastReviewScore(UUID planId, float score, String reviewTaskKey) {
        List<PlanItem> items = planItemRepository.findByPlanId(planId);
        int assigned = 0;
        for (PlanItem item : items) {
            if (item.getStatus() == ItemStatus.DONE
                    && item.getWorkerType() != WorkerType.REVIEW
                    && !item.getTaskKey().equals(reviewTaskKey)) {
                item.setReviewScore(score);
                recomputeAggregatedReward(item);
                planItemRepository.save(item);
                assigned++;
            }
        }
        log.info("Broadcast review score {} to {} items (plan={})", score, assigned, planId);
    }

    /**
     * Parses the existing rewardSources JSON for this item and injects the qualityGate score.
     */
    private void injectQualityGateScore(PlanItem item, float qgScore) {
        try {
            ObjectNode sources = item.getRewardSources() != null
                    ? (ObjectNode) objectMapper.readTree(item.getRewardSources())
                    : objectMapper.createObjectNode();
            sources.put("quality_gate", qgScore);
            item.setRewardSources(objectMapper.writeValueAsString(sources));
        } catch (Exception e) {
            log.warn("Failed to inject quality_gate score into rewardSources for {}: {}", item.getTaskKey(), e.getMessage());
        }
    }

    /**
     * Recomputes the Bayesian-weighted aggregate and updates both
     * {@code aggregatedReward} and {@code rewardSources} on the item.
     */
    private void recomputeAggregatedReward(PlanItem item) {
        Float review = item.getReviewScore();
        Float process = item.getProcessScore();
        Float qualityGate = extractQualityGateFromSources(item.getRewardSources());

        float[] rawWeights = { review != null ? 0.50f : 0f,
                               process != null ? 0.30f : 0f,
                               qualityGate != null ? 0.20f : 0f };
        float totalWeight = rawWeights[0] + rawWeights[1] + rawWeights[2];

        float aggregated = 0f;
        if (totalWeight > 0f) {
            float[] scores = { review != null ? review : 0f,
                               process != null ? process : 0f,
                               qualityGate != null ? qualityGate : 0f };
            float weighted = 0f;
            for (int i = 0; i < 3; i++) weighted += rawWeights[i] * scores[i];
            aggregated = weighted / totalWeight;
        }

        item.setAggregatedReward(aggregated);

        // Persist source snapshot for auditability
        try {
            ObjectNode sources = objectMapper.createObjectNode();
            if (review != null) sources.put("review", review);
            else sources.putNull("review");
            if (process != null) sources.put("process", process);
            else sources.putNull("process");
            if (qualityGate != null) sources.put("quality_gate", qualityGate);
            else sources.putNull("quality_gate");

            ObjectNode weights = objectMapper.createObjectNode();
            if (totalWeight > 0) {
                if (review != null) weights.put("review", rawWeights[0] / totalWeight);
                if (process != null) weights.put("process", rawWeights[1] / totalWeight);
                if (qualityGate != null) weights.put("quality_gate", rawWeights[2] / totalWeight);
            }
            sources.set("weights", weights);
            item.setRewardSources(objectMapper.writeValueAsString(sources));
        } catch (Exception e) {
            log.warn("Failed to serialize rewardSources for task {}: {}", item.getTaskKey(), e.getMessage());
        }
    }

    private Float extractQualityGateFromSources(String rewardSourcesJson) {
        if (rewardSourcesJson == null) return null;
        try {
            JsonNode node = objectMapper.readTree(rewardSourcesJson);
            if (node.has("quality_gate") && !node.get("quality_gate").isNull()) {
                return (float) node.get("quality_gate").asDouble();
            }
        } catch (Exception e) {
            log.debug("Failed to extract quality_gate from rewardSources: {}", e.getMessage());
        }
        return null;
    }

    /** Maps REVIEW worker severity strings to scalar scores. */
    private float severityToScore(String severity) {
        return switch (severity.toUpperCase()) {
            case "PASS" -> 1.0f;
            case "WARN" -> 0.0f;
            case "FAIL" -> -1.0f;
            default -> 0.0f;
        };
    }
}
