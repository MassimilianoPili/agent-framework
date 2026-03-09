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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrchestrationService#cancelPlan(UUID)} (#28 S12).
 *
 * <ul>
 *   <li>WAITING items are immediately transitioned to CANCELLED.</li>
 *   <li>DISPATCHED items are left untouched — the worker completes naturally.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CancelPlanTest {

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
                Optional.empty(),
                Optional.empty(), Optional.empty(),
                Optional.empty());

        ReflectionTestUtils.setField(service, "defaultMaxAttempts", 3);
        ReflectionTestUtils.setField(service, "defaultBackoffMs", 5000L);
        ReflectionTestUtils.setField(service, "defaultAttemptsBeforePause", 2);
        ReflectionTestUtils.setField(service, "maxContextRetries", 1);
        ReflectionTestUtils.setField(service, "defaultMaxDepth", 3);
    }

    @Test
    void cancelPlan_waitingItemsBecomeCancelled() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);

        PlanItem waitingItem = new PlanItem(UUID.randomUUID(), 0, "BE-001", "Backend",
                "Implement API", WorkerType.BE, "be-java", List.of(), List.of());
        plan.addItem(waitingItem); // initial status is WAITING

        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(waitingItem));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancelPlan(planId);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.CANCELLED);
        assertThat(waitingItem.getStatus()).isEqualTo(ItemStatus.CANCELLED);
        assertThat(waitingItem.getCompletedAt()).isNotNull();
    }

    @Test
    void cancelPlan_dispatchedItemsUnchanged() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);

        PlanItem dispatchedItem = new PlanItem(UUID.randomUUID(), 0, "BE-001", "Backend",
                "Implement API", WorkerType.BE, "be-java", List.of(), List.of());
        plan.addItem(dispatchedItem);
        dispatchedItem.transitionTo(ItemStatus.DISPATCHED);

        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(dispatchedItem));

        service.cancelPlan(planId);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.CANCELLED);
        // DISPATCHED cannot transition to CANCELLED — worker keeps running until completion
        assertThat(dispatchedItem.getStatus()).isEqualTo(ItemStatus.DISPATCHED);
        // planItemRepository.save() should NOT have been called for the dispatched item
        verify(planItemRepository, never()).save(dispatchedItem);
    }
}
