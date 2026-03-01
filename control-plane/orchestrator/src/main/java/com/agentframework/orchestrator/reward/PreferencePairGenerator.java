package com.agentframework.orchestrator.reward;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates DPO (Direct Preference Optimization) preference pairs from historical plan data.
 *
 * <p>Three complementary strategies are applied per plan:
 * <ol>
 *   <li><b>same_plan_cross_profile</b> — two profiles executed tasks of the same workerType
 *       in the same plan. The higher-reward result is {@code chosen}, the lower is {@code rejected}.</li>
 *   <li><b>retry_comparison</b> — the same item was attempted multiple times. The final successful
 *       attempt (higher reward) vs. a failed prior attempt constructs the preference signal.</li>
 *   <li><b>gp_residual_surprise</b> — cross-profile pairs filtered by GP residual
 *       {@code |actual - predicted|}. Only generated when GP is enabled. Pairs with high residual
 *       teach the DPO trainer patterns the GP didn't predict — informative surprises.</li>
 * </ol>
 *
 * <p>Only pairs with {@code deltaReward >= MIN_DELTA} (0.3) are persisted, preventing
 * near-identical pairs that add noise to preference learning.</p>
 *
 * <p>Called by {@link com.agentframework.orchestrator.orchestration.QualityGateService}
 * after ELO ratings are updated.</p>
 */
@Service
public class PreferencePairGenerator {

    private static final Logger log = LoggerFactory.getLogger(PreferencePairGenerator.class);
    private static final float MIN_DELTA = 0.3f;
    private static final float MIN_GP_RESIDUAL = 0.15f;
    private static final String GP_RESIDUAL_SOURCE = "gp_residual_surprise";

    private final PlanItemRepository planItemRepository;
    private final PreferencePairRepository pairRepository;
    private final TaskOutcomeService taskOutcomeService; // null when gp.enabled=false

    public PreferencePairGenerator(PlanItemRepository planItemRepository,
                                   PreferencePairRepository pairRepository,
                                   Optional<TaskOutcomeService> taskOutcomeService) {
        this.planItemRepository = planItemRepository;
        this.pairRepository = pairRepository;
        this.taskOutcomeService = taskOutcomeService.orElse(null);
    }

    /**
     * Generates and persists preference pairs for the given plan.
     *
     * @return total number of pairs persisted
     */
    @Transactional
    public int generateForPlan(UUID planId) {
        List<PreferencePair> pairs = new ArrayList<>();
        pairs.addAll(generateCrossProfilePairs(planId));
        pairs.addAll(generateRetryPairs(planId));
        if (taskOutcomeService != null) {
            pairs.addAll(generateGpResidualPairs(planId));
        }

        if (!pairs.isEmpty()) {
            pairRepository.saveAll(pairs);
        }

        log.info("Generated {} preference pairs for plan {} ({} cross-profile, {} retry, {} gp-residual)",
                 pairs.size(), planId,
                 pairs.stream().filter(p -> "same_plan_cross_profile".equals(p.getGenerationSource())).count(),
                 pairs.stream().filter(p -> "retry_comparison".equals(p.getGenerationSource())).count(),
                 pairs.stream().filter(p -> GP_RESIDUAL_SOURCE.equals(p.getGenerationSource())).count());

        return pairs.size();
    }

    // ── Strategy 1: cross-profile comparison ─────────────────────────────────

    private List<PreferencePair> generateCrossProfilePairs(UUID planId) {
        List<PlanItem> items = planItemRepository.findByPlanId(planId).stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE)
                .filter(i -> i.getWorkerProfile() != null)
                .filter(i -> i.getAggregatedReward() != null)
                .filter(i -> i.getResult() != null)
                .collect(Collectors.toList());

        // Group by workerType: only workerTypes with ≥2 different profiles yield pairs
        Map<String, List<PlanItem>> byType = items.stream()
                .collect(Collectors.groupingBy(i -> i.getWorkerType().name()));

        List<PreferencePair> pairs = new ArrayList<>();

        for (Map.Entry<String, List<PlanItem>> entry : byType.entrySet()) {
            List<PlanItem> group = entry.getValue();

            // Further group by profile to get one representative per profile
            Map<String, PlanItem> bestByProfile = new HashMap<>();
            for (PlanItem item : group) {
                bestByProfile.merge(item.getWorkerProfile(), item,
                        (existing, candidate) ->
                                candidate.getAggregatedReward() > existing.getAggregatedReward()
                                        ? candidate : existing);
            }

            if (bestByProfile.size() < 2) continue;

            List<PlanItem> representatives = new ArrayList<>(bestByProfile.values());

            for (int i = 0; i < representatives.size(); i++) {
                for (int j = i + 1; j < representatives.size(); j++) {
                    PlanItem a = representatives.get(i);
                    PlanItem b = representatives.get(j);

                    float rewardA = a.getAggregatedReward();
                    float rewardB = b.getAggregatedReward();

                    PlanItem chosen  = rewardA >= rewardB ? a : b;
                    PlanItem rejected = rewardA >= rewardB ? b : a;
                    float delta = Math.abs(rewardA - rewardB);

                    if (delta < MIN_DELTA) continue;

                    String source = "same_plan_cross_profile";
                    if (pairRepository.existsByTaskKeyAndGenerationSource(chosen.getTaskKey(), source)) {
                        continue; // already generated for this task
                    }

                    pairs.add(new PreferencePair(
                            UUID.randomUUID(), planId,
                            chosen.getTaskKey(), chosen.getWorkerType().name(),
                            buildPrompt(chosen),
                            chosen.getResult(), rejected.getResult(),
                            chosen.getAggregatedReward(), rejected.getAggregatedReward(),
                            source));
                }
            }
        }

        return pairs;
    }

    // ── Strategy 2: retry comparison ─────────────────────────────────────────

    private List<PreferencePair> generateRetryPairs(UUID planId) {
        List<PlanItem> items = planItemRepository.findByPlanId(planId).stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE)
                .filter(i -> i.getContextRetryCount() > 0)
                .filter(i -> i.getAggregatedReward() != null)
                .filter(i -> i.getResult() != null)
                .collect(Collectors.toList());

        List<PreferencePair> pairs = new ArrayList<>();

        for (PlanItem item : items) {
            // The successful (final) result is "chosen"; its reward vs a neutral 0.0 baseline
            // represents the "before vs after context resolution" signal.
            // We use 0.0 as the rejected reward (approximate: failed attempt has unknown quality)
            float chosenReward = item.getAggregatedReward();
            float rejectedReward = -0.5f; // failed/incomplete attempt baseline
            float delta = chosenReward - rejectedReward;

            if (delta < MIN_DELTA) continue;

            String source = "retry_comparison";
            if (pairRepository.existsByTaskKeyAndGenerationSource(item.getTaskKey(), source)) {
                continue;
            }

            String rejectedResult = "{\"status\":\"failed\",\"reason\":\"missing_context\","
                    + "\"retries\":" + item.getContextRetryCount() + "}";

            pairs.add(new PreferencePair(
                    UUID.randomUUID(), planId,
                    item.getTaskKey(), item.getWorkerType().name(),
                    buildPrompt(item),
                    item.getResult(), rejectedResult,
                    chosenReward, rejectedReward,
                    source));
        }

        return pairs;
    }

    // ── Strategy 3: GP residual surprise ─────────────────────────────────────

    /**
     * Generates pairs filtered by GP residual: {@code |actual_reward - gp_predicted|}.
     * Only pairs where at least one item has residual {@code >= MIN_GP_RESIDUAL} are kept.
     * These are "informative surprises" — outcomes the GP didn't predict.
     */
    private List<PreferencePair> generateGpResidualPairs(UUID planId) {
        List<PlanItem> items = planItemRepository.findByPlanId(planId).stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE)
                .filter(i -> i.getWorkerProfile() != null)
                .filter(i -> i.getAggregatedReward() != null)
                .filter(i -> i.getResult() != null)
                .collect(Collectors.toList());

        Map<String, List<PlanItem>> byType = items.stream()
                .collect(Collectors.groupingBy(i -> i.getWorkerType().name()));

        Map<UUID, float[]> embeddingCache = new HashMap<>();
        List<PreferencePair> pairs = new ArrayList<>();

        for (var entry : byType.entrySet()) {
            List<PlanItem> group = entry.getValue();

            Map<String, PlanItem> bestByProfile = new HashMap<>();
            for (PlanItem item : group) {
                bestByProfile.merge(item.getWorkerProfile(), item,
                        (existing, candidate) ->
                                candidate.getAggregatedReward() > existing.getAggregatedReward()
                                        ? candidate : existing);
            }

            if (bestByProfile.size() < 2) continue;

            List<PlanItem> representatives = new ArrayList<>(bestByProfile.values());

            for (int i = 0; i < representatives.size(); i++) {
                for (int j = i + 1; j < representatives.size(); j++) {
                    PlanItem a = representatives.get(i);
                    PlanItem b = representatives.get(j);

                    float residualA = computeResidual(a, embeddingCache);
                    float residualB = computeResidual(b, embeddingCache);
                    float maxResidual = Math.max(residualA, residualB);

                    if (maxResidual < MIN_GP_RESIDUAL) continue;

                    float rewardA = a.getAggregatedReward();
                    float rewardB = b.getAggregatedReward();
                    float delta = Math.abs(rewardA - rewardB);

                    if (delta < MIN_DELTA) continue;

                    PlanItem chosen  = rewardA >= rewardB ? a : b;
                    PlanItem rejected = rewardA >= rewardB ? b : a;

                    if (pairRepository.existsByTaskKeyAndGenerationSource(
                            chosen.getTaskKey(), GP_RESIDUAL_SOURCE)) {
                        continue;
                    }

                    pairs.add(new PreferencePair(
                            UUID.randomUUID(), planId,
                            chosen.getTaskKey(), chosen.getWorkerType().name(),
                            buildPrompt(chosen),
                            chosen.getResult(), rejected.getResult(),
                            chosen.getAggregatedReward(), rejected.getAggregatedReward(),
                            GP_RESIDUAL_SOURCE, maxResidual));
                }
            }
        }

        return pairs;
    }

    private float computeResidual(PlanItem item, Map<UUID, float[]> embeddingCache) {
        float[] embedding = embeddingCache.computeIfAbsent(item.getId(),
                k -> taskOutcomeService.embedTask(item.getTitle(), item.getDescription()));
        GpPrediction prediction = taskOutcomeService.predict(
                embedding, item.getWorkerType().name(), item.getWorkerProfile());
        return (float) Math.abs(item.getAggregatedReward() - prediction.mu());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the prompt string for a preference pair from the plan item's
     * title, description, and worker profile context.
     */
    private String buildPrompt(PlanItem item) {
        return String.format("{\"task_key\":\"%s\",\"worker_type\":\"%s\",\"worker_profile\":\"%s\","
                + "\"title\":%s,\"description\":%s}",
                item.getTaskKey(),
                item.getWorkerType().name(),
                item.getWorkerProfile() != null ? item.getWorkerProfile() : "null",
                jsonString(item.getTitle()),
                jsonString(item.getDescription()));
    }

    private String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                           .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
