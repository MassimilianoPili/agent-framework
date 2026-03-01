package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.api.dto.PlanRequest;
import com.agentframework.orchestrator.budget.TokenBudgetService;
import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.eventsourcing.PlanEventStore;
import com.agentframework.orchestrator.event.*;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.common.policy.ApprovalMode;
import com.agentframework.orchestrator.hooks.HookManagerService;
import com.agentframework.common.policy.HookPolicy;
import com.agentframework.common.policy.RiskLevel;
import com.agentframework.orchestrator.messaging.AgentTaskProducer;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.messaging.dto.AgentTask;
import com.agentframework.orchestrator.council.CouncilProperties;
import com.agentframework.orchestrator.council.CouncilReport;
import com.agentframework.orchestrator.council.CouncilService;
import com.agentframework.orchestrator.planner.PlannerService;
import com.agentframework.orchestrator.repository.DispatchAttemptRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import com.agentframework.orchestrator.reward.RewardComputationService;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.gp.GpWorkerSelectionService;
import com.agentframework.orchestrator.gp.SerendipityService;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import com.agentframework.orchestrator.specification.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Core orchestration logic.
 *
 * Lifecycle:
 * 1. createAndStart(spec) — create Plan, call planner, persist, dispatch first wave
 * 2. onTaskCompleted(result) — update item, dispatch newly unblocked items, check completion
 *
 * Dependency resolution is database-driven: findDispatchableItems() returns WAITING items
 * whose all dependencies are DONE, in a single query (no topological sort in Java).
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    @Value("${retry.max-attempts:3}")
    private int defaultMaxAttempts;

    @Value("${retry.backoff-ms:5000}")
    private long defaultBackoffMs;

    @Value("${retry.attempts-before-pause:2}")
    private int defaultAttemptsBeforePause;

    @Value("${retry.max-context-retries:1}")
    private int maxContextRetries;

    @Value("${plan.max-depth:3}")
    private int defaultMaxDepth;

    private final PlanRepository planRepository;
    private final PlanItemRepository planItemRepository;
    private final DispatchAttemptRepository attemptRepository;
    private final PlannerService plannerService;
    private final AgentTaskProducer taskProducer;
    private final WorkerProfileRegistry profileRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final WorkerCapabilitySpec capabilitySpec;
    private final HookManagerService hookManagerService;
    private final TokenBudgetService tokenBudgetService;
    private final PlanEventStore eventStore;
    private final CouncilService councilService;
    private final CouncilProperties councilProperties;
    private final RewardComputationService rewardComputationService;
    private final GpWorkerSelectionService gpSelectionService;
    private final TaskOutcomeService gpTaskOutcomeService;
    private final SerendipityService serendipityService;

    public OrchestrationService(PlanRepository planRepository,
                                PlanItemRepository planItemRepository,
                                DispatchAttemptRepository attemptRepository,
                                PlannerService plannerService,
                                AgentTaskProducer taskProducer,
                                WorkerProfileRegistry profileRegistry,
                                ApplicationEventPublisher eventPublisher,
                                ObjectMapper objectMapper,
                                HookManagerService hookManagerService,
                                TokenBudgetService tokenBudgetService,
                                PlanEventStore eventStore,
                                CouncilService councilService,
                                CouncilProperties councilProperties,
                                RewardComputationService rewardComputationService,
                                Optional<GpWorkerSelectionService> gpSelectionService,
                                Optional<TaskOutcomeService> gpTaskOutcomeService,
                                Optional<SerendipityService> serendipityService) {
        this.planRepository = planRepository;
        this.planItemRepository = planItemRepository;
        this.attemptRepository = attemptRepository;
        this.plannerService = plannerService;
        this.taskProducer = taskProducer;
        this.profileRegistry = profileRegistry;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.hookManagerService = hookManagerService;
        this.tokenBudgetService = tokenBudgetService;
        this.eventStore = eventStore;
        this.councilService = councilService;
        this.councilProperties = councilProperties;
        this.rewardComputationService = rewardComputationService;
        this.gpSelectionService = gpSelectionService.orElse(null);
        this.gpTaskOutcomeService = gpTaskOutcomeService.orElse(null);
        this.serendipityService = serendipityService.orElse(null);
        this.capabilitySpec = new CompositeSpec(
                new ToolAvailabilitySpec(),
                new PathOwnershipSpec());
    }

    /**
     * Creates a new Plan, calls Claude to decompose the spec into PlanItems,
     * persists everything, then dispatches the first wave of tasks.
     *
     * @param spec   natural-language plan specification
     * @param budget optional token budget — null means no budget enforced
     */
    @Transactional
    public Plan createAndStart(String spec, PlanRequest.Budget budget) {
        Plan plan = new Plan(UUID.randomUUID(), spec);

        if (budget != null) {
            try {
                plan.setBudgetJson(objectMapper.writeValueAsString(budget));
            } catch (Exception e) {
                log.warn("Failed to serialize budget for plan: {}", e.getMessage());
            }
        }

        // Pre-planning council session: consult domain experts before decomposing the spec.
        // The CouncilReport is stored on the plan and passed to the planner as enriched context.
        if (councilProperties.enabled() && councilProperties.prePlanningEnabled()) {
            try {
                CouncilReport councilReport = councilService.conductPrePlanningSession(spec);
                plan.setCouncilReport(objectMapper.writeValueAsString(councilReport));
                log.info("Pre-planning council complete: {} members, {} decisions (plan={})",
                         councilReport.selectedMembers() != null ? councilReport.selectedMembers().size() : 0,
                         councilReport.architectureDecisions() != null ? councilReport.architectureDecisions().size() : 0,
                         plan.getId());
            } catch (Exception e) {
                log.warn("Council pre-planning session failed (plan={}), continuing without council: {}",
                         plan.getId(), e.getMessage());
                // Non-fatal: the plan proceeds without council enrichment
            }
        }

        plan = plannerService.decompose(plan);
        plan.transitionTo(PlanStatus.RUNNING);
        plan = planRepository.save(plan);

        log.info("Plan {} created with {} items, dispatching first wave",
                 plan.getId(), plan.getItems().size());

        eventPublisher.publishEvent(new PlanCreatedEvent(
                plan.getId(), plan.getSpec(), plan.getItems().size()));
        eventPublisher.publishEvent(SpringPlanEvent.forPlan(SpringPlanEvent.PLAN_STARTED, plan.getId()));
        eventStore.append(plan.getId(), null, SpringPlanEvent.PLAN_STARTED,
                Map.of("spec", plan.getSpec(), "itemCount", plan.getItems().size()));

        dispatchReadyItems(plan.getId());
        return plan;
    }

    /**
     * Called by AgentResultConsumer when a worker publishes an AgentResult.
     * Updates item status, stores result, dispatches newly unblocked items.
     * If all items are terminal, triggers quality gate generation.
     */
    @Transactional
    public void onTaskCompleted(AgentResult result) {
        PlanItem item = planItemRepository.findById(result.itemId())
            .orElseThrow(() -> new IllegalStateException(
                "Unknown plan item: " + result.itemId()));

        // Idempotency guard: skip duplicate results for already-terminal items.
        // Service Bus uses at-least-once delivery; without this guard, a redelivered
        // result would cause transitionTo() to throw, triggering reject → redeliver loop.
        if (item.getStatus() == ItemStatus.DONE || item.getStatus() == ItemStatus.FAILED) {
            log.info("Task {} already in terminal state {} — skipping duplicate result (plan={})",
                     result.taskKey(), item.getStatus(), result.planId());
            return;
        }

        // Close the open dispatch attempt
        attemptRepository.findOpenAttempt(result.itemId())
            .ifPresent(attempt -> {
                attempt.complete(result.success(), result.failureReason(), result.durationMs());
                attemptRepository.save(attempt);
            });

        if (result.success()) {
            // Check for missing_context BEFORE transitioning to DONE.
            // Workers report missing context as a JSON array in their resultJson.
            List<String> missingCtx = extractMissingContext(result.resultJson());
            if (!missingCtx.isEmpty() && item.getContextRetryCount() < maxContextRetries) {
                handleMissingContext(item, missingCtx, result.planId());
                planItemRepository.save(item);
                dispatchReadyItems(result.planId());
                return;
            }

            item.transitionTo(ItemStatus.DONE);
            item.setResult(result.resultJson());
            item.setNextRetryAt(null);
            log.info("Task {} completed successfully (plan={}, profile={}, duration={}ms)",
                     result.taskKey(), result.planId(), item.getWorkerProfile(), result.durationMs());

            // Reward signal — point 1: compute processScore from Provenance (zero LLM cost)
            rewardComputationService.computeProcessScore(item, result);

            // GP reward signal: feed aggregatedReward back to task_outcomes for GP training
            if (gpTaskOutcomeService != null && item.getAggregatedReward() != null) {
                gpTaskOutcomeService.updateReward(
                        item.getId(), item.getAggregatedReward().doubleValue(),
                        item.getWorkerType().name(), item.getWorkerProfile());
            }

            // Serendipity: collect file-task associations for future serendipity ranking.
            // Must run after updateReward so the task_outcome has actual_reward populated.
            if (serendipityService != null) {
                try {
                    serendipityService.collectFileOutcomes(item);
                } catch (Exception e) {
                    log.warn("Serendipity collection failed for task {} (non-blocking): {}",
                             result.taskKey(), e.getMessage());
                }
            }

            // Reward signal — point 2: if REVIEW worker, distribute per-task review scores
            if (item.getWorkerType() == WorkerType.REVIEW) {
                rewardComputationService.distributeReviewScore(item);
            }

            // Store per-task policies emitted by the Hook Manager worker so
            // subsequent dispatchReadyItems() calls can inject them into AgentTask.
            if (item.getWorkerType() == WorkerType.HOOK_MANAGER) {
                hookManagerService.storePolicies(result.planId(), result.resultJson());
            }
        } else {
            item.transitionTo(ItemStatus.FAILED);
            item.setFailureReason(result.failureReason());

            // Schedule automatic retry with exponential backoff if attempts remain.
            int attemptNum = attemptRepository.findMaxAttemptNumber(item.getId()).orElse(1);
            if (attemptNum < defaultMaxAttempts) {
                long backoff = (long) (defaultBackoffMs * Math.pow(2, attemptNum - 1));
                // ±25% jitter to avoid thundering herd
                long jitter = (long) (backoff * 0.25 * (Math.random() * 2 - 1));
                item.setNextRetryAt(Instant.now().plus(Duration.ofMillis(backoff + jitter)));
                log.info("Task {} failed (attempt {}/{}), scheduled retry in ~{}ms (plan={})",
                         result.taskKey(), attemptNum, defaultMaxAttempts, backoff,
                         result.planId());
            } else {
                log.warn("Task {} exhausted all {} attempts (plan={})",
                         result.taskKey(), defaultMaxAttempts, result.planId());
            }

            // Pause the plan if this failure hits the attemptsBeforePause threshold.
            if (attemptNum >= defaultAttemptsBeforePause) {
                Plan plan = item.getPlan();
                if (plan.getStatus() == PlanStatus.RUNNING) {
                    plan.transitionTo(PlanStatus.PAUSED);
                    plan.setPausedAt(Instant.now());
                    planRepository.save(plan);
                    log.warn("Plan {} paused after {} failed attempts on task {}",
                             result.planId(), attemptNum, result.taskKey());
                    eventPublisher.publishEvent(SpringPlanEvent.forPlan(
                            SpringPlanEvent.PLAN_PAUSED, result.planId()));
                    eventStore.append(result.planId(), null, SpringPlanEvent.PLAN_PAUSED,
                            Map.of("taskKey", result.taskKey(), "attempts", attemptNum));
                }
            }

            log.warn("Task {} failed: {} (plan={}, profile={})",
                     result.taskKey(), result.failureReason(), result.planId(), item.getWorkerProfile());
        }
        item.setCompletedAt(Instant.now());
        planItemRepository.save(item);

        // Record actual token consumption for budget tracking (fire-and-forget, own transaction).
        // tokensUsed is a convenience field populated by newer workers; fall back to
        // provenance.tokenUsage.totalTokens for workers that report tokens only via provenance.
        long actualTokens = 0;
        if (result.tokensUsed() != null) {
            actualTokens = result.tokensUsed();
        } else if (result.provenance() != null && result.provenance().tokenUsage() != null
                && result.provenance().tokenUsage().totalTokens() != null) {
            actualTokens = result.provenance().tokenUsage().totalTokens();
        }
        if (actualTokens > 0) {
            String workerTypeKey = result.workerType() != null ? result.workerType() : item.getWorkerType().name();
            tokenBudgetService.recordUsage(result.planId(), workerTypeKey, actualTokens);
        }

        eventPublisher.publishEvent(new PlanItemCompletedEvent(
                result.planId(), result.itemId(), result.taskKey(),
                result.success(), result.durationMs()));
        String taskEventType = result.success() ? SpringPlanEvent.TASK_COMPLETED : SpringPlanEvent.TASK_FAILED;
        eventPublisher.publishEvent(SpringPlanEvent.forItem(
                taskEventType,
                result.planId(), result.itemId(), result.taskKey(),
                item.getWorkerProfile(), result.success(), result.durationMs()));
        eventStore.append(result.planId(), result.itemId(), taskEventType,
                Map.of("taskKey", result.taskKey(),
                       "workerProfile", item.getWorkerProfile() != null ? item.getWorkerProfile() : "",
                       "success", result.success(),
                       "durationMs", result.durationMs()));

        dispatchReadyItems(result.planId());
        checkPlanCompletion(result.planId());
    }

    /**
     * Retries a failed plan item by transitioning it FAILED → WAITING
     * and triggering a new dispatch wave.
     *
     * @throws IllegalStateException if the item is not found
     * @throws IllegalStateTransitionException if the item is not in FAILED status
     */
    @Transactional
    public void retryFailedItem(UUID itemId) {
        PlanItem item = planItemRepository.findById(itemId)
            .orElseThrow(() -> new IllegalStateException("Unknown plan item: " + itemId));

        int previousAttempts = attemptRepository.findMaxAttemptNumber(itemId).orElse(0);
        log.info("Retrying item {} (task={}, attempt #{} → #{})",
                 itemId, item.getTaskKey(), previousAttempts, previousAttempts + 1);

        item.transitionTo(ItemStatus.WAITING);
        item.setFailureReason(null);
        item.setCompletedAt(null);
        planItemRepository.save(item);

        // If the plan was FAILED or PAUSED, reopen it as RUNNING
        Plan plan = item.getPlan();
        if (plan.getStatus() == PlanStatus.FAILED || plan.getStatus() == PlanStatus.PAUSED) {
            plan.transitionTo(PlanStatus.RUNNING);
            plan.setCompletedAt(null);
            plan.setPausedAt(null);
            planRepository.save(plan);
        }

        dispatchReadyItems(plan.getId());
    }

    /**
     * Creates a COMPENSATOR_MANAGER task to undo the effects of the specified item.
     *
     * <p>The compensation task is added to the same plan as the original item.
     * Its description carries the original task's title, result, and a human-supplied
     * reason for compensation. It is dispatched immediately.</p>
     *
     * @param itemId             ID of the item to compensate
     * @param compensationReason why compensation is being requested (from the human reviewer)
     * @return the newly created compensation PlanItem
     */
    @Transactional
    public PlanItem createCompensationTask(UUID itemId, String compensationReason) {
        PlanItem originalItem = planItemRepository.findById(itemId)
            .orElseThrow(() -> new IllegalStateException("Unknown plan item: " + itemId));

        Plan plan = originalItem.getPlan();

        // Ensure the plan is still open (not permanently completed)
        if (plan.getStatus() == PlanStatus.COMPLETED || plan.getStatus() == PlanStatus.FAILED) {
            // Reopen the plan so we can dispatch the compensation task
            plan.transitionTo(PlanStatus.RUNNING);
            plan.setCompletedAt(null);
            planRepository.save(plan);
        }

        int maxOrdinal = planItemRepository.findByPlanId(plan.getId()).stream()
                .mapToInt(PlanItem::getOrdinal)
                .max().orElse(0);

        String compKey  = "COMP-" + originalItem.getTaskKey();
        String compDesc = String.format("""
                {
                  "original_task": {"key": "%s", "title": "%s", "description": "%s"},
                  "original_result": %s,
                  "compensation_reason": "%s"
                }
                """,
                originalItem.getTaskKey(),
                originalItem.getTitle().replace("\"", "\\\""),
                (originalItem.getDescription() != null ? originalItem.getDescription() : "").replace("\"", "\\\""),
                (originalItem.getResult() != null ? originalItem.getResult() : "null"),
                compensationReason.replace("\"", "\\\""));

        PlanItem compensationItem = new PlanItem(
                UUID.randomUUID(), maxOrdinal + 1, compKey,
                "Compensate: " + originalItem.getTitle(),
                compDesc,
                WorkerType.COMPENSATOR_MANAGER,
                "compensator-manager",
                List.of()
        );

        plan.addItem(compensationItem);
        planRepository.save(plan);

        log.info("Compensation task {} created for original item {} (plan={})",
                 compKey, itemId, plan.getId());

        eventStore.append(plan.getId(), compensationItem.getId(), "COMPENSATION_REQUESTED",
                Map.of("originalItemId", itemId.toString(),
                       "compensationKey", compKey,
                       "reason", compensationReason));

        dispatchReadyItems(plan.getId());
        return compensationItem;
    }

    /**
     * Returns the dispatch history for a plan item.
     */
    @Transactional(readOnly = true)
    public List<DispatchAttempt> getAttempts(UUID itemId) {
        return attemptRepository.findByItemIdOrderByAttemptNumberAsc(itemId);
    }

    /**
     * Retrieves the current state of a plan by ID.
     */
    @Transactional(readOnly = true)
    public Optional<Plan> getPlan(UUID planId) {
        return planRepository.findById(planId);
    }

    /**
     * Triggers a new dispatch wave for the given plan without changing the plan's status.
     * Used after an AWAITING_APPROVAL item is approved: the plan stays RUNNING, but a
     * fresh dispatch pass is needed to pick up the newly-WAITING item.
     */
    @Transactional
    public void triggerDispatch(UUID planId) {
        dispatchReadyItems(planId);
        checkPlanCompletion(planId);
    }

    /**
     * Resumes a PAUSED plan by transitioning it back to RUNNING and re-triggering dispatch.
     */
    @Transactional
    public void resumePlan(UUID planId) {
        Plan plan = planRepository.findById(planId)
            .orElseThrow(() -> new IllegalStateException("Unknown plan: " + planId));
        plan.transitionTo(PlanStatus.RUNNING);
        plan.setPausedAt(null);
        planRepository.save(plan);
        log.info("Plan {} resumed", planId);
        eventPublisher.publishEvent(SpringPlanEvent.forPlan(SpringPlanEvent.PLAN_RESUMED, planId));
        eventStore.append(planId, null, SpringPlanEvent.PLAN_RESUMED, Map.of("planId", planId.toString()));
        dispatchReadyItems(planId);
    }

    /**
     * Finds WAITING items with all dependencies satisfied and dispatches them.
     */
    private void dispatchReadyItems(UUID planId) {
        List<PlanItem> dispatchable = planItemRepository.findDispatchableItems(planId);

        if (dispatchable.isEmpty()) {
            return;
        }

        Map<String, String> completedResults = loadCompletedResults(planId);
        Plan plan = planRepository.findById(planId).orElseThrow();
        String planSpec = plan.getSpec();
        PlanRequest.Budget budget = deserializeBudget(plan.getBudgetJson());

        int dispatched = 0;
        for (PlanItem item : dispatchable) {
            // SUB_PLAN items are handled inline — spawn a child plan instead of dispatching to a worker.
            if (item.getWorkerType() == WorkerType.SUB_PLAN) {
                handleSubPlan(item, plan);
                planItemRepository.save(item);
                continue;
            }

            // COUNCIL_MANAGER items are handled in-process — run a council session synchronously,
            // mark DONE immediately, and let the result flow to dependent workers via completedResults.
            if (item.getWorkerType() == WorkerType.COUNCIL_MANAGER) {
                handleCouncilManager(item, plan, completedResults);
                planItemRepository.save(item);
                // Reload completed results so dependent items see this council output
                if (item.getStatus() == ItemStatus.DONE) {
                    completedResults.put(item.getTaskKey(), item.getResult());
                }
                continue;
            }

            // Risk check: CRITICAL tasks require human approval before dispatch.
            // The HookPolicy is resolved here (same call used later for AgentTask),
            // so we resolve it once and reuse it below.
            HookPolicy preResolvedPolicy = hookManagerService
                    .resolvePolicy(planId, item.getTaskKey(), item.getWorkerType())
                    .orElse(null);

            if (preResolvedPolicy != null
                    && preResolvedPolicy.riskLevel() == RiskLevel.CRITICAL) {
                item.transitionTo(ItemStatus.AWAITING_APPROVAL);
                planItemRepository.save(item);
                log.warn("Task {} requires human approval (riskLevel=CRITICAL, plan={})",
                         item.getTaskKey(), planId);
                continue; // do not dispatch — awaiting approval
            }

            // Resolve worker profile for multi-stack types (BE, FE) when planner didn't assign one.
            // GP-based selection: if GP is enabled and there are multiple candidate profiles,
            // predict expected reward for each and select the best. Falls back to default.
            // The GpPrediction is captured here for dynamic budget adjustment below.
            GpPrediction gpPrediction = null;
            if (item.getWorkerProfile() == null) {
                if (gpSelectionService != null
                        && profileRegistry.profilesForWorkerType(item.getWorkerType()).size() > 1) {
                    var selection = gpSelectionService.selectProfile(
                            item.getWorkerType(), item.getTitle(), item.getDescription());
                    item.setWorkerProfile(selection.selectedProfile());
                    gpPrediction = selection.selectedPrediction();
                } else {
                    String defaultProfile = profileRegistry.resolveDefaultProfile(item.getWorkerType());
                    if (defaultProfile != null) {
                        log.debug("Resolved default profile '{}' for task {} (type={})",
                                  defaultProfile, item.getTaskKey(), item.getWorkerType());
                        item.setWorkerProfile(defaultProfile);
                    }
                }
            } else if (gpTaskOutcomeService != null && item.getWorkerProfile() != null) {
                // Profile pre-assigned by planner — compute standalone GP prediction for budget
                try {
                    float[] emb = gpTaskOutcomeService.embedTask(item.getTitle(), item.getDescription());
                    gpPrediction = gpTaskOutcomeService.predict(
                            emb, item.getWorkerType().name(), item.getWorkerProfile());
                } catch (Exception e) {
                    log.debug("GP prediction for budget failed (non-blocking): {}", e.getMessage());
                }
            }

            // Specification pattern: validate profile capabilities before dispatch
            if (item.getWorkerProfile() != null) {
                WorkerProfileRegistry.ProfileEntry profileEntry =
                        profileRegistry.getProfileEntry(item.getWorkerProfile());
                if (profileEntry != null) {
                    ProfileCapabilities capabilities = new ProfileCapabilities(
                            item.getWorkerProfile(), profileEntry.workerType(),
                            profileEntry.mcpServers(), profileEntry.ownsPaths());
                    TaskRequirements requirements = new TaskRequirements(
                            item.getTaskKey(), item.getWorkerType(), List.of());

                    if (!capabilitySpec.isSatisfiedBy(capabilities, requirements)) {
                        log.warn("Profile '{}' does not satisfy requirements for task {} — failing",
                                 item.getWorkerProfile(), item.getTaskKey());
                        item.transitionTo(ItemStatus.FAILED);
                        item.setFailureReason("Profile '" + item.getWorkerProfile()
                                + "' does not satisfy capability requirements");
                        item.setCompletedAt(Instant.now());
                        planItemRepository.save(item);
                        continue;
                    }
                }
            }

            // Token budget check: enforce per-workerType limits with GP-adjusted dynamic budget.
            // Placed after profile resolution so the GP prediction is available for budget modulation.
            if (budget != null) {
                TokenBudgetService.BudgetDecision decision =
                        tokenBudgetService.checkBudget(planId, item.getWorkerType().name(), budget, gpPrediction);
                if (decision.action() == TokenBudgetService.BudgetDecision.Action.FAIL) {
                    log.warn("Token budget FAIL_FAST for task {} (workerType={}, used={}, effective={})",
                             item.getTaskKey(), item.getWorkerType(), decision.used(), decision.effectiveLimit());
                    item.transitionTo(ItemStatus.FAILED);
                    item.setFailureReason("Token budget exceeded (used=" + decision.used()
                            + ", effective=" + decision.effectiveLimit() + ")");
                    item.setCompletedAt(Instant.now());
                    planItemRepository.save(item);
                    continue;
                } else if (decision.action() == TokenBudgetService.BudgetDecision.Action.SKIP) {
                    log.info("Token budget NO_NEW_DISPATCH: skipping task {} (workerType={}, used={}, effective={})",
                             item.getTaskKey(), item.getWorkerType(), decision.used(), decision.effectiveLimit());
                    continue; // item stays WAITING
                }
                // ALLOW or WARN — proceed with dispatch
            }

            // Create attempt entity first so its ID can be embedded in the task message for tracing
            int attemptNum = attemptRepository.findMaxAttemptNumber(item.getId()).orElse(0) + 1;
            DispatchAttempt attempt = new DispatchAttempt(UUID.randomUUID(), item, attemptNum);
            UUID traceId = UUID.randomUUID();

            // Use the pre-resolved policy (already fetched for the risk check above).
            HookPolicy policy = preResolvedPolicy;

            // Ralph-loop: append quality gate feedback to description so the worker
            // knows what to fix. The feedback is set by RalphLoopService when re-queuing.
            String description = item.getDescription();
            if (item.getLastQualityGateFeedback() != null) {
                description = description
                    + "\n\n--- QUALITY GATE FEEDBACK (iteration "
                    + item.getRalphLoopCount() + ") ---\n"
                    + item.getLastQualityGateFeedback();
            }

            // Serendipity hints: enrich CONTEXT_MANAGER and RAG_MANAGER task descriptions
            // with historically-surprising file paths for similar tasks. This gives Claude
            // (CM) explicit hints about which files to explore, and enriches RAG search context.
            if (serendipityService != null
                    && (item.getWorkerType() == WorkerType.CONTEXT_MANAGER
                        || item.getWorkerType() == WorkerType.RAG_MANAGER)) {
                try {
                    var hints = serendipityService.getSerendipityHints(
                            item.getTitle(), description);
                    if (!hints.isEmpty()) {
                        description = description + formatSerendipityHints(hints);
                    }
                } catch (Exception e) {
                    log.warn("Serendipity hints failed for task {} (non-blocking): {}",
                             item.getTaskKey(), e.getMessage());
                }
            }

            AgentTask task = new AgentTask(
                planId,
                item.getId(),
                item.getTaskKey(),
                item.getTitle(),
                description,
                item.getWorkerType(),
                item.getWorkerProfile(),
                planSpec,
                buildContextJson(item, completedResults),
                attemptNum,
                attempt.getId(),
                traceId,
                Instant.now().toString(),
                policy,
                plan.getCouncilReport()  // inject pre-planning council context into every task
            );

            try {
                taskProducer.dispatch(task);
                item.transitionTo(ItemStatus.DISPATCHED);
                item.setDispatchedAt(Instant.now());
                dispatched++;
                eventPublisher.publishEvent(new PlanItemDispatchedEvent(
                        planId, item.getId(), item.getTaskKey(), item.getWorkerProfile()));
                eventStore.append(planId, item.getId(), SpringPlanEvent.TASK_DISPATCHED,
                        Map.of("taskKey", item.getTaskKey(),
                               "workerProfile", item.getWorkerProfile() != null ? item.getWorkerProfile() : "",
                               "attempt", attemptNum));
            } catch (WorkerProfileRegistry.UnknownWorkerProfileException e) {
                log.error("Unknown worker profile for task {}: {}", item.getTaskKey(), e.getMessage());
                item.transitionTo(ItemStatus.FAILED);
                item.setFailureReason("Unknown worker profile: " + item.getWorkerProfile());
                item.setCompletedAt(Instant.now());
                attempt.failImmediately("Unknown worker profile: " + item.getWorkerProfile());
            }
            attemptRepository.save(attempt);
            planItemRepository.save(item);
        }

        if (dispatched > 0) {
            log.info("Dispatched {}/{} items for plan {} (profiles: {})", dispatched, dispatchable.size(), planId,
                     dispatchable.stream()
                         .map(i -> i.getTaskKey() + "→" + (i.getWorkerProfile() != null ? i.getWorkerProfile() : i.getWorkerType().name()))
                         .collect(Collectors.joining(", ")));
        }
    }

    /**
     * Checks if all items in the plan are terminal (DONE or FAILED).
     * If so, marks the plan as COMPLETED or FAILED and triggers quality gate.
     * Skips if the plan is PAUSED — completion is deferred until explicit resume.
     */
    private void checkPlanCompletion(UUID planId) {
        List<PlanItem> active = planItemRepository.findActiveByPlanId(planId);
        if (!active.isEmpty()) {
            return;
        }

        Plan plan = planRepository.findById(planId).orElseThrow();

        // A PAUSED plan waits for explicit resume — do not auto-complete.
        if (plan.getStatus() == PlanStatus.PAUSED) {
            return;
        }

        List<PlanItem> allItems = planItemRepository.findByPlanId(planId);

        boolean anyFailed = allItems.stream()
            .anyMatch(i -> i.getStatus() == ItemStatus.FAILED);

        plan.transitionTo(anyFailed ? PlanStatus.FAILED : PlanStatus.COMPLETED);
        plan.setCompletedAt(Instant.now());
        planRepository.save(plan);

        long failedCount = allItems.stream().filter(i -> i.getStatus() == ItemStatus.FAILED).count();
        String profileSummary = allItems.stream()
            .filter(i -> i.getWorkerProfile() != null)
            .collect(Collectors.groupingBy(PlanItem::getWorkerProfile, Collectors.counting()))
            .entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(", "));

        log.info("Plan {} completed with status {} ({} items, {} failed, profiles: [{}])",
                 planId, plan.getStatus(), allItems.size(), failedCount,
                 profileSummary.isEmpty() ? "none" : profileSummary);

        // Release per-plan HookPolicy cache to prevent unbounded memory growth.
        hookManagerService.evictPlan(planId);

        eventPublisher.publishEvent(new PlanCompletedEvent(
                planId, plan.getStatus(), allItems.size(), failedCount));
        eventPublisher.publishEvent(SpringPlanEvent.forPlan(SpringPlanEvent.PLAN_COMPLETED, planId));
        eventStore.append(planId, null, SpringPlanEvent.PLAN_COMPLETED,
                Map.of("status", plan.getStatus().name(),
                       "totalItems", allItems.size(),
                       "failedItems", failedCount));
    }

    private Map<String, String> loadCompletedResults(UUID planId) {
        return planItemRepository.findByPlanId(planId).stream()
            .filter(i -> i.getStatus() == ItemStatus.DONE && i.getResult() != null)
            .collect(Collectors.toMap(PlanItem::getTaskKey, PlanItem::getResult));
    }

    private String buildContextJson(PlanItem item, Map<String, String> completedResults) {
        Map<String, String> deps = new LinkedHashMap<>();
        for (String depKey : item.getDependsOn()) {
            deps.put(depKey, completedResults.getOrDefault(depKey, "{}"));
        }
        try {
            return objectMapper.writeValueAsString(deps);
        } catch (Exception e) {
            log.warn("Failed to serialize context for task {}: {}", item.getTaskKey(), e.getMessage());
            return "{}";
        }
    }

    /** Deserializes the plan's budget JSON back to a {@link PlanRequest.Budget} (null if absent/invalid). */
    private PlanRequest.Budget deserializeBudget(String budgetJson) {
        if (budgetJson == null || budgetJson.isBlank()) return null;
        try {
            return objectMapper.readValue(budgetJson, PlanRequest.Budget.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize plan budget JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Formats serendipity hints as a human-readable text block to append to task descriptions.
     */
    private String formatSerendipityHints(List<SerendipityService.SerendipityHint> hints) {
        var sb = new StringBuilder("\n\n--- SERENDIPITY HINTS ---\n");
        sb.append("Files historically useful for similar tasks (explore these in addition to obvious matches):\n");
        for (var hint : hints) {
            sb.append(String.format("- %s (score: %.2f)%n", hint.filePath(), hint.score()));
        }
        return sb.toString();
    }

    /**
     * Parses the worker result JSON and extracts the {@code missing_context} array (if present).
     * Workers report paths or keys they needed but couldn't access.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractMissingContext(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return List.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(resultJson, Map.class);
            Object mc = parsed.get("missing_context");
            if (mc instanceof List<?> list && !list.isEmpty()) {
                return list.stream().map(Object::toString).toList();
            }
        } catch (Exception e) {
            // Non-JSON or unexpected format — not an error, just no missing_context
        }
        return List.of();
    }

    /**
     * Handles a worker result with missing_context: creates a new CONTEXT_MANAGER task
     * to resolve the missing paths, adds it as a dependency of the original item,
     * and re-queues the original item in WAITING state.
     */
    private void handleMissingContext(PlanItem item, List<String> missingCtx, UUID planId) {
        int retryNum = item.getContextRetryCount() + 1;
        String ctxKey = "CM-CTX" + retryNum + "-" + item.getTaskKey();
        String ctxDesc = "Resolve missing context for " + item.getTaskKey()
                + ". Missing paths/keys: " + missingCtx;

        int maxOrdinal = planItemRepository.findByPlanId(planId).stream()
                .mapToInt(PlanItem::getOrdinal)
                .max().orElse(0);

        PlanItem ctxItem = new PlanItem(
                UUID.randomUUID(), maxOrdinal + 1, ctxKey,
                "Context resolution for " + item.getTaskKey(),
                ctxDesc,
                WorkerType.CONTEXT_MANAGER,
                null,
                List.of()
        );

        Plan plan = planRepository.findById(planId).orElseThrow();
        plan.addItem(ctxItem);
        planRepository.save(plan);

        item.addDependency(ctxKey);
        item.transitionTo(ItemStatus.WAITING);   // DISPATCHED → WAITING
        item.incrementContextRetryCount();
        item.setNextRetryAt(null);

        log.info("Task {} re-queued for context retry #{} via {} (missing: {})",
                 item.getTaskKey(), retryNum, ctxKey, missingCtx);
    }

    /**
     * Handles an item with workerType=SUB_PLAN by spawning a child plan inline.
     * Does not dispatch to any message broker — everything is handled synchronously.
     *
     * <ul>
     *   <li>If the plan is already at maxDepth, the item is transitioned to FAILED immediately.</li>
     *   <li>If awaitCompletion=true (default), the item stays DISPATCHED until the child completes.</li>
     *   <li>If awaitCompletion=false, the item is immediately transitioned to DONE (fire-and-forget).</li>
     * </ul>
     */
    private void handleSubPlan(PlanItem item, Plan parentPlan) {
        int effectiveMaxDepth = defaultMaxDepth;

        // Recursion guard: refuse to spawn beyond the depth limit
        if (parentPlan.getDepth() >= effectiveMaxDepth) {
            item.transitionTo(ItemStatus.FAILED);
            item.setFailureReason("SUB_PLAN depth limit exceeded (depth=" + parentPlan.getDepth()
                    + ", maxDepth=" + effectiveMaxDepth + ")");
            item.setCompletedAt(Instant.now());
            log.warn("SUB_PLAN item {} rejected: depth limit {} reached (plan={})",
                     item.getTaskKey(), effectiveMaxDepth, parentPlan.getId());
            return;
        }

        String subSpec = item.getSubPlanSpec();
        if (subSpec == null || subSpec.isBlank()) {
            item.transitionTo(ItemStatus.FAILED);
            item.setFailureReason("SUB_PLAN item has no subPlanSpec");
            item.setCompletedAt(Instant.now());
            return;
        }

        // Spawn child plan — shares the parent's budget (no independent budget for now)
        Plan childPlan = new Plan(UUID.randomUUID(), subSpec, parentPlan.getId(), parentPlan.getDepth());
        childPlan = plannerService.decompose(childPlan);
        childPlan.transitionTo(PlanStatus.RUNNING);
        childPlan = planRepository.save(childPlan);

        item.setChildPlanId(childPlan.getId());
        item.transitionTo(ItemStatus.DISPATCHED);  // WAITING → DISPATCHED
        item.setDispatchedAt(Instant.now());

        log.info("SUB_PLAN item {} spawned child plan {} (depth={}, awaitCompletion={})",
                 item.getTaskKey(), childPlan.getId(), childPlan.getDepth(), item.isAwaitCompletion());

        eventStore.append(parentPlan.getId(), item.getId(), "SUB_PLAN_STARTED",
                Map.of("childPlanId", childPlan.getId().toString(),
                       "depth", childPlan.getDepth()));

        // Dispatch the first wave of the child plan
        dispatchReadyItems(childPlan.getId());

        // Fire-and-forget: immediately complete the parent item without waiting for the child
        if (!item.isAwaitCompletion()) {
            item.transitionTo(ItemStatus.DONE);
            item.setCompletedAt(Instant.now());
            log.info("SUB_PLAN item {} completed immediately (awaitCompletion=false)", item.getTaskKey());
        }
    }

    /**
     * Handles a COUNCIL_MANAGER item in-process (never dispatched via messaging).
     *
     * <p>Conducts a targeted council session using the item's title and description as the
     * task context, and the already-completed dependency results as background. The
     * synthesised {@code CouncilReport} JSON is stored in {@code item.result} and made
     * available to downstream workers via {@code completedResults}.</p>
     *
     * <p>If the council feature is disabled ({@code council.enabled=false} or
     * {@code council.task-session-enabled=false}), the item is immediately transitioned
     * to DONE with a minimal stub result, preserving backward compatibility.</p>
     */
    private void handleCouncilManager(PlanItem item, Plan plan, Map<String, String> completedResults) {
        if (!councilProperties.enabled() || !councilProperties.taskSessionEnabled()) {
            item.transitionTo(ItemStatus.DONE);
            item.setResult("{\"council_disabled\": true}");
            item.setCompletedAt(Instant.now());
            log.debug("COUNCIL_MANAGER task {} skipped — council disabled (plan={})",
                      item.getTaskKey(), plan.getId());
            return;
        }

        // Collect dependency results available at this point in the DAG
        Map<String, String> depResults = item.getDependsOn().stream()
                .filter(completedResults::containsKey)
                .collect(Collectors.toMap(k -> k, completedResults::get));

        Instant start = Instant.now();
        try {
            item.transitionTo(ItemStatus.RUNNING);

            CouncilReport report = councilService.conductTaskSession(
                    item.getTitle(), item.getDescription(), depResults);

            String resultJson = objectMapper.writeValueAsString(
                    Map.of("council_report", report, "taskKey", item.getTaskKey()));

            item.transitionTo(ItemStatus.DONE);
            item.setResult(resultJson);
            item.setCompletedAt(Instant.now());

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            log.info("COUNCIL_MANAGER task {} completed in-process in {}ms (plan={})",
                     item.getTaskKey(), durationMs, plan.getId());

            eventPublisher.publishEvent(SpringPlanEvent.forItem(
                    SpringPlanEvent.TASK_COMPLETED,
                    plan.getId(), item.getId(), item.getTaskKey(),
                    item.getWorkerProfile(), true, durationMs));

            eventStore.append(plan.getId(), item.getId(), SpringPlanEvent.TASK_COMPLETED,
                    Map.of("taskKey", item.getTaskKey(),
                           "workerType", "COUNCIL_MANAGER",
                           "durationMs", durationMs));

        } catch (Exception e) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            log.error("COUNCIL_MANAGER task {} failed: {} (plan={})",
                      item.getTaskKey(), e.getMessage(), plan.getId(), e);
            item.transitionTo(ItemStatus.FAILED);
            item.setFailureReason("Council session failed: " + e.getMessage());
            item.setCompletedAt(Instant.now());

            eventPublisher.publishEvent(SpringPlanEvent.forItem(
                    SpringPlanEvent.TASK_FAILED,
                    plan.getId(), item.getId(), item.getTaskKey(),
                    item.getWorkerProfile(), false, durationMs));
        }
    }

    /**
     * Called when any plan completes. Finds the parent item (if any) whose
     * childPlanId matches and transitions it to DONE or FAILED accordingly.
     *
     * <p>This enables the awaitCompletion=true semantics: the parent item stays
     * DISPATCHED until the child plan terminates.</p>
     */
    @EventListener
    @Transactional
    public void onChildPlanCompleted(PlanCompletedEvent event) {
        // Only handle child plans (those with a parent)
        Plan childPlan = planRepository.findById(event.planId()).orElse(null);
        if (childPlan == null || childPlan.getParentPlanId() == null) {
            return;
        }

        UUID parentPlanId = childPlan.getParentPlanId();

        // Find the parent item that references this child plan
        planItemRepository.findByPlanId(parentPlanId).stream()
            .filter(i -> event.planId().equals(i.getChildPlanId())
                      && i.getStatus() == ItemStatus.DISPATCHED)
            .findFirst()
            .ifPresent(parentItem -> {
                boolean childSucceeded = event.status() == PlanStatus.COMPLETED;
                if (childSucceeded) {
                    parentItem.transitionTo(ItemStatus.DONE);
                    log.info("Parent item {} completed: child plan {} finished successfully",
                             parentItem.getTaskKey(), event.planId());
                } else {
                    parentItem.transitionTo(ItemStatus.FAILED);
                    parentItem.setFailureReason("Child plan " + event.planId() + " failed");
                    log.warn("Parent item {} failed: child plan {} did not complete successfully",
                             parentItem.getTaskKey(), event.planId());
                }
                parentItem.setCompletedAt(Instant.now());
                planItemRepository.save(parentItem);

                // Trigger a dispatch wave on the parent plan (unblocks items depending on this one)
                dispatchReadyItems(parentPlanId);
                checkPlanCompletion(parentPlanId);
            });
    }
}
