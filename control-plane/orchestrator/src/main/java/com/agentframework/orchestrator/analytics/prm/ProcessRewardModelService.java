package com.agentframework.orchestrator.analytics.prm;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Process Reward Model: step-level evaluation using GP-posterior-as-proxy.
 *
 * <p>Instead of training a dedicated PRM (expensive, data-hungry), this service uses
 * the existing GP posterior mu as a proxy for step-level reward, combined with objective
 * metrics (compile/test pass) when available. This approach is validated by the convergence
 * toward training-free PRMs (Lightman et al. ICLR 2024, CodePRM ACL 2025).</p>
 *
 * <p>Combined score: {@code objectiveWeight * objectiveScore + gpWeight * gpScore}</p>
 *
 * @see <a href="https://arxiv.org/abs/2305.20050">Lightman et al., Let's Verify Step by Step (ICLR 2024)</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.prm", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(PrmConfig.class)
public class ProcessRewardModelService {

    private static final Logger log = LoggerFactory.getLogger(ProcessRewardModelService.class);

    private static final Set<WorkerType> OBJECTIVE_WORKER_TYPES = Set.of(
            WorkerType.BE, WorkerType.FE, WorkerType.CONTRACT, WorkerType.DBA,
            WorkerType.MOBILE, WorkerType.INTEGRATION_MANAGER
    );

    private final TaskOutcomeService outcomeService;
    private final PrmConfig config;

    public ProcessRewardModelService(TaskOutcomeService outcomeService, PrmConfig config) {
        this.outcomeService = outcomeService;
        this.config = config;
    }

    /**
     * Evaluates a single plan item step using GP posterior + objective metrics.
     *
     * @param item the plan item to evaluate
     * @return step reward with decomposed scores, or null if GP data unavailable
     */
    public StepReward evaluateStep(PlanItem item) {
        if (item == null) return null;

        GpPrediction prediction = predictForItem(item);
        double gpScore = prediction != null ? clamp(prediction.mu(), -1.0, 1.0) : 0.0;

        double objectiveScore = computeObjectiveScore(item);
        boolean hasObjective = hasObjectiveSignal(item);

        double combined;
        String evidence;
        if (hasObjective) {
            combined = config.objectiveWeight() * objectiveScore + config.gpWeight() * gpScore;
            evidence = String.format("objective=%.3f (w=%.1f) + gp_mu=%.3f (w=%.1f)",
                    objectiveScore, config.objectiveWeight(), gpScore, config.gpWeight());
        } else {
            combined = gpScore;
            evidence = String.format("gp_mu=%.3f (no objective signal)", gpScore);
        }

        return new StepReward(item.getTaskKey(), gpScore, objectiveScore, combined, evidence);
    }

    /**
     * Evaluates an entire plan trajectory step-by-step.
     * Applies temporal decay: later steps contribute more (decay^(totalSteps - step_index - 1)).
     *
     * @param plan the plan to evaluate
     * @return trajectory report with per-step rewards and aggregated score
     */
    public PrmReport evaluateTrajectory(Plan plan) {
        if (plan == null || plan.getItems() == null || plan.getItems().isEmpty()) {
            return new PrmReport(List.of(), 0.0, 0, 0);
        }

        List<PlanItem> items = plan.getItems();
        List<StepReward> stepRewards = new ArrayList<>();
        int verifiedSteps = 0;

        for (PlanItem item : items) {
            StepReward reward = evaluateStep(item);
            if (reward != null) {
                stepRewards.add(reward);
                if (hasObjectiveSignal(item)) verifiedSteps++;
            }
        }

        double trajectoryScore = computeTrajectoryScore(stepRewards);

        log.debug("PRM trajectory: plan={} steps={} verified={} score={}",
                plan.getId(), stepRewards.size(), verifiedSteps,
                String.format("%.4f", trajectoryScore));

        return new PrmReport(stepRewards, trajectoryScore, stepRewards.size(), verifiedSteps);
    }

    /**
     * Checks whether a plan item has objective verification signals (compile, test, lint).
     * Only worker types that produce machine-verifiable output qualify.
     */
    public boolean hasObjectiveSignal(PlanItem item) {
        if (item == null) return false;
        WorkerType type = item.getWorkerType();
        if (type == null) return false;
        return OBJECTIVE_WORKER_TYPES.contains(type)
                && item.getStatus() != null
                && (item.getStatus() == ItemStatus.DONE || item.getStatus() == ItemStatus.FAILED);
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private GpPrediction predictForItem(PlanItem item) {
        try {
            float[] embedding = outcomeService.embedTask(item.getDescription(), item.getDescription());
            String workerType = item.getWorkerType() != null ? item.getWorkerType().name() : "BE";
            return outcomeService.predict(embedding, workerType, workerType.toLowerCase());
        } catch (Exception e) {
            log.trace("GP prediction unavailable for task={}: {}", item.getTaskKey(), e.getMessage());
            return null;
        }
    }

    private double computeObjectiveScore(PlanItem item) {
        double score = 0.0;
        int sources = 0;

        Float processScore = item.getProcessScore();
        if (processScore != null) {
            score += processScore;
            sources++;
        }

        Float aggregatedReward = item.getAggregatedReward();
        if (aggregatedReward != null) {
            score += aggregatedReward;
            sources++;
        }

        if (item.getStatus() == ItemStatus.DONE) {
            score += 0.5;
            sources++;
        } else if (item.getStatus() == ItemStatus.FAILED) {
            score -= 0.5;
            sources++;
        }

        return sources > 0 ? score / sources : 0.0;
    }

    private double computeTrajectoryScore(List<StepReward> stepRewards) {
        if (stepRewards.isEmpty()) return 0.0;

        int n = stepRewards.size();
        double weightedSum = 0.0;
        double weightTotal = 0.0;

        for (int i = 0; i < n; i++) {
            double weight = Math.pow(config.decayFactor(), n - i - 1);
            weightedSum += stepRewards.get(i).combined() * weight;
            weightTotal += weight;
        }

        return weightTotal > 0 ? weightedSum / weightTotal : 0.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * Step-level reward evaluation.
     *
     * @param taskKey        task identifier
     * @param gpScore        GP posterior mu (proxy reward) clamped to [-1, 1]
     * @param objectiveScore objective metrics score (processScore, status, aggregatedReward)
     * @param combined       weighted combination of GP and objective scores
     * @param evidence       human-readable explanation of score components
     */
    public record StepReward(
            String taskKey,
            double gpScore,
            double objectiveScore,
            double combined,
            String evidence
    ) {}

    /**
     * Trajectory-level PRM report.
     *
     * @param stepRewards    per-step reward evaluations (ordered by plan item sequence)
     * @param trajectoryScore aggregate score with temporal decay (later steps weighted more)
     * @param totalSteps     total number of evaluated steps
     * @param verifiedSteps  steps with objective verification signals
     */
    public record PrmReport(
            List<StepReward> stepRewards,
            double trajectoryScore,
            int totalSteps,
            int verifiedSteps
    ) {}
}
