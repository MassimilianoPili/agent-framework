package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.api.dto.PlanRequest;
import com.agentframework.orchestrator.artifact.ArtifactStore;
import com.agentframework.orchestrator.budget.CostEstimationService;
import com.agentframework.orchestrator.budget.PidBudgetController;
import com.agentframework.orchestrator.cache.ContextCacheService;
import com.agentframework.orchestrator.budget.TokenBudgetService;
import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.eventsourcing.PlanEventStore;
import com.agentframework.orchestrator.event.*;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.common.policy.ApprovalMode;
import com.agentframework.common.policy.CompensationMode;
import com.agentframework.orchestrator.hooks.HookManagerService;
import com.agentframework.common.policy.HookPolicy;
import com.agentframework.common.policy.RiskLevel;
import com.agentframework.orchestrator.messaging.AgentTaskProducer;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.messaging.dto.AgentTask;
import com.agentframework.orchestrator.messaging.dto.FileModificationEvent;
import com.agentframework.orchestrator.council.CouncilProperties;
import com.agentframework.orchestrator.council.CouncilReport;
import com.agentframework.orchestrator.council.CouncilService;
import com.agentframework.orchestrator.config.EnrichmentProperties;
import com.agentframework.orchestrator.planner.PlannerService;
import com.agentframework.orchestrator.repository.DispatchAttemptRepository;
import com.agentframework.orchestrator.repository.FileModificationRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import com.agentframework.orchestrator.reward.RewardComputationService;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.gp.BayesianSuccessPredictorService;
import com.agentframework.orchestrator.gp.GpWorkerSelectionService;
import com.agentframework.orchestrator.gp.PlanDecompositionPredictor;
import com.agentframework.orchestrator.gp.SerendipityService;
import com.agentframework.orchestrator.gp.SuccessPrediction;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import com.agentframework.messaging.inprocess.InProcessMessageBroker;
import com.agentframework.messaging.hybrid.HybridMessagingProperties;
import com.agentframework.messaging.hybrid.RemoteWorkerClient;
import com.agentframework.orchestrator.leader.LeaderElectionService;
import com.agentframework.orchestrator.metrics.OrchestratorMetrics;
import com.agentframework.orchestrator.specification.*;
import com.agentframework.orchestrator.workspace.WorkspaceManager;
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
    private final CostEstimationService costEstimationService;
    private final PlanEventStore eventStore;
    private final CouncilService councilService;
    private final CouncilProperties councilProperties;
    private final RewardComputationService rewardComputationService;
    private final GpWorkerSelectionService gpSelectionService;
    private final TaskOutcomeService gpTaskOutcomeService;
    private final SerendipityService serendipityService;
    private final BayesianSuccessPredictorService bayesianPredictor;
    private final MarketMakingDispatcher marketMakingDispatcher;
    private final ArtifactStore artifactStore;
    private final WorkspaceManager workspaceManager;
    private final EnrichmentInjectorService enrichmentInjectorService;
    private final EnrichmentProperties enrichmentProperties;
    private final ContextCacheService contextCacheService;
    private final PlanDecompositionPredictor decompositionPredictor;
    private final OrchestratorMetrics metrics;
    private final LeaderElectionService leaderElectionService;
    private final FileModificationRepository fileModificationRepository;
    // P1.7: optional — present only when messaging.provider=in-process or hybrid
    private final InProcessMessageBroker inProcessBroker;
    // P2.7: optional — present only in hybrid mode for cross-JVM cancellation
    private final RemoteWorkerClient remoteWorkerClient;
    private final HybridMessagingProperties hybridProps;
    // #37: PID adaptive token budget
    private final PidBudgetController pidBudgetController;

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
                                CostEstimationService costEstimationService,
                                PlanEventStore eventStore,
                                CouncilService councilService,
                                CouncilProperties councilProperties,
                                RewardComputationService rewardComputationService,
                                Optional<GpWorkerSelectionService> gpSelectionService,
                                Optional<TaskOutcomeService> gpTaskOutcomeService,
                                Optional<SerendipityService> serendipityService,
                                Optional<BayesianSuccessPredictorService> bayesianPredictor,
                                Optional<MarketMakingDispatcher> marketMakingDispatcher,
                                ArtifactStore artifactStore,
                                WorkspaceManager workspaceManager,
                                EnrichmentInjectorService enrichmentInjectorService,
                                EnrichmentProperties enrichmentProperties,
                                ContextCacheService contextCacheService,
                                Optional<PlanDecompositionPredictor> decompositionPredictor,
                                OrchestratorMetrics metrics,
                                Optional<LeaderElectionService> leaderElectionService,
                                FileModificationRepository fileModificationRepository,
                                Optional<InProcessMessageBroker> inProcessBroker,
                                Optional<RemoteWorkerClient> remoteWorkerClient,
                                Optional<HybridMessagingProperties> hybridProps,
                                Optional<PidBudgetController> pidBudgetController) {
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
        this.costEstimationService = costEstimationService;
        this.eventStore = eventStore;
        this.councilService = councilService;
        this.councilProperties = councilProperties;
        this.rewardComputationService = rewardComputationService;
        this.gpSelectionService = gpSelectionService.orElse(null);
        this.gpTaskOutcomeService = gpTaskOutcomeService.orElse(null);
        this.serendipityService = serendipityService.orElse(null);
        this.bayesianPredictor = bayesianPredictor.orElse(null);
        this.marketMakingDispatcher = marketMakingDispatcher.orElse(null);
        this.artifactStore = artifactStore;
        this.workspaceManager = workspaceManager;
        this.enrichmentInjectorService = enrichmentInjectorService;
        this.enrichmentProperties = enrichmentProperties;
        this.contextCacheService = contextCacheService;
        this.decompositionPredictor = decompositionPredictor.orElse(null);
        this.metrics = metrics;
        this.leaderElectionService = leaderElectionService.orElse(null);
        this.fileModificationRepository = fileModificationRepository;
        this.inProcessBroker = inProcessBroker.orElse(null);
        this.remoteWorkerClient = remoteWorkerClient.orElse(null);
        this.hybridProps = hybridProps.orElse(null);
        this.pidBudgetController = pidBudgetController.orElse(null);
        this.capabilitySpec = new CompositeSpec(
                new ToolAvailabilitySpec(),
                new PathOwnershipSpec());
    }

    /**
     * Creates a new Plan, calls Claude to decompose the spec into PlanItems,
     * persists everything, then dispatches the first wave of tasks.
     *
     * @param spec        natural-language plan specification
     * @param budget      optional token budget — null means no budget enforced
     * @param projectPath optional base path for dynamic ownsPaths resolution — null means static only
     */
    @Transactional
    public Plan createAndStart(String spec, PlanRequest.Budget budget, String projectPath) {
        Plan plan = new Plan(UUID.randomUUID(), spec);

        if (projectPath != null && !projectPath.isBlank()) {
            plan.setProjectPath(projectPath);
        }

        // Create plan-scoped workspace for shared file access (#44)
        try {
            String workspaceName = workspaceManager.createWorkspace(plan.getId());
            plan.setWorkspaceVolume(workspaceName);
        } catch (Exception e) {
            log.warn("Failed to create workspace for plan {}: {}", plan.getId(), e.getMessage());
        }

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

        // Auto-inject enrichment tasks (CM, RM, SM) as dependencies of domain workers.
        // Idempotent: skips types the planner already generated.
        if (enrichmentProperties.autoInject()) {
            enrichmentInjectorService.inject(plan);
        }

        plan.transitionTo(PlanStatus.RUNNING);
        plan = planRepository.save(plan);

        log.info("Plan {} created with {} items, dispatching first wave",
                 plan.getId(), plan.getItems().size());
        metrics.recordPlanCreated();

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
        PlanItem item = planItemRepository.findByIdWithPlan(result.itemId())
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
                if (result.conversationLog() != null) {
                    attempt.setConversationLog(result.conversationLog());
                }
                attemptRepository.save(attempt);
            });

        // G3: Persist file modifications reported by the worker
        if (result.fileModifications() != null && !result.fileModifications().isEmpty()) {
            for (FileModificationEvent fm : result.fileModifications()) {
                FileOperation op;
                try { op = FileOperation.valueOf(fm.operation()); }
                catch (Exception e) { op = FileOperation.MODIFIED; }

                fileModificationRepository.save(new FileModification(
                    result.planId(), result.itemId(), result.taskKey(),
                    fm.filePath(), op,
                    fm.contentHashBefore(), fm.contentHashAfter(),
                    fm.diffPreview()));
            }
        }

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
            metrics.recordItemCompleted(item.getWorkerType().name(), "DONE", result.durationMs());

            // CAS: deduplicate result content via content-addressable storage (#48)
            if (result.resultJson() != null && !result.resultJson().isBlank()) {
                try {
                    String hash = artifactStore.save(result.resultJson());
                    item.setResultHash(hash);
                } catch (Exception e) {
                    log.warn("Failed to store artifact for {}: {}", result.taskKey(), e.getMessage());
                }
            }

            // Cache CONTEXT_MANAGER results so downstream tasks can retrieve context
            // without re-running the expensive file-scan + embedding pipeline.
            if (item.getWorkerType() == WorkerType.CONTEXT_MANAGER
                    && result.resultJson() != null && !result.resultJson().isBlank()) {
                contextCacheService.put(result.planId(), item.getTaskKey(), result.resultJson());
            }

            log.info("Task {} completed successfully (plan={}, profile={}, duration={}ms)",
                     result.taskKey(), result.planId(), item.getWorkerProfile(), result.durationMs());
        } else {
            item.transitionTo(ItemStatus.FAILED);
            item.setFailureReason(result.failureReason());
            metrics.recordItemCompleted(item.getWorkerType().name(), "FAILED", result.durationMs());

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

            // Propagate failure to dependents when a CONTEXT_MANAGER task fails.
            // Without context, the dependent task cannot run — fail it immediately
            // rather than leaving it WAITING forever.
            if (item.getWorkerType() == WorkerType.CONTEXT_MANAGER) {
                failDependentsOfContextManager(item, result.planId());
            }
        }
        item.setCompletedAt(Instant.now());

        // Save per-task token breakdown on the PlanItem (#26L1)
        if (result.provenance() != null && result.provenance().tokenUsage() != null) {
            var tu = result.provenance().tokenUsage();
            item.setInputTokens(tu.inputTokens());
            item.setOutputTokens(tu.outputTokens());
            item.setEstimatedCostUsd(costEstimationService.estimate(
                    tu.inputTokens(), tu.outputTokens(),
                    result.provenance().model()));
        }

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
            log.info("Task {} completed: {}in/{}out tokens, ~${} (plan={}, worker={})",
                     result.taskKey(), item.getInputTokens(), item.getOutputTokens(),
                     item.getEstimatedCostUsd(), result.planId(), item.getWorkerProfile());

            // PID adaptive budget: feed actual consumption back to the controller (#37)
            if (pidBudgetController != null) {
                Integer estimatedTokens = hookManagerService
                        .resolvePolicy(result.planId(), result.taskKey(), item.getWorkerType())
                        .map(HookPolicy::estimatedTokens)
                        .orElse(null);
                pidBudgetController.update(
                        result.planId(), workerTypeKey, estimatedTokens, actualTokens);
            }
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

        // Publish side-effect event for successful tasks — consumed AFTER_COMMIT by
        // TaskCompletedEventHandler (reward computation, GP update, serendipity, etc.)
        if (result.success()) {
            eventPublisher.publishEvent(new TaskCompletedSideEffectEvent(item.getId(), result));
        }

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
        PlanItem item = planItemRepository.findByIdWithPlan(itemId)
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
     * Redispatches a FAILED or DONE item by transitioning it through TO_DISPATCH → DISPATCHED.
     *
     * <p>Unlike {@link #retryFailedItem(UUID)}, which goes through WAITING and dependency
     * resolution, this method dispatches the item directly to a worker — bypassing the
     * dependency check. This is the operator override path for manual retry after root-cause
     * fix, or re-running a completed task.</p>
     *
     * <p>If the item is already in TO_DISPATCH (e.g. set via direct DB update), the
     * transition to TO_DISPATCH is skipped and dispatch proceeds directly.</p>
     *
     * @param itemId the plan item to redispatch
     * @return the previous status of the item before redispatch
     * @throws IllegalStateException if the item is not found
     * @throws IllegalStateTransitionException if the transition is not legal
     */
    @Transactional
    public ItemStatus redispatchItem(UUID itemId) {
        PlanItem item = planItemRepository.findByIdWithPlan(itemId)
            .orElseThrow(() -> new IllegalStateException("Unknown plan item: " + itemId));

        ItemStatus previousStatus = item.getStatus();

        // Transition to TO_DISPATCH (unless already there, e.g. from DB-level manual update)
        if (item.getStatus() != ItemStatus.TO_DISPATCH) {
            item.transitionTo(ItemStatus.TO_DISPATCH);
        }
        item.setFailureReason(null);
        item.setCompletedAt(null);
        planItemRepository.save(item);

        // Reopen plan if needed
        Plan plan = item.getPlan();
        if (plan.getStatus() == PlanStatus.COMPLETED) {
            // Compensation path: plan was already complete; operator is forcing a task redo.
            // Preserve completedAt for audit — do NOT null it out.
            plan.transitionTo(PlanStatus.RUNNING);
            plan.setPausedAt(null);
            planRepository.save(plan);
            log.warn("Reopening COMPLETED plan {} for item redispatch — compensation operation (task={})",
                     plan.getId(), item.getTaskKey());
            eventStore.append(plan.getId(), item.getId(), "PLAN_COMPENSATION_STARTED",
                    Map.of("taskKey", item.getTaskKey(), "triggeredBy", "redispatch"));
        } else if (plan.getStatus() == PlanStatus.FAILED || plan.getStatus() == PlanStatus.PAUSED) {
            plan.transitionTo(PlanStatus.RUNNING);
            plan.setCompletedAt(null);
            plan.setPausedAt(null);
            planRepository.save(plan);
        }

        // Close any orphaned open attempts before creating the new one (B19)
        int closedOrphans = attemptRepository.closeOrphanedAttempts(
                item.getId(), Instant.now(), "closed-before-redispatch");
        if (closedOrphans > 0) {
            log.warn("Closed {} orphaned DispatchAttempt(s) for item {} before redispatch",
                     closedOrphans, itemId);
        }

        // Direct dispatch — bypass dependency resolution
        int attemptNum = attemptRepository.findMaxAttemptNumber(item.getId()).orElse(0) + 1;
        DispatchAttempt attempt = new DispatchAttempt(UUID.randomUUID(), item, attemptNum);
        UUID traceId = UUID.randomUUID();

        log.info("Redispatching item {} (task={}, {} → TO_DISPATCH → DISPATCHED, attempt #{})",
                 itemId, item.getTaskKey(), previousStatus, attemptNum);

        // Build description with ralph-loop feedback if present
        String description = item.getDescription();
        if (item.getLastQualityGateFeedback() != null) {
            description = description
                + "\n\n--- QUALITY GATE FEEDBACK (iteration "
                + item.getRalphLoopCount() + ") ---\n"
                + item.getLastQualityGateFeedback();
        }

        Map<String, String> completedResults = loadCompletedResults(plan.getId());
        AgentTask task = new AgentTask(
            plan.getId(),
            item.getId(),
            item.getTaskKey(),
            item.getTitle(),
            description,
            item.getWorkerType(),
            item.getWorkerProfile(),
            plan.getSpec(),
            buildContextJson(item, completedResults),
            attemptNum,
            attempt.getId(),
            traceId,
            Instant.now().toString(),
            null,  // no hook policy override for operator-initiated redispatch
            plan.getCouncilReport(),
            buildDynamicOwnsPaths(plan.getProjectPath(), item.getWorkerProfile()),
            item.getToolHints(),
            resolveWorkspacePath(plan),
            item.getModelId()
        );

        try {
            taskProducer.dispatch(task);
            item.transitionTo(ItemStatus.DISPATCHED);
            item.setDispatchedAt(Instant.now());
            eventPublisher.publishEvent(new PlanItemDispatchedEvent(
                    plan.getId(), item.getId(), item.getTaskKey(), item.getWorkerProfile()));
            eventStore.append(plan.getId(), item.getId(), SpringPlanEvent.TASK_DISPATCHED,
                    Map.of("taskKey", item.getTaskKey(),
                           "workerProfile", item.getWorkerProfile() != null ? item.getWorkerProfile() : "",
                           "attempt", attemptNum,
                           "redispatch", true));
        } catch (WorkerProfileRegistry.UnknownWorkerProfileException e) {
            log.error("Redispatch failed — unknown worker profile for task {}: {}",
                      item.getTaskKey(), e.getMessage());
            item.transitionTo(ItemStatus.FAILED);
            item.setFailureReason("Redispatch failed: unknown worker profile: " + item.getWorkerProfile());
            item.setCompletedAt(Instant.now());
            attempt.failImmediately("Unknown worker profile: " + item.getWorkerProfile());
        }
        attemptRepository.save(attempt);
        planItemRepository.save(item);

        return previousStatus;
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
        PlanItem originalItem = planItemRepository.findByIdWithPlan(itemId)
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
                .mapToInt(PlanItem::getOrdinal).max().orElse(0);

        PlanItem compensationItem = buildCompensationItem(originalItem, compensationReason, maxOrdinal + 1);
        plan.addItem(compensationItem);
        planRepository.save(plan);

        log.info("Compensation task {} created for original item {} (plan={})",
                 compensationItem.getTaskKey(), itemId, plan.getId());

        eventStore.append(plan.getId(), compensationItem.getId(), "COMPENSATION_REQUESTED",
                Map.of("originalItemId", itemId.toString(),
                       "compensationKey", compensationItem.getTaskKey(),
                       "reason", compensationReason));

        dispatchReadyItems(plan.getId());
        return compensationItem;
    }

    /**
     * Summary returned by {@link #compensatePlan}.
     *
     * @param mode           the compensation mode applied
     * @param affectedItems  number of items involved (DONE→UNDO, FAILED→RETRY)
     * @param createdTaskIds IDs of newly created COMPENSATOR_MANAGER tasks (UNDO only)
     */
    public record CompensationSummary(CompensationMode mode, int affectedItems, List<UUID> createdTaskIds) {}

    /**
     * Plan-level compensation with explicit semantic intent.
     *
     * <p>Unlike the item-level {@link #createCompensationTask(UUID, String)}, this method
     * operates on the whole plan with a declared {@link CompensationMode}:</p>
     * <ul>
     *   <li>{@code UNDO} — creates a COMPENSATOR_MANAGER task for every DONE item (saga rollback)</li>
     *   <li>{@code RETRY} — resets every FAILED item to WAITING and re-enters the dispatch loop</li>
     *   <li>{@code AMENDMENT} — reopens a terminal plan so the operator can add new tasks</li>
     * </ul>
     *
     * @param planId the plan to compensate
     * @param mode   the compensation intent
     * @param reason human-readable explanation (persisted in the event log)
     * @return a summary of what was done
     */
    @Transactional
    public CompensationSummary compensatePlan(UUID planId, CompensationMode mode, String reason) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalStateException("Unknown plan: " + planId));

        String effectiveReason = (reason != null && !reason.isBlank())
                ? reason
                : "Operator-initiated " + mode.name().toLowerCase();

        return switch (mode) {
            case UNDO      -> executeUndo(plan, effectiveReason);
            case RETRY     -> executeRetry(plan, effectiveReason);
            case AMENDMENT -> executeAmendment(plan, effectiveReason);
        };
    }

    private CompensationSummary executeUndo(Plan plan, String reason) {
        List<PlanItem> allItems = planItemRepository.findByPlanId(plan.getId());
        List<PlanItem> doneItems = allItems.stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE)
                .toList();

        if (doneItems.isEmpty()) {
            throw new IllegalStateException(
                    "Plan " + plan.getId() + " has no DONE items to undo");
        }

        reopenPlanForCompensation(plan, CompensationMode.UNDO);

        int maxOrdinal = allItems.stream().mapToInt(PlanItem::getOrdinal).max().orElse(0);
        List<UUID> createdIds = new ArrayList<>();
        for (PlanItem item : doneItems) {
            PlanItem ct = buildCompensationItem(item, reason, ++maxOrdinal);
            plan.addItem(ct);
            createdIds.add(ct.getId());
        }
        planRepository.save(plan);

        eventStore.append(plan.getId(), null, "PLAN_UNDO_REQUESTED",
                Map.of("mode", "UNDO", "reason", reason,
                       "doneItemCount", doneItems.size(),
                       "compensationTaskCount", createdIds.size()));
        dispatchReadyItems(plan.getId());
        log.info("Plan UNDO: created {} compensation tasks for plan {}", createdIds.size(), plan.getId());
        return new CompensationSummary(CompensationMode.UNDO, doneItems.size(), createdIds);
    }

    private CompensationSummary executeRetry(Plan plan, String reason) {
        List<PlanItem> failedItems = planItemRepository.findByPlanId(plan.getId()).stream()
                .filter(i -> i.getStatus() == ItemStatus.FAILED)
                .toList();

        if (failedItems.isEmpty()) {
            throw new IllegalStateException(
                    "Plan " + plan.getId() + " has no FAILED items to retry");
        }

        reopenPlanForCompensation(plan, CompensationMode.RETRY);

        for (PlanItem item : failedItems) {
            item.transitionTo(ItemStatus.WAITING);
            item.setFailureReason(null);
            planItemRepository.save(item);
        }

        eventStore.append(plan.getId(), null, "PLAN_RETRY_REQUESTED",
                Map.of("mode", "RETRY", "reason", reason, "failedItemCount", failedItems.size()));
        dispatchReadyItems(plan.getId());
        log.info("Plan RETRY: reset {} FAILED items to WAITING for plan {}", failedItems.size(), plan.getId());
        return new CompensationSummary(CompensationMode.RETRY, failedItems.size(), List.of());
    }

    private CompensationSummary executeAmendment(Plan plan, String reason) {
        if (plan.getStatus() == PlanStatus.RUNNING || plan.getStatus() == PlanStatus.PENDING) {
            throw new IllegalStateException(
                    "Plan " + plan.getId() + " is already open — AMENDMENT requires a terminal plan (COMPLETED or FAILED)");
        }

        reopenPlanForCompensation(plan, CompensationMode.AMENDMENT);

        eventStore.append(plan.getId(), null, "PLAN_AMENDMENT_REQUESTED",
                Map.of("mode", "AMENDMENT", "reason", reason));
        log.info("Plan AMENDMENT: plan {} reopened for operator to add new tasks", plan.getId());
        return new CompensationSummary(CompensationMode.AMENDMENT, 0, List.of());
    }

    /** Transitions the plan to RUNNING (if not already) and records the compensation mode. */
    private void reopenPlanForCompensation(Plan plan, CompensationMode mode) {
        if (plan.getStatus() != PlanStatus.RUNNING) {
            plan.transitionTo(PlanStatus.RUNNING);
            plan.setCompletedAt(null);
        }
        plan.setCompensationMode(mode);
        planRepository.save(plan);
    }

    /**
     * Builds a COMPENSATOR_MANAGER item that will undo the effects of {@code originalItem}.
     * Shared by {@link #createCompensationTask(UUID, String)} and {@link #compensatePlan}.
     */
    private PlanItem buildCompensationItem(PlanItem originalItem, String reason, int ordinal) {
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
                reason.replace("\"", "\\\""));

        return new PlanItem(
                UUID.randomUUID(), ordinal, compKey,
                "Compensate: " + originalItem.getTitle(),
                compDesc,
                WorkerType.COMPENSATOR_MANAGER,
                "compensator-manager",
                List.of(), List.of());
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
     * Cancels a plan and all non-terminal items (#28).
     *
     * <p>Items in WAITING, AWAITING_APPROVAL are immediately transitioned to CANCELLED.
     * Items in DISPATCHED or RUNNING are left as-is — the worker will complete or fail naturally.
     * The plan is transitioned to CANCELLED regardless.</p>
     *
     * @throws IllegalStateException if the plan is not found or cannot transition to CANCELLED
     */
    @Transactional
    public void cancelPlan(UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalStateException("Unknown plan: " + planId));
        plan.transitionTo(PlanStatus.CANCELLED);
        planRepository.save(plan);

        List<PlanItem> items = planItemRepository.findByPlanId(planId);

        // Collect DISPATCHED taskKeys before the transition loop (for in-process interrupt)
        List<String> dispatchedTaskKeys = items.stream()
                .filter(i -> i.getStatus() == ItemStatus.DISPATCHED)
                .map(PlanItem::getTaskKey)
                .toList();

        for (PlanItem item : items) {
            if (item.getStatus().canTransitionTo(ItemStatus.CANCELLED)) {
                item.transitionTo(ItemStatus.CANCELLED);
                item.setCompletedAt(Instant.now());
                planItemRepository.save(item);
                eventPublisher.publishEvent(SpringPlanEvent.forItemStatus(
                        planId, item.getId(), item.getTaskKey(), item.getWorkerProfile(), "CANCELLED"));
            }
        }

        // P1.7 + P2.7: cancel DISPATCHED tasks (in-process + remote cross-JVM)
        if (!dispatchedTaskKeys.isEmpty()) {
            Set<String> remoteTypes = (hybridProps != null) ? hybridProps.getRemoteTypes() : Set.of();
            for (String taskKey : dispatchedTaskKeys) {
                PlanItem item = items.stream()
                        .filter(i -> taskKey.equals(i.getTaskKey())).findFirst().orElse(null);

                if (item != null && remoteWorkerClient != null
                        && remoteTypes.contains(item.getWorkerType().name())) {
                    // Remote worker: send cancel via HTTP
                    boolean cancelled = remoteWorkerClient.cancel(item.getWorkerType().name(), taskKey);
                    log.debug("Remote cancel for task '{}' (type={}): cancelled={}",
                            taskKey, item.getWorkerType(), cancelled);
                } else if (inProcessBroker != null) {
                    // In-process worker: interrupt virtual thread
                    boolean interrupted = inProcessBroker.cancel(taskKey);
                    log.debug("In-process cancel for task '{}': interrupted={}", taskKey, interrupted);
                }
            }
            log.info("Sent cancel signal to {} dispatched task(s) in plan {}", dispatchedTaskKeys.size(), planId);
        }

        log.info("Plan {} cancelled ({} items cancelled)", planId,
                items.stream().filter(i -> i.getStatus() == ItemStatus.CANCELLED).count());
        eventPublisher.publishEvent(SpringPlanEvent.forPlan(SpringPlanEvent.PLAN_CANCELLED, planId));
        eventStore.append(planId, null, SpringPlanEvent.PLAN_CANCELLED,
                Map.of("planId", planId.toString()));
    }

    /**
     * Immediately kills a DISPATCHED or WAITING task by transitioning it to FAILED.
     *
     * <p>The running worker (if any) continues until natural completion, but its result is
     * ignored by the idempotency guard in {@link #onTaskCompleted} (item already terminal).
     * The normal retry flow applies: if attempts remain, the item will be re-queued;
     * otherwise the plan transitions to PAUSED/FAILED as usual.</p>
     *
     * @throws IllegalStateException           if the item is not found
     * @throws IllegalStateTransitionException if the item is not in DISPATCHED or WAITING status
     */
    @Transactional
    public void killItem(UUID itemId) {
        PlanItem item = planItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalStateException("Unknown item: " + itemId));
        if (item.getStatus() != ItemStatus.DISPATCHED && item.getStatus() != ItemStatus.WAITING) {
            throw new IllegalStateTransitionException("PlanItem", item.getId(), item.getStatus(), ItemStatus.FAILED);
        }
        item.transitionTo(ItemStatus.FAILED);
        item.setFailureReason("killed_by_operator");
        item.setCompletedAt(Instant.now());
        planItemRepository.save(item);
        eventPublisher.publishEvent(SpringPlanEvent.forItemStatus(
                item.getPlan().getId(), item.getId(), item.getTaskKey(),
                item.getWorkerProfile(), "FAILED"));
        log.info("Item {} killed by operator (plan={})", itemId, item.getPlan().getId());
        checkPlanCompletion(item.getPlan().getId());
    }

    /**
     * Finds WAITING items with all dependencies satisfied and dispatches them.
     * Skips dispatch if this instance is not the current leader (multi-instance safety).
     */
    private void dispatchReadyItems(UUID planId) {
        if (leaderElectionService != null && !leaderElectionService.isLeader()) {
            log.trace("Not leader — skipping dispatch cycle for plan {}", planId);
            return;
        }
        List<PlanItem> dispatchable = planItemRepository.findDispatchableItems(planId);

        if (dispatchable.isEmpty()) {
            return;
        }

        // Market making: sort by priority (inventory risk model) before dispatching.
        // Re-orders items so high-priority tasks (critical path, underserved worker types) go first.
        Plan plan = planRepository.findById(planId).orElseThrow();
        if (marketMakingDispatcher != null) {
            try {
                dispatchable = marketMakingDispatcher.prioritize(dispatchable, plan);
            } catch (Exception e) {
                log.debug("Market making prioritization failed (non-blocking): {}", e.getMessage());
            }
        }

        Map<String, String> completedResults = loadCompletedResults(planId);
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

            // Fast-path budget pre-check: if budget is hard-exceeded even without GP adjustment,
            // skip expensive GP inference entirely. The full check with gpPrediction runs below.
            if (budget != null) {
                TokenBudgetService.BudgetDecision preCheck =
                        tokenBudgetService.checkBudget(planId, item.getWorkerType().name(), budget, null);
                if (preCheck.action() == TokenBudgetService.BudgetDecision.Action.FAIL) {
                    log.warn("Token budget FAIL_FAST (pre-check) for task {} (workerType={}, used={}, effective={})",
                             item.getTaskKey(), item.getWorkerType(), preCheck.used(), preCheck.effectiveLimit());
                    item.transitionTo(ItemStatus.FAILED);
                    item.setFailureReason("Token budget exceeded (used=" + preCheck.used()
                            + ", effective=" + preCheck.effectiveLimit() + ")");
                    item.setCompletedAt(Instant.now());
                    planItemRepository.save(item);
                    continue;
                }
                // ALLOW or WARN: GP prediction may further adjust the effective limit — proceed normally.
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
                            item.getWorkerProfile(), profileEntry.getWorkerType(),
                            profileEntry.getMcpServers(), profileEntry.getOwnsPaths());
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

            // Bayesian admission control: predict success probability and skip low-probability tasks.
            // Only active when the BayesianSuccessPredictorService bean exists (gp.enabled=true)
            // and the plan requested admission control (admissionControl != null in PlanRequest).
            if (bayesianPredictor != null) {
                try {
                    SuccessPrediction prediction = bayesianPredictor.predictForItem(
                            item, gpPrediction, budget, planId);
                    item.setPredictedSuccessProbability((float) prediction.probability());
                    if (!prediction.shouldDispatch()) {
                        log.info("Bayesian admission: task {} below threshold (P={})",
                                 item.getTaskKey(), String.format("%.3f", prediction.probability()));
                        planItemRepository.save(item);
                        continue; // item stays WAITING
                    }
                } catch (Exception e) {
                    log.debug("Bayesian prediction failed (non-blocking): {}", e.getMessage());
                }
            }

            // Close any orphaned open attempts before creating the new one (B19)
            int closedOrphans = attemptRepository.closeOrphanedAttempts(
                    item.getId(), Instant.now(), "closed-before-dispatch");
            if (closedOrphans > 0) {
                log.warn("Closed {} orphaned DispatchAttempt(s) for item {} before dispatch",
                         closedOrphans, item.getId());
            }

            // Create attempt entity first so its ID can be embedded in the task message for tracing
            int attemptNum = attemptRepository.findMaxAttemptNumber(item.getId()).orElse(0) + 1;
            DispatchAttempt attempt = new DispatchAttempt(UUID.randomUUID(), item, attemptNum);
            UUID traceId = UUID.randomUUID();

            // Use the pre-resolved policy (already fetched for the risk check above).
            HookPolicy policy = preResolvedPolicy;

            // PID adaptive budget: adjust maxTokenBudget based on historical error (#37)
            if (pidBudgetController != null && policy != null) {
                policy = pidBudgetController.adjustPolicy(planId, item.getWorkerType().name(), policy);
            }

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
                plan.getCouncilReport(),  // inject pre-planning council context into every task
                buildDynamicOwnsPaths(plan.getProjectPath(), item.getWorkerProfile()),
                item.getToolHints(),      // planner-suggested MCP tool allowlist (#24L1)
                resolveWorkspacePath(plan),
                item.getModelId()         // optional LLM model override (#20)
            );

            try {
                taskProducer.dispatch(task);
                item.transitionTo(ItemStatus.DISPATCHED);
                item.setDispatchedAt(Instant.now());
                dispatched++;
                metrics.recordItemDispatched(item.getWorkerType().name());
                eventPublisher.publishEvent(new PlanItemDispatchedEvent(
                        planId, item.getId(), item.getTaskKey(), item.getWorkerProfile()));
                // Also publish as SpringPlanEvent so SseEmitterRegistry streams it to the dashboard (#28)
                eventPublisher.publishEvent(SpringPlanEvent.forItem(SpringPlanEvent.TASK_DISPATCHED,
                        planId, item.getId(), item.getTaskKey(), item.getWorkerProfile(), true, 0));
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
        if (anyFailed) { metrics.recordPlanFailed(); } else { metrics.recordPlanCompleted(); }

        // Record plan outcome for Council Taste Profile GP training (#13)
        if (decompositionPredictor != null && !anyFailed) {
            decompositionPredictor.recordOutcome(
                    planId, allItems.size(),
                    hasWorkerType(allItems, WorkerType.CONTEXT_MANAGER),
                    hasWorkerType(allItems, WorkerType.REVIEW),
                    (int) countWorkerType(allItems, WorkerType.BE),
                    (int) countWorkerType(allItems, WorkerType.FE));
        }

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

        // Release per-plan caches to prevent unbounded memory/Redis growth.
        hookManagerService.evictPlan(planId);
        if (pidBudgetController != null) {
            pidBudgetController.evictPlan(planId);
        }
        contextCacheService.evictPlan(planId);

        eventPublisher.publishEvent(new PlanCompletedEvent(
                planId, plan.getStatus(), allItems.size(), failedCount));
        eventPublisher.publishEvent(SpringPlanEvent.forPlan(SpringPlanEvent.PLAN_COMPLETED, planId));
        eventStore.append(planId, null, SpringPlanEvent.PLAN_COMPLETED,
                Map.of("status", plan.getStatus().name(),
                       "totalItems", allItems.size(),
                       "failedItems", failedCount));
    }

    private boolean hasWorkerType(List<PlanItem> items, WorkerType type) {
        return items.stream().anyMatch(i -> i.getWorkerType() == type);
    }

    private long countWorkerType(List<PlanItem> items, WorkerType type) {
        return items.stream().filter(i -> i.getWorkerType() == type).count();
    }

    /**
     * When a CONTEXT_MANAGER task fails, propagate the failure to all WAITING items
     * that depend on it. Without context, those tasks cannot execute meaningfully.
     */
    private void failDependentsOfContextManager(PlanItem cmItem, UUID planId) {
        String cmTaskKey = cmItem.getTaskKey();
        List<PlanItem> waitingItems = planItemRepository.findByPlanIdAndStatus(planId, ItemStatus.WAITING);

        for (PlanItem dependent : waitingItems) {
            if (dependent.getDependsOn().contains(cmTaskKey)) {
                dependent.transitionTo(ItemStatus.FAILED);
                dependent.setFailureReason("context_manager_failed: " + cmTaskKey);
                dependent.setCompletedAt(Instant.now());
                planItemRepository.save(dependent);
                log.warn("Propagated CM failure to dependent task {} (plan={})",
                         dependent.getTaskKey(), planId);
            }
        }
    }

    private Map<String, String> loadCompletedResults(UUID planId) {
        return planItemRepository.findByPlanId(planId).stream()
            .filter(i -> i.getStatus() == ItemStatus.DONE && i.getResult() != null)
            .collect(Collectors.toMap(PlanItem::getTaskKey, PlanItem::getResult));
    }

    private String buildContextJson(PlanItem item, Map<String, String> completedResults) {
        Map<String, String> deps = new LinkedHashMap<>();
        List<String> missing = new java.util.ArrayList<>();
        for (String depKey : item.getDependsOn()) {
            String result = completedResults.get(depKey);
            if (result == null) {
                // Cache fallback: covers race conditions where the CM result is cached in Redis
                // but not yet visible in completedResults (e.g. during rapid retry dispatch).
                result = contextCacheService.get(item.getPlan().getId(), depKey).orElse(null);
                if (result != null) { metrics.recordCacheHit(); } else { metrics.recordCacheMiss(); }
            }
            deps.put(depKey, result != null ? result : "{}");
            if (result == null) {
                missing.add(depKey);
            }
        }
        // B8: diagnostic log to catch dependsOn/completedResults key mismatch.
        // Shows resolved/expected ratio and only the missing keys (not the full completedResults pool).
        int expected = item.getDependsOn().size();
        int resolved = expected - missing.size();
        if (missing.isEmpty()) {
            log.debug("Context for task {}: resolved={}/{} dependency results",
                      item.getTaskKey(), resolved, expected);
        } else {
            log.warn("Context for task {}: resolved={}/{} dependency results — missing keys={}. " +
                     "Available in completedResults pool: {}",
                     item.getTaskKey(), resolved, expected, missing,
                     completedResults.keySet());
        }
        try {
            return objectMapper.writeValueAsString(deps);
        } catch (Exception e) {
            log.warn("Failed to serialize context for task {}: {}", item.getTaskKey(), e.getMessage());
            return "{}";
        }
    }

    /**
     * Builds dynamic ownsPaths by resolving profile-level relative paths against the plan's projectPath.
     * Returns null if no projectPath is set or the profile has no ownsPaths.
     */
    private List<String> buildDynamicOwnsPaths(String projectPath, String workerProfile) {
        if (projectPath == null || projectPath.isBlank() || workerProfile == null) {
            return null;
        }
        WorkerProfileRegistry.ProfileEntry entry = profileRegistry.getProfileEntry(workerProfile);
        if (entry == null || entry.getOwnsPaths().isEmpty()) {
            return null;
        }
        String base = projectPath.endsWith("/") ? projectPath : projectPath + "/";
        return entry.getOwnsPaths().stream()
                .map(p -> base + p)
                .toList();
    }

    /** Resolves the workspace path for task dispatch. Null if no workspace configured. */
    private String resolveWorkspacePath(Plan plan) {
        return plan.getWorkspaceVolume() != null
                ? "/workspace/" + plan.getWorkspaceVolume()
                : null;
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
                List.of(),
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
