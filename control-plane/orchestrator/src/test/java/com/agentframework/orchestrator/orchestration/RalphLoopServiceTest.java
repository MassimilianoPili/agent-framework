package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RalphLoopService}.
 *
 * <p>Tests cover the core ralph-loop scenarios:</p>
 * <ol>
 *   <li>Gate passed → no re-queue</li>
 *   <li>Gate failed → domain items re-queued, infra items untouched</li>
 *   <li>Max iterations exhausted → no re-queue</li>
 *   <li>Plan reopened COMPLETED → RUNNING</li>
 *   <li>Feedback text stored on re-queued items</li>
 *   <li>Disabled flag → no-op</li>
 *   <li>Mixed eligible/ineligible items</li>
 *   <li>Empty findings → no re-queue</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RalphLoopServiceTest {

    @Mock private PlanRepository planRepository;
    @Mock private PlanItemRepository planItemRepository;

    private RalphLoopService service;

    private static final UUID PLAN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RalphLoopService(planRepository, planItemRepository);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxIterations", 2);
    }

    // ── 1. Gate passed → no re-queue ──────────────────────────────────────────

    @Test
    void evaluateAndRetry_gatePassed_returnsEmpty() {
        List<UUID> result = service.evaluateAndRetry(PLAN_ID, true, List.of("finding1"));

        assertThat(result).isEmpty();
        verifyNoInteractions(planRepository);
        verifyNoInteractions(planItemRepository);
    }

    // ── 2. Gate failed → domain items re-queued ───────────────────────────────

    @Test
    void evaluateAndRetry_gateFailed_requeuesOnlyDomainItems() {
        Plan plan = createPlan(PlanStatus.COMPLETED);
        PlanItem beItem = createItem(plan, "BE-001", WorkerType.BE);
        beItem.forceStatus(ItemStatus.DONE);
        PlanItem cmItem = createItem(plan, "CM-001", WorkerType.CONTEXT_MANAGER);
        cmItem.forceStatus(ItemStatus.DONE);

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(beItem, cmItem));

        List<UUID> result = service.evaluateAndRetry(PLAN_ID, false, List.of("bug found"));

        assertThat(result).containsExactly(beItem.getId());
        // BE item: DONE → WAITING
        assertThat(beItem.getStatus()).isEqualTo(ItemStatus.WAITING);
        assertThat(beItem.getRalphLoopCount()).isEqualTo(1);
        // CM item: untouched (infrastructure worker)
        assertThat(cmItem.getStatus()).isEqualTo(ItemStatus.DONE);
        assertThat(cmItem.getRalphLoopCount()).isZero();
    }

    // ── 3. Max iterations exhausted ───────────────────────────────────────────

    @Test
    void evaluateAndRetry_maxIterationsReached_doesNotRequeue() {
        Plan plan = createPlan(PlanStatus.COMPLETED);
        PlanItem item = createItem(plan, "BE-001", WorkerType.BE);
        item.forceStatus(ItemStatus.DONE);
        // Simulate 2 previous iterations (max is 2)
        item.incrementRalphLoopCount();
        item.incrementRalphLoopCount();

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(item));

        List<UUID> result = service.evaluateAndRetry(PLAN_ID, false, List.of("still broken"));

        assertThat(result).isEmpty();
        // Item should stay DONE — not re-queued
        assertThat(item.getStatus()).isEqualTo(ItemStatus.DONE);
    }

    // ── 4. Plan reopened COMPLETED → RUNNING ──────────────────────────────────

    @Test
    void evaluateAndRetry_gateFailed_reopensPlan() {
        Plan plan = createPlan(PlanStatus.COMPLETED);
        PlanItem item = createItem(plan, "FE-001", WorkerType.FE);
        item.forceStatus(ItemStatus.DONE);

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(item));

        service.evaluateAndRetry(PLAN_ID, false, List.of("UI issue"));

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.RUNNING);
        verify(planRepository).save(plan);
    }

    // ── 5. Feedback text stored on items ──────────────────────────────────────

    @Test
    void evaluateAndRetry_storesFeedbackOnRequeuedItems() {
        Plan plan = createPlan(PlanStatus.COMPLETED);
        PlanItem item = createItem(plan, "BE-001", WorkerType.BE);
        item.forceStatus(ItemStatus.DONE);

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(item));

        service.evaluateAndRetry(PLAN_ID, false, List.of("error handling missing", "no tests"));

        assertThat(item.getLastQualityGateFeedback())
            .isEqualTo("error handling missing\nno tests");
        assertThat(item.getCompletedAt()).isNull();
    }

    // ── 6. Disabled flag → no-op ──────────────────────────────────────────────

    @Test
    void evaluateAndRetry_disabled_returnsEmpty() {
        ReflectionTestUtils.setField(service, "enabled", false);

        List<UUID> result = service.evaluateAndRetry(PLAN_ID, false, List.of("finding"));

        assertThat(result).isEmpty();
        verifyNoInteractions(planRepository);
    }

    // ── 7. Mixed eligible/ineligible items ────────────────────────────────────

    @Test
    void evaluateAndRetry_mixedItems_requeueOnlyEligible() {
        Plan plan = createPlan(PlanStatus.COMPLETED);
        // Domain workers: eligible
        PlanItem beItem = createItem(plan, "BE-001", WorkerType.BE);
        beItem.forceStatus(ItemStatus.DONE);
        PlanItem aiItem = createItem(plan, "AI-001", WorkerType.AI_TASK);
        aiItem.forceStatus(ItemStatus.DONE);
        // FAILED item: not DONE, so not eligible
        PlanItem failedItem = createItem(plan, "BE-002", WorkerType.BE);
        failedItem.forceStatus(ItemStatus.FAILED);
        // REVIEW: infrastructure, not eligible
        PlanItem reviewItem = createItem(plan, "RV-001", WorkerType.REVIEW);
        reviewItem.forceStatus(ItemStatus.DONE);
        // BE with exhausted iterations: not eligible
        PlanItem exhausted = createItem(plan, "BE-003", WorkerType.BE);
        exhausted.forceStatus(ItemStatus.DONE);
        exhausted.incrementRalphLoopCount();
        exhausted.incrementRalphLoopCount();

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(
            List.of(beItem, aiItem, failedItem, reviewItem, exhausted));

        List<UUID> result = service.evaluateAndRetry(PLAN_ID, false, List.of("issues"));

        // Only BE-001 and AI-001 should be re-queued
        assertThat(result).containsExactlyInAnyOrder(beItem.getId(), aiItem.getId());
        assertThat(beItem.getStatus()).isEqualTo(ItemStatus.WAITING);
        assertThat(aiItem.getStatus()).isEqualTo(ItemStatus.WAITING);
        assertThat(failedItem.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(reviewItem.getStatus()).isEqualTo(ItemStatus.DONE);
        assertThat(exhausted.getStatus()).isEqualTo(ItemStatus.DONE);
    }

    // ── 8b. DBA and MOBILE are domain workers ────────────────────────────────

    @Test
    void evaluateAndRetry_gateFailed_requeuesDbaAndMobile() {
        Plan plan = createPlan(PlanStatus.COMPLETED);
        PlanItem dbaItem = createItem(plan, "DBA-001", WorkerType.DBA);
        dbaItem.forceStatus(ItemStatus.DONE);
        PlanItem mobileItem = createItem(plan, "MOB-001", WorkerType.MOBILE);
        mobileItem.forceStatus(ItemStatus.DONE);

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(dbaItem, mobileItem));

        List<UUID> result = service.evaluateAndRetry(PLAN_ID, false, List.of("schema issue"));

        assertThat(result).containsExactlyInAnyOrder(dbaItem.getId(), mobileItem.getId());
        assertThat(dbaItem.getStatus()).isEqualTo(ItemStatus.WAITING);
        assertThat(mobileItem.getStatus()).isEqualTo(ItemStatus.WAITING);
    }

    // ── 8. Empty findings → no re-queue ───────────────────────────────────────

    @Test
    void evaluateAndRetry_emptyFindings_returnsEmpty() {
        List<UUID> result = service.evaluateAndRetry(PLAN_ID, false, List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(planRepository);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Plan createPlan(PlanStatus status) {
        Plan plan = new Plan(PLAN_ID, "test spec");
        plan.forceStatus(status);
        return plan;
    }

    private PlanItem createItem(Plan plan, String taskKey, WorkerType workerType) {
        PlanItem item = new PlanItem(
            UUID.randomUUID(), 1, taskKey, "Title " + taskKey,
            "Description", workerType, null, List.of()
        );
        plan.addItem(item);
        return item;
    }
}
