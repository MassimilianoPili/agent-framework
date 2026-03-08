package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.artifact.ArtifactStore;
import com.agentframework.orchestrator.workspace.WorkspaceManager;
import com.agentframework.orchestrator.budget.CostEstimationService;
import com.agentframework.orchestrator.budget.TokenBudgetService;
import com.agentframework.orchestrator.config.EnrichmentProperties;
import com.agentframework.orchestrator.council.CouncilProperties;
import com.agentframework.orchestrator.council.CouncilService;
import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.eventsourcing.PlanEventStore;
import com.agentframework.orchestrator.hooks.HookManagerService;
import com.agentframework.orchestrator.messaging.AgentTaskProducer;
import com.agentframework.orchestrator.metrics.OrchestratorMetrics;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.planner.PlannerService;
import com.agentframework.orchestrator.repository.DispatchAttemptRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.FileModificationRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import com.agentframework.orchestrator.reward.RewardComputationService;
import com.agentframework.orchestrator.cache.ContextCacheService;
import com.agentframework.orchestrator.gp.PlanDecompositionPredictor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for CONTEXT_MANAGER failure propagation (S8-E).
 * When a CM task fails, its dependents should be marked FAILED with a descriptive reason.
 */
@ExtendWith(MockitoExtension.class)
class MissingContextPropagationTest {

    @Mock private PlanRepository planRepository;
    @Mock private PlanItemRepository planItemRepository;
    @Mock private DispatchAttemptRepository attemptRepository;
    @Mock private PlannerService plannerService;
    @Mock private AgentTaskProducer taskProducer;
    @Mock private WorkerProfileRegistry profileRegistry;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private HookManagerService hookManagerService;
    @Mock private TokenBudgetService tokenBudgetService;
    @Mock private CostEstimationService costEstimationService;
    @Mock private PlanEventStore eventStore;
    @Mock private CouncilService councilService;
    @Mock private CouncilProperties councilProperties;
    @Mock private RewardComputationService rewardComputationService;
    @Mock private ArtifactStore artifactStore;
    @Mock private WorkspaceManager workspaceManager;
    @Mock private EnrichmentInjectorService enrichmentInjectorService;
    @Mock private EnrichmentProperties enrichmentProperties;
    @Mock private ContextCacheService contextCacheService;
    @Mock private OrchestratorMetrics metrics;
    @Mock private FileModificationRepository fileModificationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new OrchestrationService(
                planRepository, planItemRepository, attemptRepository,
                plannerService, taskProducer, profileRegistry,
                eventPublisher, objectMapper, hookManagerService,
                tokenBudgetService, costEstimationService, eventStore, councilService,
                councilProperties, rewardComputationService,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                artifactStore, workspaceManager,
                enrichmentInjectorService, enrichmentProperties,
                contextCacheService,
                Optional.empty(),
                metrics,
                Optional.empty(),
                fileModificationRepository,
                Optional.empty());

        ReflectionTestUtils.setField(service, "defaultMaxAttempts", 3);
        ReflectionTestUtils.setField(service, "defaultBackoffMs", 5000L);
        ReflectionTestUtils.setField(service, "defaultAttemptsBeforePause", 2);
        ReflectionTestUtils.setField(service, "maxContextRetries", 1);
        ReflectionTestUtils.setField(service, "defaultMaxDepth", 3);
    }

    @Test
    void onTaskCompleted_cmFailure_failsDependentWaitingTasks() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);

        // CM item (will fail)
        PlanItem cmItem = new PlanItem(UUID.randomUUID(), 0, "CM-001", "Context Manager",
                "Build context", WorkerType.CONTEXT_MANAGER, null, List.of(), List.of());
        plan.addItem(cmItem);
        cmItem.transitionTo(ItemStatus.DISPATCHED);

        // Dependent BE item (WAITING, depends on CM-001)
        PlanItem beItem = new PlanItem(UUID.randomUUID(), 1, "BE-001", "Backend",
                "Implement API", WorkerType.BE, "be-java", List.of("CM-001"), List.of());
        plan.addItem(beItem);

        AgentResult result = new AgentResult(planId, cmItem.getId(), "CM-001", false,
                null, "Context build failed", 100L, "CONTEXT_MANAGER", null,
                null, null, null, null, null, null);

        when(planItemRepository.findByIdWithPlan(cmItem.getId())).thenReturn(Optional.of(cmItem));
        when(attemptRepository.findOpenAttempt(cmItem.getId())).thenReturn(Optional.empty());
        when(attemptRepository.findMaxAttemptNumber(cmItem.getId())).thenReturn(Optional.of(1));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findByPlanIdAndStatus(planId, ItemStatus.WAITING))
                .thenReturn(List.of(beItem));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of());
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(cmItem, beItem));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onTaskCompleted(result);

        // CM should be FAILED
        assertThat(cmItem.getStatus()).isEqualTo(ItemStatus.FAILED);

        // Dependent BE item should also be FAILED with propagated reason
        assertThat(beItem.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(beItem.getFailureReason()).isEqualTo("context_manager_failed: CM-001");
        assertThat(beItem.getCompletedAt()).isNotNull();
    }

    @Test
    void onTaskCompleted_cmFailure_independentItemsUnaffected() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);

        PlanItem cmItem = new PlanItem(UUID.randomUUID(), 0, "CM-001", "Context Manager",
                "Build context", WorkerType.CONTEXT_MANAGER, null, List.of(), List.of());
        plan.addItem(cmItem);
        cmItem.transitionTo(ItemStatus.DISPATCHED);

        // Independent item (no dependency on CM-001)
        PlanItem independentItem = new PlanItem(UUID.randomUUID(), 1, "FE-001", "Frontend",
                "Build UI", WorkerType.FE, "fe-react", List.of(), List.of());
        plan.addItem(independentItem);

        AgentResult result = new AgentResult(planId, cmItem.getId(), "CM-001", false,
                null, "Context build failed", 100L, "CONTEXT_MANAGER", null,
                null, null, null, null, null, null);

        when(planItemRepository.findByIdWithPlan(cmItem.getId())).thenReturn(Optional.of(cmItem));
        when(attemptRepository.findOpenAttempt(cmItem.getId())).thenReturn(Optional.empty());
        when(attemptRepository.findMaxAttemptNumber(cmItem.getId())).thenReturn(Optional.of(1));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findByPlanIdAndStatus(planId, ItemStatus.WAITING))
                .thenReturn(List.of(independentItem));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of());
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(cmItem, independentItem));
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onTaskCompleted(result);

        // Independent item should NOT be affected
        assertThat(independentItem.getStatus()).isEqualTo(ItemStatus.WAITING);
        assertThat(independentItem.getFailureReason()).isNull();
    }

    @Test
    void onTaskCompleted_nonCmFailure_doesNotPropagate() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);

        PlanItem beItem = new PlanItem(UUID.randomUUID(), 0, "BE-001", "Backend",
                "Implement API", WorkerType.BE, "be-java", List.of(), List.of());
        plan.addItem(beItem);
        beItem.transitionTo(ItemStatus.DISPATCHED);

        AgentResult result = new AgentResult(planId, beItem.getId(), "BE-001", false,
                null, "Compilation error", 100L, "BE", null, null, null, null, null, null, null);

        when(planItemRepository.findByIdWithPlan(beItem.getId())).thenReturn(Optional.of(beItem));
        when(attemptRepository.findOpenAttempt(beItem.getId())).thenReturn(Optional.empty());
        when(attemptRepository.findMaxAttemptNumber(beItem.getId())).thenReturn(Optional.of(1));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findDispatchableItems(planId)).thenReturn(List.of());
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of(beItem));

        service.onTaskCompleted(result);

        // Should NOT call findByPlanIdAndStatus for CM propagation
        verify(planItemRepository, never()).findByPlanIdAndStatus(any(), eq(ItemStatus.WAITING));
    }
}
