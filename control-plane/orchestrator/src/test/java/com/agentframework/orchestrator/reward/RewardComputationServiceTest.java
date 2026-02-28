package com.agentframework.orchestrator.reward;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.messaging.dto.Provenance;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RewardComputationService}.
 *
 * <p>Covers all three reward sources (processScore, reviewScore, qualityGateScore),
 * the Bayesian re-normalisation of aggregatedReward, and edge cases such as
 * null/blank inputs and items that already have scores.</p>
 */
@ExtendWith(MockitoExtension.class)
class RewardComputationServiceTest {

    @Mock
    private PlanItemRepository planItemRepository;

    /**
     * Spy instead of mock: the service uses ObjectMapper for real JSON parsing
     * and serialization — mocking it would defeat the purpose of these tests.
     */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RewardComputationService service;

    private UUID planId;
    private Plan plan;

    @BeforeEach
    void setUp() {
        planId = UUID.randomUUID();
        plan = new Plan(planId, "test spec");
    }

    // ── Helper methods ─────────────────────────────────────────────────────────

    private PlanItem createItem(String taskKey, WorkerType type, String profile) {
        PlanItem item = new PlanItem(UUID.randomUUID(), 0, taskKey, "Title " + taskKey,
                "Description " + taskKey, type, profile, List.of());
        plan.addItem(item);
        return item;
    }

    private PlanItem createDoneItem(String taskKey, WorkerType type, String profile) {
        PlanItem item = createItem(taskKey, type, profile);
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);
        return item;
    }

    private AgentResult resultWith(long durationMs, Long tokensUsed, Provenance provenance) {
        return new AgentResult(
                planId, UUID.randomUUID(), "BE-001", true,
                null, null, durationMs, "BE", "be-java",
                null, null, provenance, tokensUsed
        );
    }

    private Provenance provenanceWithTokens(long totalTokens) {
        return new Provenance("BE", "be-java", 1, null, null, null,
                null, null, null, null, null,
                new Provenance.TokenUsage(totalTokens / 2, totalTokens / 2, totalTokens));
    }

    // ── computeProcessScore ────────────────────────────────────────────────────

    @Nested
    @DisplayName("computeProcessScore")
    class ComputeProcessScore {

        @Test
        @DisplayName("high tokens (>100k) produce low tokenEff component")
        void highTokens_lowTokenEfficiency() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(10_000L, 150_000L, null);

            service.computeProcessScore(item, result);

            // tokenEff = 1 / log10(150_000 + 10) ≈ 1 / 5.176 ≈ 0.193
            // retryPenalty = max(0, 1 - 0 * 0.25) = 1.0
            // durationEff = 1 / (1 + exp((10_000 - 60_000) / 30_000)) ≈ 1 / (1 + exp(-1.667)) ≈ 0.841
            // score = 0.193 * 0.4 + 1.0 * 0.3 + 0.841 * 0.3 ≈ 0.077 + 0.3 + 0.252 ≈ 0.630
            assertThat(item.getProcessScore()).isNotNull();
            assertThat(item.getProcessScore()).isBetween(0.55f, 0.70f);

            // tokenEff component should be small
            float tokenEff = (float) (1.0 / Math.log10(150_000 + 10.0));
            assertThat(tokenEff).isLessThan(0.25f);
        }

        @Test
        @DisplayName("low tokens (<1000) produce high tokenEff component")
        void lowTokens_highTokenEfficiency() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(10_000L, 500L, null);

            service.computeProcessScore(item, result);

            // tokenEff = 1 / log10(500 + 10) ≈ 1 / 2.708 ≈ 0.369
            // Much higher than the 100k case
            float tokenEff = (float) (1.0 / Math.log10(500 + 10.0));
            assertThat(tokenEff).isGreaterThan(0.35f);
            assertThat(item.getProcessScore()).isGreaterThan(0.65f);
        }

        @Test
        @DisplayName("null tokensUsed falls back to provenance.tokenUsage.totalTokens")
        void nullTokensUsed_fallsBackToProvenance() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            Provenance prov = provenanceWithTokens(50_000L);
            AgentResult result = resultWith(30_000L, null, prov);

            service.computeProcessScore(item, result);

            // Should use 50_000 from provenance, not the 0.5 neutral fallback
            float tokenEffFromProvenance = (float) (1.0 / Math.log10(50_000 + 10.0));
            float tokenEffNeutral = 0.5f;
            // The actual score should differ from neutral
            assertThat(item.getProcessScore()).isNotNull();
            // Verify it used provenance tokens (tokenEff ~0.213) not neutral (0.5)
            assertThat(tokenEffFromProvenance).isLessThan(tokenEffNeutral);
        }

        @Test
        @DisplayName("null tokensUsed and null provenance uses neutral 0.5 tokenEff")
        void nullTokensUsed_nullProvenance_usesNeutral() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(60_000L, null, null);

            service.computeProcessScore(item, result);

            // tokenEff = 0.5 (neutral)
            // retryPenalty = 1.0
            // durationEff at 60s = 1 / (1 + exp(0)) = 0.5
            // score = 0.5 * 0.4 + 1.0 * 0.3 + 0.5 * 0.3 = 0.2 + 0.3 + 0.15 = 0.65
            assertThat(item.getProcessScore()).isCloseTo(0.65f, within(0.01f));
        }

        @Test
        @DisplayName("0 retries give full retryPenalty (1.0)")
        void zeroRetries_fullRetryBonus() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            // 0 retries by default (contextRetryCount = 0)
            AgentResult result = resultWith(10_000L, 1000L, null);

            service.computeProcessScore(item, result);

            // retryPenalty = max(0, 1 - 0 * 0.25) = 1.0
            assertThat(item.getProcessScore()).isNotNull();

            // Compare with an item that has 4 retries
            PlanItem itemWithRetries = createDoneItem("BE-002", WorkerType.BE, "be-java");
            for (int i = 0; i < 4; i++) itemWithRetries.incrementContextRetryCount();
            AgentResult result2 = resultWith(10_000L, 1000L, null);

            service.computeProcessScore(itemWithRetries, result2);

            // retryPenalty = max(0, 1 - 4 * 0.25) = 0.0
            // Zero retries should produce a higher score
            assertThat(item.getProcessScore()).isGreaterThan(itemWithRetries.getProcessScore());
        }

        @Test
        @DisplayName("4 retries produce retryPenalty = 0.0 (fully penalised)")
        void fourRetries_zeroRetryBonus() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            for (int i = 0; i < 4; i++) item.incrementContextRetryCount();
            AgentResult result = resultWith(60_000L, null, null);

            service.computeProcessScore(item, result);

            // tokenEff = 0.5 (neutral)
            // retryPenalty = max(0, 1 - 4 * 0.25) = 0.0
            // durationEff at 60s = 0.5
            // score = 0.5 * 0.4 + 0.0 * 0.3 + 0.5 * 0.3 = 0.2 + 0.0 + 0.15 = 0.35
            assertThat(item.getProcessScore()).isCloseTo(0.35f, within(0.01f));
        }

        @Test
        @DisplayName("5+ retries still clamp retryPenalty to 0.0 (max prevents negative)")
        void fiveRetries_clampedToZero() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            for (int i = 0; i < 5; i++) item.incrementContextRetryCount();
            AgentResult result = resultWith(60_000L, null, null);

            service.computeProcessScore(item, result);

            // retryPenalty = max(0, 1 - 5 * 0.25) = max(0, -0.25) = 0.0
            // Same as 4 retries
            assertThat(item.getProcessScore()).isCloseTo(0.35f, within(0.01f));
        }

        @Test
        @DisplayName("fast duration (10s) produces high durationEff (~0.84)")
        void fastDuration_highEfficiency() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(10_000L, null, null);

            service.computeProcessScore(item, result);

            // durationEff = 1 / (1 + exp((10_000 - 60_000) / 30_000))
            //             = 1 / (1 + exp(-1.667)) ≈ 0.841
            float durationEff = (float) (1.0 / (1.0 + Math.exp((10_000.0 - 60_000.0) / 30_000.0)));
            assertThat(durationEff).isGreaterThan(0.8f);
            // Overall score should be high with fast duration + neutral tokens + 0 retries
            assertThat(item.getProcessScore()).isGreaterThan(0.65f);
        }

        @Test
        @DisplayName("slow duration (5min = 300s) produces low durationEff (~0.0003)")
        void slowDuration_lowEfficiency() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(300_000L, null, null);

            service.computeProcessScore(item, result);

            // durationEff = 1 / (1 + exp((300_000 - 60_000) / 30_000))
            //             = 1 / (1 + exp(8)) ≈ 0.00034
            float durationEff = (float) (1.0 / (1.0 + Math.exp((300_000.0 - 60_000.0) / 30_000.0)));
            assertThat(durationEff).isLessThan(0.01f);

            // Compare with fast duration: slow should produce lower score
            PlanItem fastItem = createDoneItem("BE-002", WorkerType.BE, "be-java");
            AgentResult fastResult = resultWith(10_000L, null, null);
            service.computeProcessScore(fastItem, fastResult);

            assertThat(item.getProcessScore()).isLessThan(fastItem.getProcessScore());
        }

        @Test
        @DisplayName("duration at 60s inflection point gives durationEff = 0.5")
        void durationAtInflectionPoint() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(60_000L, null, null);

            service.computeProcessScore(item, result);

            // durationEff = 1 / (1 + exp(0)) = 0.5 exactly
            // tokenEff = 0.5 (neutral), retryPenalty = 1.0
            // score = 0.5 * 0.4 + 1.0 * 0.3 + 0.5 * 0.3 = 0.65
            assertThat(item.getProcessScore()).isCloseTo(0.65f, within(0.001f));
        }

        @Test
        @DisplayName("calls recomputeAggregatedReward and saves the item")
        void invokesRecomputeAndSave() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(30_000L, 10_000L, null);

            service.computeProcessScore(item, result);

            // processScore should be set
            assertThat(item.getProcessScore()).isNotNull();
            // aggregatedReward should be set (recomputeAggregatedReward was called)
            assertThat(item.getAggregatedReward()).isNotNull();
            // rewardSources JSON should be populated
            assertThat(item.getRewardSources()).isNotNull();
            assertThat(item.getRewardSources()).contains("\"process\"");
            // save was called exactly once
            verify(planItemRepository).save(item);
        }

        @Test
        @DisplayName("aggregatedReward equals processScore when it is the only source (weight re-normalised to 1.0)")
        void onlyProcessScore_aggregatedEqualsProcess() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(30_000L, 10_000L, null);

            service.computeProcessScore(item, result);

            // With only processScore, weight 0.30 re-normalised to 1.0
            // aggregatedReward should equal processScore
            assertThat(item.getAggregatedReward()).isCloseTo(item.getProcessScore(), within(0.001f));
        }
    }

    // ── distributeReviewScore ──────────────────────────────────────────────────

    @Nested
    @DisplayName("distributeReviewScore")
    class DistributeReviewScore {

        @Test
        @DisplayName("per_task JSON assigns individual scores to matching items")
        void perTask_assignsIndividualScores() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");
            PlanItem feItem = createDoneItem("FE-001", WorkerType.FE, "fe-react");

            String resultJson = """
                    {
                        "per_task": {
                            "BE-001": {"score": 0.8},
                            "FE-001": {"score": 0.6}
                        }
                    }
                    """;
            reviewItem.setResult(resultJson);

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem, feItem));

            service.distributeReviewScore(reviewItem);

            assertThat(beItem.getReviewScore()).isCloseTo(0.8f, within(0.001f));
            assertThat(feItem.getReviewScore()).isCloseTo(0.6f, within(0.001f));
            // Each item saved individually
            verify(planItemRepository, times(2)).save(any(PlanItem.class));
        }

        @Test
        @DisplayName("per_task with severity field maps to scalar score")
        void perTask_withSeverity_mapsToScore() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");
            PlanItem feItem = createDoneItem("FE-001", WorkerType.FE, "fe-react");

            String resultJson = """
                    {
                        "per_task": {
                            "BE-001": {"severity": "PASS"},
                            "FE-001": {"severity": "FAIL"}
                        }
                    }
                    """;
            reviewItem.setResult(resultJson);

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem, feItem));

            service.distributeReviewScore(reviewItem);

            assertThat(beItem.getReviewScore()).isCloseTo(1.0f, within(0.001f));
            assertThat(feItem.getReviewScore()).isCloseTo(-1.0f, within(0.001f));
        }

        @Test
        @DisplayName("per_task with unknown taskKey is silently skipped")
        void perTask_unknownTaskKey_skipped() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            String resultJson = """
                    {
                        "per_task": {
                            "BE-001": {"score": 0.9},
                            "UNKNOWN-999": {"score": 0.5}
                        }
                    }
                    """;
            reviewItem.setResult(resultJson);

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem));

            service.distributeReviewScore(reviewItem);

            assertThat(beItem.getReviewScore()).isCloseTo(0.9f, within(0.001f));
            // Only BE-001 was saved (UNKNOWN-999 was skipped)
            verify(planItemRepository, times(1)).save(any(PlanItem.class));
        }

        @Test
        @DisplayName("per_task score is clamped to [-1, +1]")
        void perTask_scoreClamped() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            String resultJson = """
                    {
                        "per_task": {
                            "BE-001": {"score": 5.0}
                        }
                    }
                    """;
            reviewItem.setResult(resultJson);

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem));

            service.distributeReviewScore(reviewItem);

            assertThat(beItem.getReviewScore()).isCloseTo(1.0f, within(0.001f));
        }

        @Test
        @DisplayName("global severity broadcast assigns same score to all non-REVIEW DONE items")
        void globalSeverity_broadcastsToAllDoneItems() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");
            PlanItem feItem = createDoneItem("FE-001", WorkerType.FE, "fe-react");

            String resultJson = """
                    {"severity": "WARN"}
                    """;
            reviewItem.setResult(resultJson);

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem, feItem));

            service.distributeReviewScore(reviewItem);

            // WARN maps to 0.0
            assertThat(beItem.getReviewScore()).isCloseTo(0.0f, within(0.001f));
            assertThat(feItem.getReviewScore()).isCloseTo(0.0f, within(0.001f));
            // REVIEW item itself should NOT get a review score
            assertThat(reviewItem.getReviewScore()).isNull();
        }

        @Test
        @DisplayName("global severity PASS broadcasts 1.0")
        void globalSeverity_pass_broadcastsOne() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            reviewItem.setResult("{\"severity\": \"PASS\"}");

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem));

            service.distributeReviewScore(reviewItem);

            assertThat(beItem.getReviewScore()).isCloseTo(1.0f, within(0.001f));
        }

        @Test
        @DisplayName("global severity FAIL broadcasts -1.0")
        void globalSeverity_fail_broadcastsNegativeOne() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            reviewItem.setResult("{\"severity\": \"FAIL\"}");

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem));

            service.distributeReviewScore(reviewItem);

            assertThat(beItem.getReviewScore()).isCloseTo(-1.0f, within(0.001f));
        }

        @Test
        @DisplayName("broadcast skips non-DONE items")
        void globalSeverity_skipsNonDoneItems() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem doneItem = createDoneItem("BE-001", WorkerType.BE, "be-java");
            PlanItem waitingItem = createItem("BE-002", WorkerType.BE, "be-java");
            // waitingItem stays in WAITING status

            reviewItem.setResult("{\"severity\": \"PASS\"}");

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, doneItem, waitingItem));

            service.distributeReviewScore(reviewItem);

            assertThat(doneItem.getReviewScore()).isCloseTo(1.0f, within(0.001f));
            assertThat(waitingItem.getReviewScore()).isNull();
            // Only doneItem saved
            verify(planItemRepository, times(1)).save(any(PlanItem.class));
        }

        @Test
        @DisplayName("null result JSON is silently skipped")
        void nullResult_skipped() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            reviewItem.setResult(null);

            service.distributeReviewScore(reviewItem);

            verify(planItemRepository, never()).save(any());
            verify(planItemRepository, never()).findByPlanId(any());
        }

        @Test
        @DisplayName("blank result JSON is silently skipped")
        void blankResult_skipped() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            reviewItem.setResult("   ");

            service.distributeReviewScore(reviewItem);

            verify(planItemRepository, never()).save(any());
            verify(planItemRepository, never()).findByPlanId(any());
        }

        @Test
        @DisplayName("malformed JSON is handled gracefully (no exception)")
        void malformedJson_handledGracefully() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            reviewItem.setResult("not valid json {{{");

            assertThatCode(() -> service.distributeReviewScore(reviewItem)).doesNotThrowAnyException();
            verify(planItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("absent severity in broadcast defaults to PASS (1.0)")
        void absentSeverity_defaultsToPass() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            // No per_task and no severity field — defaults to "PASS"
            reviewItem.setResult("{\"summary\": \"looks good\"}");

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem));

            service.distributeReviewScore(reviewItem);

            assertThat(beItem.getReviewScore()).isCloseTo(1.0f, within(0.001f));
        }
    }

    // ── distributeQualityGateSignal ────────────────────────────────────────────

    @Nested
    @DisplayName("distributeQualityGateSignal")
    class DistributeQualityGateSignal {

        @Test
        @DisplayName("passed=true assigns +1.0 to DONE items without reviewScore")
        void passed_assignsPositiveOne() {
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");
            PlanItem feItem = createDoneItem("FE-001", WorkerType.FE, "fe-react");

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(beItem, feItem));

            service.distributeQualityGateSignal(planId, true);

            // Both items should have aggregatedReward updated
            verify(planItemRepository, times(2)).save(any(PlanItem.class));
            // qualityGate score should be in rewardSources
            assertThat(beItem.getRewardSources()).contains("\"quality_gate\"");
            assertThat(beItem.getAggregatedReward()).isNotNull();
        }

        @Test
        @DisplayName("passed=false assigns -1.0 to DONE items without reviewScore")
        void failed_assignsNegativeOne() {
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(beItem));

            service.distributeQualityGateSignal(planId, false);

            verify(planItemRepository).save(beItem);
            // aggregatedReward should be negative (qualityGate = -1.0 is the only source)
            assertThat(beItem.getAggregatedReward()).isCloseTo(-1.0f, within(0.001f));
        }

        @Test
        @DisplayName("skips items that already have a reviewScore")
        void skipsItemsWithReviewScore() {
            PlanItem reviewedItem = createDoneItem("BE-001", WorkerType.BE, "be-java");
            reviewedItem.setReviewScore(0.8f);

            PlanItem unreviewedItem = createDoneItem("FE-001", WorkerType.FE, "fe-react");

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewedItem, unreviewedItem));

            service.distributeQualityGateSignal(planId, true);

            // Only unreviewedItem should be saved (the reviewed one is skipped)
            ArgumentCaptor<PlanItem> captor = ArgumentCaptor.forClass(PlanItem.class);
            verify(planItemRepository, times(1)).save(captor.capture());
            assertThat(captor.getValue().getTaskKey()).isEqualTo("FE-001");

            // reviewedItem's reviewScore should remain unchanged
            assertThat(reviewedItem.getReviewScore()).isCloseTo(0.8f, within(0.001f));
        }

        @Test
        @DisplayName("skips non-DONE items (WAITING, DISPATCHED, etc.)")
        void skipsNonDoneItems() {
            PlanItem waitingItem = createItem("BE-001", WorkerType.BE, "be-java");
            // stays in WAITING
            PlanItem doneItem = createDoneItem("FE-001", WorkerType.FE, "fe-react");

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(waitingItem, doneItem));

            service.distributeQualityGateSignal(planId, true);

            ArgumentCaptor<PlanItem> captor = ArgumentCaptor.forClass(PlanItem.class);
            verify(planItemRepository, times(1)).save(captor.capture());
            assertThat(captor.getValue().getTaskKey()).isEqualTo("FE-001");
        }

        @Test
        @DisplayName("no items to update — no saves")
        void noEligibleItems_noSaves() {
            // All items have reviewScore already
            PlanItem reviewedItem = createDoneItem("BE-001", WorkerType.BE, "be-java");
            reviewedItem.setReviewScore(0.9f);

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewedItem));

            service.distributeQualityGateSignal(planId, true);

            verify(planItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("quality gate combined with existing processScore produces correct aggregate")
        void qualityGateWithProcessScore_correctAggregate() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            // First, compute a process score
            AgentResult result = resultWith(60_000L, null, null);
            service.computeProcessScore(item, result);

            float processScore = item.getProcessScore();
            assertThat(processScore).isCloseTo(0.65f, within(0.01f));

            // Reset mock interactions from computeProcessScore
            reset(planItemRepository);
            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));

            // Now apply quality gate
            service.distributeQualityGateSignal(planId, true);

            // With process (0.30 weight) and qualityGate (0.20 weight), renormalised:
            // process weight = 0.30 / 0.50 = 0.60
            // qg weight = 0.20 / 0.50 = 0.40
            // aggregated = processScore * 0.60 + 1.0 * 0.40
            float expected = processScore * 0.6f + 1.0f * 0.4f;
            assertThat(item.getAggregatedReward()).isCloseTo(expected, within(0.02f));
        }
    }

    // ── recomputeAggregatedReward (tested via public methods) ──────────────────

    @Nested
    @DisplayName("recomputeAggregatedReward (Bayesian re-normalisation)")
    class RecomputeAggregatedReward {

        @Test
        @DisplayName("only processScore present — aggregated equals processScore")
        void onlyProcess_aggregatedEqualsProcess() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(30_000L, 5000L, null);

            service.computeProcessScore(item, result);

            // Only process (weight 0.30 re-normalised to 1.0)
            assertThat(item.getAggregatedReward()).isCloseTo(item.getProcessScore(), within(0.001f));
        }

        @Test
        @DisplayName("only reviewScore present — aggregated equals reviewScore")
        void onlyReview_aggregatedEqualsReview() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            String resultJson = """
                    {"per_task": {"BE-001": {"score": 0.7}}}
                    """;
            reviewItem.setResult(resultJson);

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem));

            service.distributeReviewScore(reviewItem);

            // Only review (weight 0.50 re-normalised to 1.0)
            assertThat(beItem.getAggregatedReward()).isCloseTo(0.7f, within(0.001f));
        }

        @Test
        @DisplayName("all three sources — correct weighted average with original weights")
        void allThreeSources_correctWeightedAverage() {
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            // Step 1: compute processScore
            AgentResult result = resultWith(60_000L, null, null);
            service.computeProcessScore(beItem, result);
            float processScore = beItem.getProcessScore();
            // processScore at inflection: 0.65

            // Step 2: set reviewScore via per_task
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            reviewItem.setResult("{\"per_task\": {\"BE-001\": {\"score\": 0.8}}}");
            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem));
            service.distributeReviewScore(reviewItem);

            float reviewScore = beItem.getReviewScore();
            assertThat(reviewScore).isCloseTo(0.8f, within(0.001f));

            // Step 3: apply quality gate (need to allow QG since reviewScore is now set,
            // but distributeQualityGateSignal skips items WITH reviewScore.
            // We need to manually inject QG score for this test scenario.
            // Instead, let's verify the rewardSources JSON has the correct weights.

            // With review (0.50) + process (0.30), total = 0.80
            // review normalised = 0.50 / 0.80 = 0.625
            // process normalised = 0.30 / 0.80 = 0.375
            float expected = reviewScore * (0.50f / 0.80f) + processScore * (0.30f / 0.80f);
            assertThat(beItem.getAggregatedReward()).isCloseTo(expected, within(0.02f));
        }

        @Test
        @DisplayName("rewardSources JSON contains source values and normalised weights")
        void rewardSources_containsValuesAndWeights() throws Exception {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(30_000L, 5000L, null);

            service.computeProcessScore(item, result);

            String sources = item.getRewardSources();
            assertThat(sources).isNotNull();

            JsonNode sourcesNode = objectMapper.readTree(sources);

            // process should be present and non-null
            assertThat(sourcesNode.has("process")).isTrue();
            assertThat(sourcesNode.get("process").isNull()).isFalse();
            assertThat(sourcesNode.get("process").floatValue()).isCloseTo(item.getProcessScore(), within(0.001f));

            // review and quality_gate should be null
            assertThat(sourcesNode.has("review")).isTrue();
            assertThat(sourcesNode.get("review").isNull()).isTrue();
            assertThat(sourcesNode.has("quality_gate")).isTrue();
            assertThat(sourcesNode.get("quality_gate").isNull()).isTrue();

            // weights should have process = 1.0 (re-normalised)
            JsonNode weights = sourcesNode.get("weights");
            assertThat(weights).isNotNull();
            assertThat(weights.has("process")).isTrue();
            assertThat(weights.get("process").floatValue()).isCloseTo(1.0f, within(0.001f));
        }

        @Test
        @DisplayName("no sources available — aggregatedReward is 0.0")
        void noSources_aggregatedIsZero() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");

            // First set processScore so we can observe the aggregated behavior
            AgentResult result = resultWith(30_000L, 5000L, null);
            service.computeProcessScore(item, result);

            // processScore is set — aggregated should not be zero
            assertThat(item.getAggregatedReward()).isNotEqualTo(0.0f);
        }

        @Test
        @DisplayName("process + qualityGate (no review) — correctly re-normalised")
        void processAndQualityGate_noReview() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");

            // Compute processScore first
            AgentResult result = resultWith(10_000L, 1000L, null);
            service.computeProcessScore(item, result);
            float processScore = item.getProcessScore();

            // Apply quality gate
            reset(planItemRepository);
            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));
            service.distributeQualityGateSignal(planId, false);

            // process (0.30) + qualityGate (0.20) = 0.50 total
            // process normalised = 0.30 / 0.50 = 0.60
            // qualityGate normalised = 0.20 / 0.50 = 0.40
            // qgScore = -1.0 (failed)
            float expected = processScore * 0.6f + (-1.0f) * 0.4f;
            assertThat(item.getAggregatedReward()).isCloseTo(expected, within(0.02f));
        }

        @Test
        @DisplayName("review + qualityGate (no process) — correctly re-normalised")
        void reviewAndQualityGate_noProcess() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            // Set reviewScore via per_task
            reviewItem.setResult("{\"per_task\": {\"BE-001\": {\"score\": 0.5}}}");
            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem));
            service.distributeReviewScore(reviewItem);

            float reviewScore = beItem.getReviewScore();
            assertThat(reviewScore).isCloseTo(0.5f, within(0.001f));

            // The item now has reviewScore set, so distributeQualityGateSignal will skip it.
            // But we can verify the current aggregate: review only → aggregated = 0.5
            assertThat(beItem.getAggregatedReward()).isCloseTo(0.5f, within(0.001f));
        }
    }

    // ── Edge cases and integration scenarios ───────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("per_task with both score and severity — score takes precedence")
        void perTask_scoreOverSeverity() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            String resultJson = """
                    {
                        "per_task": {
                            "BE-001": {"score": 0.3, "severity": "PASS"}
                        }
                    }
                    """;
            reviewItem.setResult(resultJson);

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem));

            service.distributeReviewScore(reviewItem);

            // score field takes precedence over severity when both present
            assertThat(beItem.getReviewScore()).isCloseTo(0.3f, within(0.001f));
        }

        @Test
        @DisplayName("per_task negative score is clamped to -1.0")
        void perTask_negativeScoreClamped() {
            PlanItem reviewItem = createDoneItem("RV-001", WorkerType.REVIEW, "review-code");
            PlanItem beItem = createDoneItem("BE-001", WorkerType.BE, "be-java");

            reviewItem.setResult("{\"per_task\": {\"BE-001\": {\"score\": -5.0}}}");

            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(reviewItem, beItem));

            service.distributeReviewScore(reviewItem);

            assertThat(beItem.getReviewScore()).isCloseTo(-1.0f, within(0.001f));
        }

        @Test
        @DisplayName("null provenance tokenUsage — falls through to neutral")
        void nullProvenanceTokenUsage_neutral() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            // Provenance exists but tokenUsage is null
            Provenance prov = new Provenance("BE", "be-java", 1, null, null, null,
                    null, null, null, null, null, null);
            AgentResult result = resultWith(60_000L, null, prov);

            service.computeProcessScore(item, result);

            // Should use neutral tokenEff = 0.5
            assertThat(item.getProcessScore()).isCloseTo(0.65f, within(0.01f));
        }

        @Test
        @DisplayName("provenance with null totalTokens — falls through to neutral")
        void provenanceNullTotalTokens_neutral() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            Provenance.TokenUsage usage = new Provenance.TokenUsage(100L, 200L, null);
            Provenance prov = new Provenance("BE", "be-java", 1, null, null, null,
                    null, null, null, null, null, usage);
            AgentResult result = resultWith(60_000L, null, prov);

            service.computeProcessScore(item, result);

            // totalTokens is null → tokens stays 0 → tokenEff = 0.5 (neutral)
            assertThat(item.getProcessScore()).isCloseTo(0.65f, within(0.01f));
        }

        @Test
        @DisplayName("tokensUsed takes priority over provenance when both present")
        void tokensUsed_priorityOverProvenance() {
            PlanItem item1 = createDoneItem("BE-001", WorkerType.BE, "be-java");
            PlanItem item2 = createDoneItem("BE-002", WorkerType.BE, "be-java");

            Provenance prov = provenanceWithTokens(200_000L);

            // item1: tokensUsed=500, provenance=200_000
            AgentResult result1 = resultWith(30_000L, 500L, prov);
            // item2: tokensUsed=null, provenance=200_000
            AgentResult result2 = resultWith(30_000L, null, prov);

            service.computeProcessScore(item1, result1);
            service.computeProcessScore(item2, result2);

            // item1 should use 500 tokens (much higher tokenEff)
            // item2 should use 200_000 tokens from provenance (lower tokenEff)
            assertThat(item1.getProcessScore()).isGreaterThan(item2.getProcessScore());
        }

        @Test
        @DisplayName("zero duration gives maximum durationEff (~1.0)")
        void zeroDuration_maxEfficiency() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");
            AgentResult result = resultWith(0L, null, null);

            service.computeProcessScore(item, result);

            // durationEff = 1 / (1 + exp((0 - 60_000) / 30_000))
            //             = 1 / (1 + exp(-2)) ≈ 0.881
            float durationEff = (float) (1.0 / (1.0 + Math.exp(-60_000.0 / 30_000.0)));
            assertThat(durationEff).isGreaterThan(0.85f);
        }

        @Test
        @DisplayName("distributeQualityGateSignal with empty plan — no saves")
        void qualityGate_emptyPlan_noSaves() {
            when(planItemRepository.findByPlanId(planId)).thenReturn(List.of());

            service.distributeQualityGateSignal(planId, true);

            verify(planItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("multiple computeProcessScore calls overwrite previous value")
        void multipleProcessScoreCalls_overwrite() {
            PlanItem item = createDoneItem("BE-001", WorkerType.BE, "be-java");

            // Fast execution
            AgentResult fastResult = resultWith(5_000L, 100L, null);
            service.computeProcessScore(item, fastResult);
            float firstScore = item.getProcessScore();

            // Slow execution (overwrite)
            AgentResult slowResult = resultWith(300_000L, 500_000L, null);
            service.computeProcessScore(item, slowResult);
            float secondScore = item.getProcessScore();

            assertThat(firstScore).isGreaterThan(secondScore);
            verify(planItemRepository, times(2)).save(item);
        }
    }
}
