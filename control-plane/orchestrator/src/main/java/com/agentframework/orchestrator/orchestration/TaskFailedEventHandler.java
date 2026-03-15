package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.analytics.mast.MastClassifierService;
import com.agentframework.orchestrator.analytics.mast.MastTaxonomy.FailureClassification;
import com.agentframework.orchestrator.analytics.mast.SelfHealingRouter;
import com.agentframework.orchestrator.analytics.recovery.RecoveryRouterService;
import com.agentframework.orchestrator.analytics.selfrefine.SelfRefineGateService;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.event.TaskFailedSideEffectEvent;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Handles failure-specific side-effects after a task failure transaction commits.
 *
 * <p>Mirrors {@link TaskCompletedEventHandler} but runs only for failed tasks.
 * Each side-effect is best-effort and non-blocking.</p>
 *
 * <p>Side-effects handled:
 * <ol>
 *   <li>MAST failure classification (taxonomy-based)</li>
 *   <li>Self-healing routing (MAST-based recovery action)</li>
 *   <li>Graph-based recovery routing (Dijkstra alternative path)</li>
 *   <li>Self-refine gate evaluation (should this task be self-refined?)</li>
 * </ol>
 */
@Component
public class TaskFailedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskFailedEventHandler.class);

    private final PlanItemRepository planItemRepository;
    private final @Nullable MastClassifierService mastClassifierService;
    private final @Nullable SelfHealingRouter selfHealingRouter;
    private final @Nullable RecoveryRouterService recoveryRouterService;
    private final @Nullable SelfRefineGateService selfRefineGateService;

    public TaskFailedEventHandler(PlanItemRepository planItemRepository,
                                   @Nullable MastClassifierService mastClassifierService,
                                   @Nullable SelfHealingRouter selfHealingRouter,
                                   @Nullable RecoveryRouterService recoveryRouterService,
                                   @Nullable SelfRefineGateService selfRefineGateService) {
        this.planItemRepository = planItemRepository;
        this.mastClassifierService = mastClassifierService;
        this.selfHealingRouter = selfHealingRouter;
        this.recoveryRouterService = recoveryRouterService;
        this.selfRefineGateService = selfRefineGateService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleFailureSideEffects(TaskFailedSideEffectEvent event) {
        AgentResult result = event.result();

        PlanItem item = planItemRepository.findByIdWithPlan(event.itemId())
                .orElse(null);
        if (item == null) {
            log.warn("PlanItem {} not found for failure side-effects — skipping", event.itemId());
            return;
        }

        Plan plan = item.getPlan();

        // 1. MAST failure classification
        FailureClassification classification = null;
        if (mastClassifierService != null) {
            classification = runSafelyWithResult("mastClassify", result.taskKey(), () ->
                    mastClassifierService.classify(item, plan));
            if (classification != null) {
                log.info("MAST classification for task {}: mode={}, confidence={}, evidence={}",
                         result.taskKey(), classification.mode(), classification.confidence(),
                         classification.evidence());
            }
        }

        // 2. Self-healing routing (depends on MAST classification)
        if (selfHealingRouter != null && classification != null) {
            final FailureClassification finalClassification = classification;
            runSafely("selfHealingRoute", result.taskKey(), () -> {
                var action = selfHealingRouter.route(finalClassification, item, plan);
                log.info("Self-healing action for task {}: strategy={}, requiresHuman={}, description={}",
                         result.taskKey(), action.strategy(), action.requiresHuman(), action.description());
            });
        }

        // 3. Graph-based recovery routing (independent of MAST)
        if (recoveryRouterService != null) {
            runSafely("recoveryRouter", result.taskKey(), () -> {
                var path = recoveryRouterService.findAlternativePath(item, plan);
                log.info("Recovery path for task {}: feasible={}, strategy={}, hops={}, weight={}",
                         result.taskKey(), path.feasible(), path.strategy(),
                         path.taskKeys().size(), path.totalWeight());
            });
        }

        // 4. Self-refine gate evaluation
        if (selfRefineGateService != null) {
            runSafely("selfRefineGate", result.taskKey(), () -> {
                var gate = selfRefineGateService.evaluate(item, item.getWorkerType());
                log.info("Self-refine gate for task {}: decision={}, reason={}",
                         result.taskKey(), gate.decision(), gate.reason());
            });
        }
    }

    private void runSafely(String label, String taskKey, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Failure side-effect '{}' failed for task {} (non-blocking): {}",
                     label, taskKey, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface SafeSupplier<T> {
        T get() throws Exception;
    }

    private <T> T runSafelyWithResult(String label, String taskKey, SafeSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Failure side-effect '{}' failed for task {} (non-blocking): {}",
                     label, taskKey, e.getMessage());
            return null;
        }
    }
}
