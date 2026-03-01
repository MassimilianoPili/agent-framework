package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.event.PlanCompletedEvent;
import com.agentframework.orchestrator.planner.PromptLoader;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import com.agentframework.orchestrator.repository.QualityGateReportRepository;
import com.agentframework.orchestrator.reward.EloRatingService;
import com.agentframework.orchestrator.reward.PreferencePairGenerator;
import com.agentframework.orchestrator.reward.RewardComputationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link QualityGateService}.
 * Verifies report generation, reward signal distribution, error handling,
 * and the presence of async/transactional annotations.
 */
@ExtendWith(MockitoExtension.class)
class QualityGateServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private PromptLoader promptLoader;
    @Mock private PlanRepository planRepository;
    @Mock private PlanItemRepository planItemRepository;
    @Mock private QualityGateReportRepository reportRepository;
    @Mock private RewardComputationService rewardComputationService;
    @Mock private EloRatingService eloRatingService;
    @Mock private PreferencePairGenerator preferencePairGenerator;
    @Mock private RalphLoopService ralphLoopService;

    private QualityGateService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Reusable mock objects for the ChatClient fluent chain
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponse;

    private static final UUID PLAN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new QualityGateService(
            chatClient, promptLoader, planRepository, planItemRepository,
            reportRepository, objectMapper, rewardComputationService,
            eloRatingService, preferencePairGenerator, ralphLoopService
        );
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponse = mock(ChatClient.CallResponseSpec.class);

        // Ralph-loop: by default returns empty (no re-queue) so reward signals proceed.
        lenient().when(ralphLoopService.evaluateAndRetry(any(), anyBoolean(), any()))
            .thenReturn(List.of());
    }

    // ── Annotation verification ──────────────────────────────────────────────

    @Test
    void onPlanCompleted_hasAsyncAnnotation() throws Exception {
        Method method = QualityGateService.class.getMethod("onPlanCompleted", PlanCompletedEvent.class);
        Async async = method.getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("orchestratorAsyncExecutor");
    }

    @Test
    void onPlanCompleted_hasTransactionalEventListenerAnnotation() throws Exception {
        Method method = QualityGateService.class.getMethod("onPlanCompleted", PlanCompletedEvent.class);
        TransactionalEventListener tel = method.getAnnotation(TransactionalEventListener.class);
        assertThat(tel).isNotNull();
        assertThat(tel.phase())
            .isEqualTo(org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT);
    }

    // ── Successful report generation ─────────────────────────────────────────

    @Test
    void onPlanCompleted_generatesReport_andSaves() {
        Plan plan = createPlan();
        PlanItem item = createItem(plan, "T1", WorkerType.BE, "be-java");
        item.forceStatus(ItemStatus.DONE);

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(item));
        stubPrompts();
        stubChatClient("{\"passed\":true,\"summary\":\"All good\",\"findings\":[\"clean code\"]}");
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onPlanCompleted(new PlanCompletedEvent(PLAN_ID, PlanStatus.COMPLETED, 1, 0));

        ArgumentCaptor<QualityGateReport> captor = ArgumentCaptor.forClass(QualityGateReport.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().isPassed()).isTrue();
        assertThat(captor.getValue().getSummary()).isEqualTo("All good");
        assertThat(captor.getValue().getFindings()).containsExactly("clean code");
    }

    @Test
    void onPlanCompleted_callsRewardSignals() {
        Plan plan = createPlan();
        PlanItem item = createItem(plan, "T1", WorkerType.BE, "be-java");
        item.forceStatus(ItemStatus.DONE);

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(item));
        stubPrompts();
        stubChatClient("{\"passed\":true,\"summary\":\"ok\",\"findings\":[]}");
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(preferencePairGenerator.generateForPlan(PLAN_ID)).thenReturn(3);

        service.onPlanCompleted(new PlanCompletedEvent(PLAN_ID, PlanStatus.COMPLETED, 1, 0));

        verify(rewardComputationService).distributeQualityGateSignal(PLAN_ID, true);
        verify(eloRatingService).updateRatingsForPlan(PLAN_ID);
        verify(preferencePairGenerator).generateForPlan(PLAN_ID);
    }

    @Test
    void onPlanCompleted_failedGate_setsPassedFalse() {
        Plan plan = createPlan();
        PlanItem item = createItem(plan, "T1", WorkerType.BE, "be-java");
        item.forceStatus(ItemStatus.DONE);

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(item));
        stubPrompts();
        stubChatClient("{\"passed\":false,\"summary\":\"bugs found\",\"findings\":[\"bug1\",\"bug2\"]}");
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onPlanCompleted(new PlanCompletedEvent(PLAN_ID, PlanStatus.COMPLETED, 1, 0));

        ArgumentCaptor<QualityGateReport> captor = ArgumentCaptor.forClass(QualityGateReport.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().isPassed()).isFalse();
        assertThat(captor.getValue().getFindings()).hasSize(2);
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void onPlanCompleted_llmError_savesFailReport() {
        Plan plan = createPlan();
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of());
        stubPrompts();
        stubChatClientThrowing(new RuntimeException("LLM timeout"));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onPlanCompleted(new PlanCompletedEvent(PLAN_ID, PlanStatus.COMPLETED, 0, 0));

        ArgumentCaptor<QualityGateReport> captor = ArgumentCaptor.forClass(QualityGateReport.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().isPassed()).isFalse();
        assertThat(captor.getValue().getSummary()).contains("LLM timeout");
    }

    @Test
    void onPlanCompleted_llmError_doesNotCallRewardSignals() {
        Plan plan = createPlan();
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of());
        stubPrompts();
        stubChatClientThrowing(new RuntimeException("LLM error"));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onPlanCompleted(new PlanCompletedEvent(PLAN_ID, PlanStatus.COMPLETED, 0, 0));

        verifyNoInteractions(rewardComputationService);
        verifyNoInteractions(eloRatingService);
        verifyNoInteractions(preferencePairGenerator);
    }

    @Test
    void onPlanCompleted_malformedJson_savesFailedParsing() {
        Plan plan = createPlan();
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of());
        stubPrompts();
        stubChatClient("this is not JSON");
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onPlanCompleted(new PlanCompletedEvent(PLAN_ID, PlanStatus.COMPLETED, 0, 0));

        ArgumentCaptor<QualityGateReport> captor = ArgumentCaptor.forClass(QualityGateReport.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().isPassed()).isFalse();
        assertThat(captor.getValue().getSummary()).contains("Failed to parse");
    }

    @Test
    void onPlanCompleted_planNotFound_throws() {
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
            service.onPlanCompleted(new PlanCompletedEvent(PLAN_ID, PlanStatus.COMPLETED, 0, 0))
        );
    }

    @Test
    void onPlanCompleted_multipleItems_serializesAllResults() {
        Plan plan = createPlan();
        PlanItem item1 = createItem(plan, "T1", WorkerType.BE, "be-java");
        item1.forceStatus(ItemStatus.DONE);
        item1.setResult("result 1");
        PlanItem item2 = createItem(plan, "T2", WorkerType.FE, "fe-react");
        item2.forceStatus(ItemStatus.DONE);
        item2.setResult("result 2");

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(item1, item2));
        stubPrompts();
        stubChatClient("{\"passed\":true,\"summary\":\"ok\",\"findings\":[]}");
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onPlanCompleted(new PlanCompletedEvent(PLAN_ID, PlanStatus.COMPLETED, 2, 0));

        verify(reportRepository).save(any(QualityGateReport.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Plan createPlan() {
        Plan plan = new Plan(PLAN_ID, "test spec");
        plan.forceStatus(PlanStatus.COMPLETED);
        return plan;
    }

    private PlanItem createItem(Plan plan, String taskKey, WorkerType workerType, String profile) {
        PlanItem item = new PlanItem(
            UUID.randomUUID(), 1, taskKey, "Title " + taskKey,
            "Description", workerType, profile, List.of()
        );
        plan.addItem(item);
        return item;
    }

    private void stubPrompts() {
        lenient().when(promptLoader.load(anyString())).thenReturn("system prompt");
        lenient().when(promptLoader.renderQualityGatePrompt(anyString(), anyString(), anyString()))
            .thenReturn("user prompt");
    }

    private void stubChatClient(String response) {
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponse);
        when(callResponse.content()).thenReturn(response);
    }

    private void stubChatClientThrowing(RuntimeException exception) {
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(exception);
    }
}
