package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.artifact.ArtifactStore;
import com.agentframework.orchestrator.workspace.WorkspaceManager;
import com.agentframework.orchestrator.budget.CostEstimationService;
import com.agentframework.orchestrator.budget.TokenBudgetService;
import com.agentframework.orchestrator.budget.TokenLedgerService;
import com.agentframework.orchestrator.config.EnrichmentProperties;
import com.agentframework.orchestrator.council.CouncilProperties;
import com.agentframework.orchestrator.council.CouncilService;
import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.eventsourcing.PlanEventStore;
import com.agentframework.orchestrator.hooks.HookManagerService;
import com.agentframework.orchestrator.messaging.AgentTaskProducer;
import com.agentframework.orchestrator.metrics.OrchestratorMetrics;
import com.agentframework.orchestrator.planner.PlannerService;
import com.agentframework.orchestrator.repository.DispatchAttemptRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.FileModificationRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import com.agentframework.orchestrator.reward.RewardComputationService;
import com.agentframework.orchestrator.cache.ContextCacheService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrchestrationService#killItem(UUID)} (#29 Fase 1).
 *
 * <ul>
 *   <li>DISPATCHED items are immediately transitioned to FAILED("killed_by_operator").</li>
 *   <li>WAITING items are immediately transitioned to FAILED("killed_by_operator").</li>
 *   <li>DONE items throw IllegalStateTransitionException (terminal state, cannot be killed).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class KillItemTest {

    @Mock private PlanRepository planRepository;
    @Mock private PlanItemRepository planItemRepository;
    @Mock private DispatchAttemptRepository attemptRepository;
    @Mock private PlannerService plannerService;
    @Mock private AgentTaskProducer taskProducer;
    @Mock private WorkerProfileRegistry profileRegistry;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private HookManagerService hookManagerService;
    @Mock private TokenBudgetService tokenBudgetService;
    @Mock private TokenLedgerService tokenLedgerService;
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
                Optional.empty(),
                Optional.empty(), Optional.empty(),
                tokenLedgerService,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new com.agentframework.orchestrator.graph.DagHashService());

        ReflectionTestUtils.setField(service, "defaultMaxAttempts", 3);
        ReflectionTestUtils.setField(service, "defaultBackoffMs", 5000L);
        ReflectionTestUtils.setField(service, "defaultAttemptsBeforePause", 2);
        ReflectionTestUtils.setField(service, "maxContextRetries", 1);
        ReflectionTestUtils.setField(service, "defaultMaxDepth", 3);
    }

    @Test
    void killItem_dispatchedItem_becomesFailedWithReason() {
        UUID planId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);

        PlanItem item = new PlanItem(itemId, 0, "BE-001", "Backend",
                "Implement API", WorkerType.BE, "be-java", List.of(), List.of());
        plan.addItem(item);
        item.transitionTo(ItemStatus.DISPATCHED);

        when(planItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of());
        // checkPlanCompletion: active empty → loads plan + all items to decide COMPLETED/FAILED
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));

        service.killItem(itemId);

        assertThat(item.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(item.getFailureReason()).isEqualTo("killed_by_operator");
        assertThat(item.getCompletedAt()).isNotNull();
        verify(planItemRepository).save(item);
        verify(eventPublisher, atLeastOnce()).publishEvent(any(Object.class));
    }

    @Test
    void killItem_waitingItem_becomesFailedWithReason() {
        UUID planId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);

        PlanItem item = new PlanItem(itemId, 0, "FE-001", "Frontend",
                "Build UI", WorkerType.FE, "fe-react", List.of(), List.of());
        plan.addItem(item); // initial status is WAITING

        when(planItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findActiveByPlanId(planId)).thenReturn(List.of());
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));

        service.killItem(itemId);

        assertThat(item.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(item.getFailureReason()).isEqualTo("killed_by_operator");
        assertThat(item.getCompletedAt()).isNotNull();
    }

    @Test
    void killItem_doneItem_throwsIllegalStateTransition() {
        UUID planId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);

        PlanItem item = new PlanItem(itemId, 0, "BE-001", "Backend",
                "Implement API", WorkerType.BE, "be-java", List.of(), List.of());
        plan.addItem(item);
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);  // simulate completed item

        when(planItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.killItem(itemId))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("cannot transition from DONE to FAILED");

        // No save should have been called
        verify(planItemRepository, never()).save(any());
    }
}
