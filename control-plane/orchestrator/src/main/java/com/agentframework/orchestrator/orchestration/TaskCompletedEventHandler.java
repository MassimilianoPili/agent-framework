package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.analytics.BayesianSurpriseService;
import com.agentframework.orchestrator.analytics.ByzantineFaultToleranceService;
import com.agentframework.orchestrator.analytics.ProcessMiningService;
import com.agentframework.orchestrator.analytics.ShapleyDagService;
import com.agentframework.orchestrator.analytics.prm.ProcessRewardModelService;
import com.agentframework.orchestrator.analytics.shapley.CausalShapleyService;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.event.TaskCompletedSideEffectEvent;
import com.agentframework.orchestrator.gp.ContextQualityService;
import com.agentframework.orchestrator.gp.SerendipityService;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import com.agentframework.orchestrator.budget.TokenLedgerService;
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
 *   <li>Tool Manager policy storage (for TOOL_MANAGER worker type — per-task, overrides HM)</li>
 *   <li>Context quality scoring (information-theoretic feedback on CM-selected context)</li>
 *   <li>Token ledger credit: record value production from aggregated reward (#33)</li>
 *   <li>DAG Shapley: compute Shapley values when all plan items are DONE (#40)</li>
 * </ol>
 */
@Component
public class TaskCompletedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskCompletedEventHandler.class);

    private final PlanItemRepository planItemRepository;
    private final RewardComputationService rewardComputationService;
    private final @Nullable TaskOutcomeService gpTaskOutcomeService;
    private final @Nullable SerendipityService serendipityService;
    private final @Nullable ContextQualityService contextQualityService;
    private final HookManagerService hookManagerService;
    private final TokenLedgerService tokenLedgerService;
    private final ShapleyDagService shapleyDagService;
    private final @Nullable ByzantineFaultToleranceService bftService;
    private final @Nullable BayesianSurpriseService bayesianSurpriseService;
    private final @Nullable ProcessRewardModelService processRewardModelService;
    private final @Nullable CausalShapleyService causalShapleyService;
    private final @Nullable ProcessMiningService processMiningService;

    public TaskCompletedEventHandler(PlanItemRepository planItemRepository,
                                      RewardComputationService rewardComputationService,
                                      @Nullable TaskOutcomeService gpTaskOutcomeService,
                                      @Nullable SerendipityService serendipityService,
                                      @Nullable ContextQualityService contextQualityService,
                                      HookManagerService hookManagerService,
                                      TokenLedgerService tokenLedgerService,
                                      ShapleyDagService shapleyDagService,
                                      @Nullable ByzantineFaultToleranceService bftService,
                                      @Nullable BayesianSurpriseService bayesianSurpriseService,
                                      @Nullable ProcessRewardModelService processRewardModelService,
                                      @Nullable CausalShapleyService causalShapleyService,
                                      @Nullable ProcessMiningService processMiningService) {
        this.planItemRepository = planItemRepository;
        this.rewardComputationService = rewardComputationService;
        this.gpTaskOutcomeService = gpTaskOutcomeService;
        this.serendipityService = serendipityService;
        this.contextQualityService = contextQualityService;
        this.hookManagerService = hookManagerService;
        this.tokenLedgerService = tokenLedgerService;
        this.shapleyDagService = shapleyDagService;
        this.bftService = bftService;
        this.bayesianSurpriseService = bayesianSurpriseService;
        this.processRewardModelService = processRewardModelService;
        this.causalShapleyService = causalShapleyService;
        this.processMiningService = processMiningService;
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

        // 6. Store precise per-task tool policy from Tool Manager (overrides HM result for target task)
        if (item.getWorkerType() == WorkerType.TOOL_MANAGER && result.resultJson() != null) {
            runSafely("storeToolManagerResult", result.taskKey(), () ->
                    hookManagerService.storeToolManagerResult(result.planId(), result.resultJson()));
        }

        // 7. Context quality scoring: information-theoretic feedback on CM-selected context (#35)
        if (contextQualityService != null) {
            runSafely("contextQualityScore", result.taskKey(), () -> {
                Double cqScore = contextQualityService.computeAndStore(item, result);
                if (cqScore != null) {
                    rewardComputationService.injectContextQualityScore(item, cqScore.floatValue());
                }
            });
        }

        // 8. Token ledger credit: record value production from aggregated reward (#33)
        runSafely("tokenLedgerCredit", result.taskKey(), () -> {
            PlanItem freshItem = planItemRepository.findById(event.itemId()).orElse(null);
            if (freshItem != null && freshItem.getAggregatedReward() != null) {
                long tokens = 0;
                if (result.tokensUsed() != null) tokens = result.tokensUsed();
                else if (result.provenance() != null && result.provenance().tokenUsage() != null
                        && result.provenance().tokenUsage().totalTokens() != null) {
                    tokens = result.provenance().tokenUsage().totalTokens();
                }
                if (tokens > 0) {
                    tokenLedgerService.credit(result.planId(), freshItem.getId(),
                            result.taskKey(), freshItem.getWorkerType().name(),
                            tokens, freshItem.getAggregatedReward().doubleValue());
                }
            }
        });

        // 9. DAG Shapley: compute Shapley values when all plan items are DONE (#40)
        runSafely("shapleyDag", result.taskKey(), () -> {
            PlanItem freshItem = planItemRepository.findByIdWithPlan(event.itemId()).orElse(null);
            if (freshItem == null) return;
            Plan plan = freshItem.getPlan();
            boolean allDone = plan.getItems().stream()
                    .allMatch(i -> i.getStatus() == ItemStatus.DONE);
            if (allDone) {
                shapleyDagService.computeForPlan(plan);
            }
        });

        // 10. BFT consensus: detect Byzantine workers on items with multiple dispatch attempts
        if (bftService != null) {
            runSafely("bftConsensus", result.taskKey(), () -> {
                var bftReport = bftService.analyseItem(event.itemId());
                if (!bftReport.byzantineWorkers().isEmpty()) {
                    log.warn("BFT detected {} Byzantine attempts for task {} (consensus: {}, reached: {})",
                             bftReport.byzantineWorkers().size(), result.taskKey(),
                             bftReport.consensusOutcome(), bftReport.consensusReached());
                }
            });
        }

        // 11. Bayesian surprise: detect anomalous worker outcomes
        if (bayesianSurpriseService != null) {
            runSafely("bayesianSurprise", result.taskKey(), () -> {
                var surprise = bayesianSurpriseService.analyse(item.getWorkerType().name());
                if (surprise != null) {
                    log.debug("Bayesian surprise for task {} ({}): KL={}, category={}, zScore={}",
                              result.taskKey(), item.getWorkerType(), surprise.klDivergence(),
                              surprise.surpriseCategory(), surprise.zScore());
                }
            });
        }

        // 12. Process Reward Model: step-level evaluation via GP posterior
        if (processRewardModelService != null) {
            runSafely("prmEvaluateStep", result.taskKey(), () -> {
                var stepReward = processRewardModelService.evaluateStep(item);
                if (stepReward != null) {
                    log.debug("PRM step reward for task {}: combined={}, gp={}, objective={}, evidence={}",
                              result.taskKey(), stepReward.combined(), stepReward.gpScore(),
                              stepReward.objectiveScore(), stepReward.evidence());
                }
            });
        }

        // 13. Causal Shapley: compute causal attribution when all plan items are DONE
        if (causalShapleyService != null) {
            runSafely("causalShapley", result.taskKey(), () -> {
                PlanItem freshItem = planItemRepository.findByIdWithPlan(event.itemId()).orElse(null);
                if (freshItem == null) return;
                Plan plan = freshItem.getPlan();
                boolean allDone = plan.getItems().stream()
                        .allMatch(i -> i.getStatus() == ItemStatus.DONE);
                if (allDone) {
                    var attributions = causalShapleyService.computeForPlan(plan);
                    log.info("Causal Shapley for plan {}: {} task attributions computed",
                             plan.getId(), attributions.size());
                }
            });
        }

        // 14. Process Mining: discover process model when all plan items are DONE
        if (processMiningService != null) {
            runSafely("processMining", result.taskKey(), () -> {
                PlanItem freshItem = planItemRepository.findByIdWithPlan(event.itemId()).orElse(null);
                if (freshItem == null) return;
                Plan plan = freshItem.getPlan();
                boolean allDone = plan.getItems().stream()
                        .allMatch(i -> i.getStatus() == ItemStatus.DONE);
                if (allDone) {
                    var model = processMiningService.discover();
                    if (model != null) {
                        log.info("Process mining for plan {}: {} causal relations, fitness={}, loops={}",
                                 plan.getId(), model.causalRelations().size(),
                                 model.fitness(), model.loopDetected());
                    }
                }
            });
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
