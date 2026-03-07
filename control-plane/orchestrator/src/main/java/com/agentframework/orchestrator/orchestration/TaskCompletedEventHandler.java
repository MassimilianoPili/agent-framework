package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.event.TaskCompletedSideEffectEvent;
import com.agentframework.orchestrator.gp.SerendipityService;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import com.agentframework.orchestrator.hooks.HookManagerService;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.reward.RewardComputationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Handles non-critical side-effects after a task completion transaction commits.
 *
 * <p>This listener runs in the {@link TransactionPhase#AFTER_COMMIT} phase, which means:
 * <ul>
 *   <li>The critical path (status transition, save, dispatch, plan completion check)
 *       has already been committed to the database.</li>
 *   <li>Each side-effect runs in its own transaction — a failure in one does not
 *       affect the others or roll back the committed data.</li>
 *   <li>Side-effects are best-effort: if they fail, the task is still DONE/FAILED.</li>
 * </ul>
 *
 * <p>Side-effects handled:
 * <ol>
 *   <li>Process score computation (reward signal from Provenance)</li>
 *   <li>GP task outcome reward update</li>
 *   <li>Serendipity file outcome collection</li>
 *   <li>Review score distribution (for REVIEW worker type)</li>
 *   <li>Hook Manager policy storage (for HOOK_MANAGER worker type)</li>
 * </ol>
 */
@Component
public class TaskCompletedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskCompletedEventHandler.class);

    private final PlanItemRepository planItemRepository;
    private final RewardComputationService rewardComputationService;
    private final @Nullable TaskOutcomeService gpTaskOutcomeService;
    private final @Nullable SerendipityService serendipityService;
    private final HookManagerService hookManagerService;

    public TaskCompletedEventHandler(PlanItemRepository planItemRepository,
                                      RewardComputationService rewardComputationService,
                                      @Nullable TaskOutcomeService gpTaskOutcomeService,
                                      @Nullable SerendipityService serendipityService,
                                      HookManagerService hookManagerService) {
        this.planItemRepository = planItemRepository;
        this.rewardComputationService = rewardComputationService;
        this.gpTaskOutcomeService = gpTaskOutcomeService;
        this.serendipityService = serendipityService;
        this.hookManagerService = hookManagerService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleSideEffects(TaskCompletedSideEffectEvent event) {
        AgentResult result = event.result();

        PlanItem item = planItemRepository.findByIdWithPlan(event.itemId())
                .orElse(null);
        if (item == null) {
            log.warn("PlanItem {} not found for side-effects — skipping", event.itemId());
            return;
        }

        // 1. Compute process score from Provenance (zero LLM cost)
        runSafely("computeProcessScore", result.taskKey(), () ->
                rewardComputationService.computeProcessScore(item, result));

        // 2. GP reward signal: feed aggregatedReward back to task_outcomes
        if (gpTaskOutcomeService != null && item.getAggregatedReward() != null) {
            runSafely("updateReward", result.taskKey(), () ->
                    gpTaskOutcomeService.updateReward(
                            item.getId(), item.getAggregatedReward().doubleValue(),
                            item.getWorkerType().name(), item.getWorkerProfile()));
        }

        // 3. Serendipity: collect file-task associations
        if (serendipityService != null) {
            runSafely("collectFileOutcomes", result.taskKey(), () ->
                    serendipityService.collectFileOutcomes(item));
        }

        // 4. If REVIEW worker, distribute per-task review scores
        if (item.getWorkerType() == WorkerType.REVIEW) {
            runSafely("distributeReviewScore", result.taskKey(), () ->
                    rewardComputationService.distributeReviewScore(item));
        }

        // 5. Store per-task policies from Hook Manager
        if (item.getWorkerType() == WorkerType.HOOK_MANAGER) {
            runSafely("storePolicies", result.taskKey(), () ->
                    hookManagerService.storePolicies(result.planId(), result.resultJson()));
        }
    }

    private void runSafely(String label, String taskKey, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Side-effect '{}' failed for task {} (non-blocking): {}",
                     label, taskKey, e.getMessage());
        }
    }
}
