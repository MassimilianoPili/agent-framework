package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ralph-Loop: quality gate feedback loop.
 *
 * <p>When the quality gate fails, this service identifies the implicated items
 * and re-queues them (DONE → WAITING) with the quality gate feedback appended
 * to their context. The plan is reopened (COMPLETED → RUNNING) so that
 * {@code dispatchReadyItems()} can re-dispatch them.</p>
 *
 * <h3>Design choices and motivations</h3>
 *
 * <p><b>Why DONE → WAITING and not DONE → FAILED → WAITING:</b>
 * The task didn't <i>fail</i> — it produced a result that the quality gate rejected.
 * Routing through FAILED would pollute {@code AutoRetryScheduler}'s FAILED→WAITING
 * count ({@code PlanItemRepository.findRetryEligible()}), triggering exponential backoff
 * and potentially pausing the plan. DONE→WAITING is semantically clean.</p>
 *
 * <p><b>Why a separate {@code ralphLoopCount} instead of reusing {@code contextRetryCount}:</b>
 * The two counters track different phenomena: contextRetryCount tracks intra-task
 * missing-context retries (triggered by the worker), ralphLoopCount tracks post-plan
 * quality gate retries (triggered by this service). Independent caps allow fine-tuning
 * each loop without side effects on the other.</p>
 *
 * <p><b>Why COMPLETED → RUNNING for the plan:</b>
 * Creating a new plan would lose dependency graph, accumulated rewards, and provenance.
 * Reopening the same plan preserves continuity. {@code PlanStatus} already has RUNNING;
 * we only add the transition from COMPLETED.</p>
 *
 * <p><b>Why only domain workers (BE, FE, AI_TASK, CONTRACT) are re-queued:</b>
 * Infrastructure workers (CONTEXT_MANAGER, SCHEMA_MANAGER, HOOK_MANAGER, etc.) produce
 * intermediate context, not deliverables. Re-running them without re-running the domain
 * workers that consume their output is pointless. The quality gate evaluates deliverables.</p>
 */
@Service
public class RalphLoopService {

    private static final Logger log = LoggerFactory.getLogger(RalphLoopService.class);

    @Value("${ralph-loop.enabled:true}")
    private boolean enabled;

    @Value("${ralph-loop.max-iterations:2}")
    private int maxIterations;

    private final PlanRepository planRepository;
    private final PlanItemRepository planItemRepository;

    public RalphLoopService(PlanRepository planRepository,
                            PlanItemRepository planItemRepository) {
        this.planRepository = planRepository;
        this.planItemRepository = planItemRepository;
    }

    /**
     * Evaluates the quality gate report and re-queues implicated items if the gate failed.
     *
     * <p>Called by {@code QualityGateService.onPlanCompleted()} after the report is saved.
     * This is the integration point: QualityGateService owns the assessment,
     * RalphLoopService owns the retry logic. Single Responsibility.</p>
     *
     * @param planId   the plan that was evaluated
     * @param passed   whether the quality gate passed
     * @param findings list of findings from the quality gate report
     * @return list of item IDs that were re-queued, empty if gate passed or loop exhausted
     */
    @Transactional
    public List<UUID> evaluateAndRetry(UUID planId, boolean passed, List<String> findings) {
        if (!enabled || passed || findings == null || findings.isEmpty()) {
            return List.of();
        }

        Plan plan = planRepository.findById(planId).orElse(null);
        if (plan == null) {
            log.warn("Ralph-loop: plan {} not found, skipping", planId);
            return List.of();
        }

        List<PlanItem> allItems = planItemRepository.findByPlanId(planId);
        List<UUID> requeued = new ArrayList<>();

        // Identify domain worker items that are DONE and eligible for re-queue.
        // Only domain workers produce deliverables evaluated by the quality gate.
        String feedbackText = String.join("\n", findings);

        for (PlanItem item : allItems) {
            if (item.getStatus() != ItemStatus.DONE) {
                continue;
            }
            if (!isDomainWorker(item.getWorkerType())) {
                continue;
            }
            if (item.getRalphLoopCount() >= maxIterations) {
                log.info("Ralph-loop: item {} (task={}) exhausted {} iterations, skipping",
                         item.getId(), item.getTaskKey(), maxIterations);
                continue;
            }

            // Re-queue: DONE → WAITING with quality gate feedback
            item.transitionTo(ItemStatus.WAITING);
            item.incrementRalphLoopCount();
            item.setLastQualityGateFeedback(feedbackText);
            item.setCompletedAt(null);
            planItemRepository.save(item);

            requeued.add(item.getId());
            log.info("Ralph-loop: re-queued item {} (task={}, iteration={}/{})",
                     item.getId(), item.getTaskKey(), item.getRalphLoopCount(), maxIterations);
        }

        if (requeued.isEmpty()) {
            log.info("Ralph-loop: no eligible items to re-queue for plan {}", planId);
            return List.of();
        }

        // Reopen the plan: COMPLETED → RUNNING so dispatchReadyItems() picks up the re-queued items.
        if (plan.getStatus() == PlanStatus.COMPLETED) {
            plan.transitionTo(PlanStatus.RUNNING);
            plan.setCompletedAt(null);
            planRepository.save(plan);
            log.info("Ralph-loop: reopened plan {} (COMPLETED → RUNNING), {} items re-queued",
                     planId, requeued.size());
        }

        return requeued;
    }

    /**
     * Returns true for worker types that produce deliverables evaluated by the quality gate.
     * Infrastructure workers (context, schema, hooks, etc.) are excluded.
     */
    private boolean isDomainWorker(WorkerType type) {
        return type == WorkerType.BE
            || type == WorkerType.FE
            || type == WorkerType.AI_TASK
            || type == WorkerType.CONTRACT
            || type == WorkerType.DBA
            || type == WorkerType.MOBILE;
    }
}
