package com.agentframework.orchestrator.orchestration;

import com.agentframework.common.policy.HookPolicy;
import com.agentframework.common.policy.RiskLevel;
import com.agentframework.orchestrator.api.dto.PlanRequest;
import com.agentframework.orchestrator.budget.TokenBudgetService;
import com.agentframework.orchestrator.budget.TokenBudgetService.BudgetDecision;
import com.agentframework.orchestrator.config.EnrichmentProperties;
import com.agentframework.orchestrator.council.CouncilProperties;
import com.agentframework.orchestrator.council.CouncilReport;
import com.agentframework.orchestrator.council.CouncilService;
import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.event.PlanCompletedEvent;
import com.agentframework.orchestrator.event.TaskCompletedSideEffectEvent;
import com.agentframework.orchestrator.eventsourcing.PlanEventStore;
import com.agentframework.orchestrator.hooks.HookManagerService;
import com.agentframework.orchestrator.messaging.AgentTaskProducer;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.planner.PlannerService;
import com.agentframework.orchestrator.repository.DispatchAttemptRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import com.agentframework.orchestrator.reward.RewardComputationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrchestrationService} — plan lifecycle, task dispatch,
 * result handling, retry logic, sub-plans, council sessions, and budget enforcement.
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationServiceTest {

    @Mock private PlanRepository planRepository;
    @Mock private PlanItemRepository planItemRepository;
    @Mock private DispatchAttemptRepository attemptRepository;
    @Mock private PlannerService plannerService;
    @Mock private AgentTaskProducer taskProducer;
    @Mock private WorkerProfileRegistry profileRegistry;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private HookManagerService hookManagerService;
    @Mock private TokenBudgetService tokenBudgetService;
    @Mock private PlanEventStore eventStore;
    @Mock private CouncilService councilService;
    @Mock private CouncilProperties councilProperties;
    @Mock private RewardComputationService rewardComputationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new OrchestrationService(
                planRepository, planItemRepository, attemptRepository,
                plannerService, taskProducer, profileRegistry,
                eventPublisher, objectMapper, hookManagerService,
                tokenBudgetService, eventStore, councilService,
                councilProperties, rewardComputationService,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                new EnrichmentInjectorService(new EnrichmentProperties(false, false, false, false)),
                new EnrichmentProperties(false, false, false, false));

        ReflectionTestUtils.setField(service, "defaultMaxAttempts", 3);
        ReflectionTestUtils.setField(service, "defaultBackoffMs", 5000L);
        ReflectionTestUtils.setField(service, "defaultAttemptsBeforePause", 2);
        ReflectionTestUtils.setField(service, "maxContextRetries", 1);
        ReflectionTestUtils.setField(service, "defaultMaxDepth", 3);
    }

    // ── createAndStart ──────────────────────────────────────────────────────

    @Test
    void createAndStart_happyPath_planRunningAndItemsDispatched() {
        String spec = "Build a REST API";
        PlanItem item = createItem(WorkerType.BE, "BE-001", List.of());

        when(plannerService.decompose(any(Plan.class))).thenAnswer(inv -> {
            Plan p = inv.getArgument(0);
            p.addItem(item);
            return p;
        });
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            // dispatchReadyItems calls planRepository.findById(planId).orElseThrow()
            if (item.getPlan() != null && item.getPlan().getId().equals(inv.getArgument(0))) {
                return Optional.of(item.getPlan());
            }
            return Optional.empty();
        });
        when(planItemRepository.findDispatchableItems(any(UUID.class))).thenReturn(List.of(item));
        when(planItemRepository.findByPlanId(any(UUID.class))).thenReturn(List.of(item));
        when(hookManagerService.resolvePolicy(any(), any(), any())).thenReturn(Optional.empty());
        when(attemptRepository.findMaxAttemptNumber(any())).thenReturn(Optional.of(0));
        when(attemptRepository.save(any(DispatchAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        Plan result = service.createAndStart(spec, null, null);

        assertThat(result.getStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(result.getItems()).contains(item);
        verify(plannerService).decompose(any(Plan.class));
        verify(taskProducer).dispatch(any());
        verify(eventStore, atLeast(1)).append(any(), any(), any(), anyMap());
    }

    @Test
    void createAndStart_withBudget_budgetJsonSet() {
        String spec = "Build a REST API";
        PlanRequest.Budget budget = new PlanRequest.Budget("FAIL_FAST", Map.of("BE", 50000L));

        when(plannerService.decompose(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(any(UUID.class))).thenReturn(List.of());

        Plan result = service.createAndStart(spec, budget, null);

        assertThat(result.getBudgetJson()).isNotNull();
        assertThat(result.getBudgetJson()).contains("FAIL_FAST");
        assertThat(result.getBudgetJson()).contains("50000");
    }

    @Test
    void createAndStart_councilEnabled_prePlanningSessionConducted() {
        String spec = "Build a REST API";
        CouncilReport report = new CouncilReport(
                List.of("be-manager"), List.of("Use Repository pattern"),
                null, null, null, null, null, Map.of());

        when(councilProperties.enabled()).thenReturn(true);
        when(councilProperties.prePlanningEnabled()).thenReturn(true);
        when(councilService.conductPrePlanningSession(spec)).thenReturn(report);
        when(plannerService.decompose(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(any(UUID.class))).thenReturn(List.of());

        Plan result = service.createAndStart(spec, null, null);

        verify(councilService).conductPrePlanningSession(spec);
        assertThat(result.getCouncilReport()).isNotNull();
        assertThat(result.getCouncilReport()).contains("be-manager");
    }

    @Test
    void createAndStart_councilDisabled_noPlanningSession() {
        String spec = "Build a REST API";

        when(councilProperties.enabled()).thenReturn(false);
        when(plannerService.decompose(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(any(UUID.class))).thenReturn(List.of());

        Plan result = service.createAndStart(spec, null, null);

        verify(councilService, never()).conductPrePlanningSession(any());
        assertThat(result.getCouncilReport()).isNull();
    }

    @Test
    void createAndStart_councilFails_continuesWithoutCouncil() {
        String spec = "Build a REST API";

        when(councilProperties.enabled()).thenReturn(true);
        when(councilProperties.prePlanningEnabled()).thenReturn(true);
        when(councilService.conductPrePlanningSession(spec)).thenThrow(new RuntimeException("LLM timeout"));
        when(plannerService.decompose(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(any(UUID.class))).thenReturn(List.of());

        Plan result = service.createAndStart(spec, null, null);

        assertThat(result.getCouncilReport()).isNull();
        assertThat(result.getStatus()).isEqualTo(PlanStatus.RUNNING);
    }

    // ── onTaskCompleted — success path ──────────────────────────────────────

    @Test
    void onTaskCompleted_success_itemDoneAndResultStored() {
        UUID planId = UUID.randomUUID();
        PlanItem item = createItemWithPlan(WorkerType.BE, "BE-001", planId, List.of());
        item.transitionTo(ItemStatus.DISPATCHED);

        AgentResult result = successResult(planId, item.getId(), "BE-001", "{\"code\": \"ok\"}");

        stubOnTaskCompletedCommon(item, planId);

        service.onTaskCompleted(result);

        assertThat(item.getStatus()).isEqualTo(ItemStatus.DONE);
        assertThat(item.getResult()).isEqualTo("{\"code\": \"ok\"}");
        assertThat(item.getCompletedAt()).isNotNull();
    }

    @Test
    void onTaskCompleted_success_publishesSideEffectEvent() {
        UUID planId = UUID.randomUUID();
        PlanItem item = createItemWithPlan(WorkerType.BE, "BE-001", planId, List.of());
        item.transitionTo(ItemStatus.DISPATCHED);

        AgentResult result = successResult(planId, item.getId(), "BE-001", "{\"code\": \"ok\"}");

        stubOnTaskCompletedCommon(item, planId);

        service.onTaskCompleted(result);

        // Side-effects moved to TaskCompletedEventHandler; verify event is published
        verify(eventPublisher).publishEvent(
                eq(new TaskCompletedSideEffectEvent(item.getId(), result)));
    }

    @Test
    void onTaskCompleted_reviewWorkerSuccess_publishesSideEffectEvent() {
        UUID planId = UUID.randomUUID();
        PlanItem reviewItem = createItemWithPlan(WorkerType.REVIEW, "RV-001", planId, List.of());
        reviewItem.transitionTo(ItemStatus.DISPATCHED);

        AgentResult result = successResult(planId, reviewItem.getId(), "RV-001",
                "{\"severity\": \"PASS\", \"per_task\": {\"BE-001\": {\"score\": 0.8}}}");

        stubOnTaskCompletedCommon(reviewItem, planId);

        service.onTaskCompleted(result);

        // Side-effects (computeProcessScore, distributeReviewScore) moved to handler
        verify(eventPublisher).publishEvent(
                eq(new TaskCompletedSideEffectEvent(reviewItem.getId(), result)));
    }

    @Test
    void onTaskCompleted_hookManagerSuccess_publishesSideEffectEvent() {
        UUID planId = UUID.randomUUID();
        PlanItem hmItem = createItemWithPlan(WorkerType.HOOK_MANAGER, "HM-001", planId, List.of());
        hmItem.transitionTo(ItemStatus.DISPATCHED);

        String policiesJson = "{\"policies\": {\"BE-001\": {\"allowedTools\": [\"git\"]}}}";
        AgentResult result = successResult(planId, hmItem.getId(), "HM-001", policiesJson);

        stubOnTaskCompletedCommon(hmItem, planId);

        service.onTaskCompleted(result);

        // storePolicies moved to TaskCompletedEventHandler; verify event is published
        verify(eventPublisher).publishEvent(
                eq(new TaskCompletedSideEffectEvent(hmItem.getId(), result)));
    }

    @Test
    void onTaskCompleted_duplicateResult_skipped() {
        UUID planId = UUID.randomUUID();
        PlanItem item = createItemWithPlan(WorkerType.BE, "BE-001", planId, List.of());
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);

        AgentResult result = successResult(planId, item.getId(), "BE-001", "{\"code\": \"ok\"}");

        when(planItemRepository.findByIdWithPlan(item.getId())).thenReturn(Optional.of(item));

        service.onTaskCompleted(result);

        verify(planItemRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── onTaskCompleted — missing context ───────────────────────────────────

    @Test
    void onTaskCompleted_missingContext_createsContextManagerTask() {
        UUID planId = UUID.randomUUID();
        PlanItem item = createItemWithPlan(WorkerType.BE, "BE-001", planId, List.of());
        item.transitionTo(ItemStatus.DISPATCHED);
        Plan plan = item.getPlan();

        String missingCtxJson = "{\"missing_context\": [\"src/main/java/Foo.java\"]}";
        AgentResult result = successResult(planId, item.getId(), "BE-001", missingCtxJson);

        when(planItemRepository.findByIdWithPlan(item.getId())).thenReturn(Optional.of(item));
        when(attemptRepository.findOpenAttempt(item.getId())).thenReturn(Optional.empty());
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());

        service.onTaskCompleted(result);

        // Item should be re-queued to WAITING (DISPATCHED -> WAITING via context retry)
        assertThat(item.getStatus()).isEqualTo(ItemStatus.WAITING);
        assertThat(item.getContextRetryCount()).isEqualTo(1);
        // A new CONTEXT_MANAGER item should have been added to the plan
        assertThat(plan.getItems()).anyMatch(i -> i.getWorkerType() == WorkerType.CONTEXT_MANAGER);
        // The original item should now depend on the CM task
        assertThat(item.getDependsOn()).anyMatch(dep -> dep.startsWith("CM-CTX"));
    }

    @Test
    void onTaskCompleted_missingContext_maxRetriesExceeded_proceedsToDone() {
        UUID planId = UUID.randomUUID();
        PlanItem item = createItemWithPlan(WorkerType.BE, "BE-001", planId, List.of());
        item.transitionTo(ItemStatus.DISPATCHED);
        // Simulate already used the one allowed context retry
        item.incrementContextRetryCount();

        String missingCtxJson = "{\"missing_context\": [\"src/main/java/Bar.java\"]}";
        AgentResult result = successResult(planId, item.getId(), "BE-001", missingCtxJson);

        stubOnTaskCompletedCommon(item, planId);

        service.onTaskCompleted(result);

        // Should proceed to DONE despite missing context since max retries exceeded
        assertThat(item.getStatus()).isEqualTo(ItemStatus.DONE);
        assertThat(item.getResult()).isEqualTo(missingCtxJson);
    }

    // ── onTaskCompleted — failure path ──────────────────────────────────────

    @Test
    void onTaskCompleted_failure_itemFailedAndReasonStored() {
        UUID planId = UUID.randomUUID();
        PlanItem item = createItemWithPlan(WorkerType.BE, "BE-001", planId, List.of());
        item.transitionTo(ItemStatus.DISPATCHED);

        AgentResult result = failureResult(planId, item.getId(), "BE-001", "Compilation error");

        when(planItemRepository.findByIdWithPlan(item.getId())).thenReturn(Optional.of(item));
        when(attemptRepository.findOpenAttempt(item.getId())).thenReturn(Optional.empty());
        when(attemptRepository.findMaxAttemptNumber(item.getId())).thenReturn(Optional.of(1));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of());
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));
        when(planRepository.findById(planId)).thenReturn(Optional.of(item.getPlan()));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onTaskCompleted(result);

        assertThat(item.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(item.getFailureReason()).isEqualTo("Compilation error");
        assertThat(item.getCompletedAt()).isNotNull();
    }

    @Test
    void onTaskCompleted_failure_schedulesRetryWithBackoff() {
        UUID planId = UUID.randomUUID();
        PlanItem item = createItemWithPlan(WorkerType.BE, "BE-001", planId, List.of());
        item.transitionTo(ItemStatus.DISPATCHED);

        AgentResult result = failureResult(planId, item.getId(), "BE-001", "Transient error");

        when(planItemRepository.findByIdWithPlan(item.getId())).thenReturn(Optional.of(item));
        when(attemptRepository.findOpenAttempt(item.getId())).thenReturn(Optional.empty());
        // Attempt 1 of 3 — should schedule retry
        when(attemptRepository.findMaxAttemptNumber(item.getId())).thenReturn(Optional.of(1));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of(item));

        service.onTaskCompleted(result);

        assertThat(item.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(item.getNextRetryAt()).isNotNull();
    }

    @Test
    void onTaskCompleted_failure_attemptsThreshold_pausesPlan() {
        UUID planId = UUID.randomUUID();
        PlanItem item = createItemWithPlan(WorkerType.BE, "BE-001", planId, List.of());
        item.transitionTo(ItemStatus.DISPATCHED);
        Plan plan = item.getPlan();

        AgentResult result = failureResult(planId, item.getId(), "BE-001", "Persistent error");

        when(planItemRepository.findByIdWithPlan(item.getId())).thenReturn(Optional.of(item));
        when(attemptRepository.findOpenAttempt(item.getId())).thenReturn(Optional.empty());
        // Attempt 2 >= defaultAttemptsBeforePause(2) — should pause
        when(attemptRepository.findMaxAttemptNumber(item.getId())).thenReturn(Optional.of(2));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of(item));

        service.onTaskCompleted(result);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.PAUSED);
        assertThat(plan.getPausedAt()).isNotNull();
        verify(planRepository).save(plan);
    }

    // ── dispatch — SUB_PLAN ─────────────────────────────────────────────────

    @Test
    void dispatch_subPlanItem_handledInProcess() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "Build a REST API");
        plan.transitionTo(PlanStatus.RUNNING);

        PlanItem subPlanItem = new PlanItem(
                UUID.randomUUID(), 0, "SP-001", "Sub-plan", "desc",
                WorkerType.SUB_PLAN, null, List.of());
        subPlanItem.setSubPlanSpec("Build auth module");
        plan.addItem(subPlanItem);

        when(planItemRepository.findDispatchableItems(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            if (planId.equals(id) && subPlanItem.getStatus() == ItemStatus.WAITING) {
                return List.of(subPlanItem);
            }
            return List.of();
        });
        when(planItemRepository.findByPlanId(any(UUID.class))).thenReturn(List.of(subPlanItem));
        when(planRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            if (planId.equals(id)) {
                return Optional.of(plan);
            }
            // Return a new plan for the child plan lookup
            return Optional.empty();
        });
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(plannerService.decompose(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findActiveByPlanId(any(UUID.class))).thenReturn(List.of(subPlanItem));

        service.triggerDispatch(planId);

        // SUB_PLAN item transitions to DISPATCHED (awaiting child completion)
        assertThat(subPlanItem.getStatus()).isEqualTo(ItemStatus.DISPATCHED);
        assertThat(subPlanItem.getChildPlanId()).isNotNull();
        // Not dispatched via message broker
        verify(taskProducer, never()).dispatch(any());
    }

    @Test
    void dispatch_subPlanItem_depthLimitExceeded_fails() {
        // Create a plan at depth 3 (== maxDepth) — SUB_PLAN should fail
        UUID parentPlanId = UUID.randomUUID();
        Plan deepPlan = new Plan(UUID.randomUUID(), "Deep spec", parentPlanId, 2); // depth = 3
        deepPlan.transitionTo(PlanStatus.RUNNING);

        PlanItem subPlanItem = new PlanItem(
                UUID.randomUUID(), 0, "SP-001", "Sub-plan", "desc",
                WorkerType.SUB_PLAN, null, List.of());
        subPlanItem.setSubPlanSpec("Go even deeper");
        deepPlan.addItem(subPlanItem);

        when(planItemRepository.findDispatchableItems(deepPlan.getId())).thenReturn(List.of(subPlanItem));
        when(planItemRepository.findByPlanId(deepPlan.getId())).thenReturn(List.of(subPlanItem));
        when(planRepository.findById(deepPlan.getId())).thenReturn(Optional.of(deepPlan));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findActiveByPlanId(deepPlan.getId())).thenReturn(List.of(subPlanItem));

        service.triggerDispatch(deepPlan.getId());

        assertThat(subPlanItem.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(subPlanItem.getFailureReason()).contains("depth limit exceeded");
    }

    // ── dispatch — COUNCIL_MANAGER ──────────────────────────────────────────

    @Test
    void dispatch_councilManagerItem_councilDisabled_immediatelyDone() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "spec");
        plan.transitionTo(PlanStatus.RUNNING);

        // Pre-transition to DISPATCHED so DISPATCHED->DONE is valid in the state machine.
        // In production, handleCouncilManager receives WAITING items; the state machine
        // should arguably include WAITING->DONE for in-process items. This workaround
        // tests the council logic independently of the state machine gap.
        PlanItem councilItem = new PlanItem(
                UUID.randomUUID(), 0, "CL-001", "Council session", "desc",
                WorkerType.COUNCIL_MANAGER, null, List.of());
        plan.addItem(councilItem);
        councilItem.transitionTo(ItemStatus.DISPATCHED);

        when(councilProperties.enabled()).thenReturn(false);
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of(councilItem));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(councilItem));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of());
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.triggerDispatch(planId);

        assertThat(councilItem.getStatus()).isEqualTo(ItemStatus.DONE);
        assertThat(councilItem.getResult()).contains("council_disabled");
        verify(taskProducer, never()).dispatch(any());
    }

    @Test
    void dispatch_councilManagerItem_councilEnabled_conductsCounsel() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "spec");
        plan.transitionTo(PlanStatus.RUNNING);

        // Pre-transition to DISPATCHED for same reason as councilDisabled test
        PlanItem councilItem = new PlanItem(
                UUID.randomUUID(), 0, "CL-001", "Council session", "Advise on auth",
                WorkerType.COUNCIL_MANAGER, null, List.of());
        plan.addItem(councilItem);
        councilItem.transitionTo(ItemStatus.DISPATCHED);

        CouncilReport report = new CouncilReport(
                List.of("security-specialist"), List.of("Use OAuth2"),
                null, null, null, null, null, Map.of());

        when(councilProperties.enabled()).thenReturn(true);
        when(councilProperties.taskSessionEnabled()).thenReturn(true);
        when(councilService.conductTaskSession(eq("Council session"), eq("Advise on auth"), anyMap()))
                .thenReturn(report);
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of(councilItem));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(councilItem));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of());
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.triggerDispatch(planId);

        assertThat(councilItem.getStatus()).isEqualTo(ItemStatus.DONE);
        assertThat(councilItem.getResult()).contains("council_report");
        verify(councilService).conductTaskSession(any(), any(), anyMap());
        verify(taskProducer, never()).dispatch(any());
    }

    // ── dispatch — risk check + budget ──────────────────────────────────────

    @Test
    void dispatch_criticalRiskPolicy_awaitsApproval() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "spec");
        plan.transitionTo(PlanStatus.RUNNING);

        PlanItem item = new PlanItem(
                UUID.randomUUID(), 0, "BE-001", "Dangerous task", "desc",
                WorkerType.BE, null, List.of());
        plan.addItem(item);

        HookPolicy criticalPolicy = new HookPolicy(
                List.of("git"), List.of("src/"), List.of(), true,
                null, List.of(), null, 0, RiskLevel.CRITICAL, null, false);

        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of(item));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(hookManagerService.resolvePolicy(planId, "BE-001", WorkerType.BE))
                .thenReturn(Optional.of(criticalPolicy));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of(item));

        service.triggerDispatch(planId);

        assertThat(item.getStatus()).isEqualTo(ItemStatus.AWAITING_APPROVAL);
        verify(taskProducer, never()).dispatch(any());
    }

    @Test
    void dispatch_tokenBudgetFail_itemFailed() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "spec");
        plan.transitionTo(PlanStatus.RUNNING);
        plan.setBudgetJson("{\"onExceeded\":\"FAIL_FAST\",\"perWorkerType\":{\"BE\":100}}");

        PlanItem item = new PlanItem(
                UUID.randomUUID(), 0, "BE-001", "Task", "desc",
                WorkerType.BE, null, List.of());
        plan.addItem(item);

        BudgetDecision failDecision = new BudgetDecision(BudgetDecision.Action.FAIL, 200, 100, 100);

        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of(item));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(tokenBudgetService.checkBudget(eq(planId), eq("BE"), any(PlanRequest.Budget.class), any()))
                .thenReturn(failDecision);
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of());
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.triggerDispatch(planId);

        assertThat(item.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(item.getFailureReason()).contains("Token budget exceeded");
    }

    @Test
    void dispatch_tokenBudgetSkip_itemNotDispatched() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "spec");
        plan.transitionTo(PlanStatus.RUNNING);
        plan.setBudgetJson("{\"onExceeded\":\"NO_NEW_DISPATCH\",\"perWorkerType\":{\"BE\":100}}");

        PlanItem item = new PlanItem(
                UUID.randomUUID(), 0, "BE-001", "Task", "desc",
                WorkerType.BE, null, List.of());
        plan.addItem(item);

        BudgetDecision skipDecision = new BudgetDecision(BudgetDecision.Action.SKIP, 150, 100, 100);

        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of(item));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(tokenBudgetService.checkBudget(eq(planId), eq("BE"), any(PlanRequest.Budget.class), any()))
                .thenReturn(skipDecision);
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of(item));

        service.triggerDispatch(planId);

        // Item stays WAITING, not dispatched
        assertThat(item.getStatus()).isEqualTo(ItemStatus.WAITING);
        verify(taskProducer, never()).dispatch(any());
    }

    // ── resumePlan ──────────────────────────────────────────────────────────

    @Test
    void resumePlan_pausedPlan_transitionsToRunning() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "spec");
        plan.transitionTo(PlanStatus.RUNNING);
        plan.transitionTo(PlanStatus.PAUSED);

        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());
        // checkPlanCompletion: all items terminal triggers completion.
        // Return at least one active item so checkPlanCompletion exits early
        // and does not try to finalize the plan (avoids unnecessary findByPlanId stub).
        lenient().when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of(
                createItem(WorkerType.BE, "BE-placeholder", List.of())));

        service.resumePlan(planId);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(plan.getPausedAt()).isNull();
        verify(planRepository).save(plan);
    }

    @Test
    void resumePlan_dispatchesReadyItems() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "spec");
        plan.transitionTo(PlanStatus.RUNNING);
        plan.transitionTo(PlanStatus.PAUSED);

        PlanItem item = new PlanItem(
                UUID.randomUUID(), 0, "BE-001", "Task", "desc",
                WorkerType.BE, "be-java", List.of());
        plan.addItem(item);

        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of(item));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));
        when(hookManagerService.resolvePolicy(any(), any(), any())).thenReturn(Optional.empty());
        when(attemptRepository.findMaxAttemptNumber(any())).thenReturn(Optional.of(0));
        when(attemptRepository.save(any(DispatchAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resumePlan(planId);

        verify(taskProducer).dispatch(any());
        assertThat(item.getStatus()).isEqualTo(ItemStatus.DISPATCHED);
    }

    // ── createCompensationTask ──────────────────────────────────────────────

    @Test
    void createCompensationTask_createsCompensatorManagerItem() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "spec");
        plan.transitionTo(PlanStatus.RUNNING);

        PlanItem original = new PlanItem(
                UUID.randomUUID(), 0, "BE-001", "Build user API", "desc",
                WorkerType.BE, "be-java", List.of());
        plan.addItem(original);
        original.transitionTo(ItemStatus.DISPATCHED);
        original.transitionTo(ItemStatus.DONE);
        original.setResult("{\"code\": \"ok\"}");

        when(planItemRepository.findByIdWithPlan(original.getId())).thenReturn(Optional.of(original));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(original));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());

        PlanItem compItem = service.createCompensationTask(original.getId(), "Wrong approach");

        assertThat(compItem.getWorkerType()).isEqualTo(WorkerType.COMPENSATOR_MANAGER);
        assertThat(compItem.getTaskKey()).isEqualTo("COMP-BE-001");
        assertThat(compItem.getTitle()).contains("Compensate");
        assertThat(compItem.getDescription()).contains("Wrong approach");
        assertThat(compItem.getDescription()).contains("BE-001");
        assertThat(plan.getItems()).contains(compItem);
    }

    @Test
    void createCompensationTask_reopensFailedPlan() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "spec");
        plan.transitionTo(PlanStatus.RUNNING);
        plan.transitionTo(PlanStatus.FAILED);

        PlanItem original = new PlanItem(
                UUID.randomUUID(), 0, "BE-001", "Build user API", "desc",
                WorkerType.BE, "be-java", List.of());
        plan.addItem(original);

        when(planItemRepository.findByIdWithPlan(original.getId())).thenReturn(Optional.of(original));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(original));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());

        service.createCompensationTask(original.getId(), "Revert needed");

        // FAILED -> RUNNING is a legal transition in PlanStatus
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(plan.getCompletedAt()).isNull();
    }

    // ── onChildPlanCompleted ────────────────────────────────────────────────

    @Test
    void onChildPlanCompleted_childSuccess_parentItemDone() {
        UUID parentPlanId = UUID.randomUUID();
        UUID childPlanId = UUID.randomUUID();

        Plan parentPlan = new Plan(parentPlanId, "parent spec");
        parentPlan.transitionTo(PlanStatus.RUNNING);
        PlanItem parentItem = new PlanItem(
                UUID.randomUUID(), 0, "SP-001", "Sub-plan", "desc",
                WorkerType.SUB_PLAN, null, List.of());
        parentPlan.addItem(parentItem);
        parentItem.transitionTo(ItemStatus.DISPATCHED);
        parentItem.setChildPlanId(childPlanId);

        Plan childPlan = new Plan(childPlanId, "child spec", parentPlanId, 0);
        childPlan.transitionTo(PlanStatus.RUNNING);
        childPlan.transitionTo(PlanStatus.COMPLETED);

        PlanCompletedEvent event = new PlanCompletedEvent(childPlanId, PlanStatus.COMPLETED, 2, 0);

        when(planRepository.findById(childPlanId)).thenReturn(Optional.of(childPlan));
        when(planItemRepository.findByPlanId(parentPlanId)).thenReturn(List.of(parentItem));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(parentPlanId)).thenReturn(List.of());
        when(planItemRepository.findActiveByPlanId(parentPlanId)).thenReturn(List.of());
        lenient().when(planRepository.findById(parentPlanId)).thenReturn(Optional.of(parentPlan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onChildPlanCompleted(event);

        assertThat(parentItem.getStatus()).isEqualTo(ItemStatus.DONE);
        assertThat(parentItem.getCompletedAt()).isNotNull();
    }

    @Test
    void onChildPlanCompleted_childFailure_parentItemFailed() {
        UUID parentPlanId = UUID.randomUUID();
        UUID childPlanId = UUID.randomUUID();

        Plan parentPlan = new Plan(parentPlanId, "parent spec");
        parentPlan.transitionTo(PlanStatus.RUNNING);
        PlanItem parentItem = new PlanItem(
                UUID.randomUUID(), 0, "SP-001", "Sub-plan", "desc",
                WorkerType.SUB_PLAN, null, List.of());
        parentPlan.addItem(parentItem);
        parentItem.transitionTo(ItemStatus.DISPATCHED);
        parentItem.setChildPlanId(childPlanId);

        Plan childPlan = new Plan(childPlanId, "child spec", parentPlanId, 0);
        childPlan.transitionTo(PlanStatus.RUNNING);
        childPlan.transitionTo(PlanStatus.FAILED);

        PlanCompletedEvent event = new PlanCompletedEvent(childPlanId, PlanStatus.FAILED, 2, 1);

        when(planRepository.findById(childPlanId)).thenReturn(Optional.of(childPlan));
        when(planItemRepository.findByPlanId(parentPlanId)).thenReturn(List.of(parentItem));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(parentPlanId)).thenReturn(List.of());
        when(planItemRepository.findActiveByPlanId(parentPlanId)).thenReturn(List.of());
        lenient().when(planRepository.findById(parentPlanId)).thenReturn(Optional.of(parentPlan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onChildPlanCompleted(event);

        assertThat(parentItem.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(parentItem.getFailureReason()).contains("Child plan");
        assertThat(parentItem.getFailureReason()).contains(childPlanId.toString());
    }

    @Test
    void onChildPlanCompleted_notChildPlan_ignored() {
        UUID rootPlanId = UUID.randomUUID();
        Plan rootPlan = new Plan(rootPlanId, "root spec");
        // parentPlanId is null — this is a root plan, not a child

        PlanCompletedEvent event = new PlanCompletedEvent(rootPlanId, PlanStatus.COMPLETED, 3, 0);

        when(planRepository.findById(rootPlanId)).thenReturn(Optional.of(rootPlan));

        service.onChildPlanCompleted(event);

        // No parent item lookup should happen
        verify(planItemRepository, never()).findByPlanId(any());
        verify(planItemRepository, never()).save(any());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Stubs the common mocks needed by most onTaskCompleted success tests.
     * Covers findById, findOpenAttempt, findMaxAttemptNumber, save, and
     * the checkPlanCompletion path (findActiveByPlanId, findByPlanId, planRepository).
     */
    private void stubOnTaskCompletedCommon(PlanItem item, UUID planId) {
        when(planItemRepository.findByIdWithPlan(item.getId())).thenReturn(Optional.of(item));
        when(attemptRepository.findOpenAttempt(item.getId())).thenReturn(Optional.empty());
        lenient().when(attemptRepository.findMaxAttemptNumber(item.getId())).thenReturn(Optional.of(1));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of());
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));
        when(planRepository.findById(planId)).thenReturn(Optional.of(item.getPlan()));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PlanItem createItem(WorkerType type, String taskKey, List<String> deps) {
        return new PlanItem(UUID.randomUUID(), 0, taskKey, "Test: " + taskKey,
                "Description for " + taskKey, type, null, deps);
    }

    /**
     * Creates a PlanItem already attached to a new RUNNING Plan.
     */
    private PlanItem createItemWithPlan(WorkerType type, String taskKey, UUID planId, List<String> deps) {
        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);
        PlanItem item = new PlanItem(UUID.randomUUID(), 0, taskKey, "Test: " + taskKey,
                "Description for " + taskKey, type, null, deps);
        plan.addItem(item);
        return item;
    }

    private AgentResult successResult(UUID planId, UUID itemId, String taskKey, String resultJson) {
        return new AgentResult(planId, itemId, taskKey, true, resultJson, null,
                500L, "BE", null, null, null, null, null);
    }

    private AgentResult failureResult(UUID planId, UUID itemId, String taskKey, String failureReason) {
        return new AgentResult(planId, itemId, taskKey, false, null, failureReason,
                100L, "BE", null, null, null, null, null);
    }
}
