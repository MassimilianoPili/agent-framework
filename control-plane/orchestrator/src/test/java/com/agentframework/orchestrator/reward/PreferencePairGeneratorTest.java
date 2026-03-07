package com.agentframework.orchestrator.reward;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PreferencePairGenerator}.
 *
 * <p>Covers all three DPO strategies: same_plan_cross_profile,
 * retry_comparison, and gp_residual_surprise.</p>
 */
@ExtendWith(MockitoExtension.class)
class PreferencePairGeneratorTest {

    @Mock PlanItemRepository planItemRepository;
    @Mock PreferencePairRepository pairRepository;
    @Mock TaskOutcomeService taskOutcomeService;

    private PreferencePairGenerator generator;
    private PreferencePairGenerator generatorNoGp;

    private static final UUID PLAN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        generator = new PreferencePairGenerator(
                planItemRepository, pairRepository, Optional.of(taskOutcomeService));
        generatorNoGp = new PreferencePairGenerator(
                planItemRepository, pairRepository, Optional.empty());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private PlanItem buildItem(String taskKey, WorkerType type, String profile,
                               float reward, int retries, String result) {
        PlanItem item = new PlanItem(
                UUID.randomUUID(), 1, taskKey, "Title " + taskKey,
                "Description for " + taskKey, type, profile, List.of(), List.of());
        item.forceStatus(ItemStatus.DONE);
        item.setAggregatedReward(reward);
        item.setResult(result);
        for (int i = 0; i < retries; i++) {
            item.incrementContextRetryCount();
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<PreferencePair> capturePersistedPairs() {
        ArgumentCaptor<List<PreferencePair>> captor = ArgumentCaptor.forClass(List.class);
        verify(pairRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    // ── Strategy 1: cross-profile ──────────────────────────────────────────────

    @Nested
    @DisplayName("Strategy: same_plan_cross_profile")
    class CrossProfile {

        @Test
        @DisplayName("Two profiles with delta >= 0.3 generates pair")
        void twoProfiles_generatesPair() {
            PlanItem a = buildItem("T1", WorkerType.BE, "be-java", 0.9f, 0, "{\"ok\":true}");
            PlanItem b = buildItem("T2", WorkerType.BE, "be-go", 0.4f, 0, "{\"ok\":false}");
            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(a, b));

            int count = generatorNoGp.generateForPlan(PLAN_ID);

            assertThat(count).isEqualTo(1);
            List<PreferencePair> pairs = capturePersistedPairs();
            assertThat(pairs.get(0).getGenerationSource()).isEqualTo("same_plan_cross_profile");
            assertThat(pairs.get(0).getChosenReward()).isEqualTo(0.9f);
            assertThat(pairs.get(0).getRejectedReward()).isEqualTo(0.4f);
            assertThat(pairs.get(0).getGpResidual()).isNull();
        }

        @Test
        @DisplayName("Same profile — no pair generated")
        void sameProfile_noPair() {
            PlanItem a = buildItem("T1", WorkerType.BE, "be-java", 0.9f, 0, "{\"ok\":true}");
            PlanItem b = buildItem("T2", WorkerType.BE, "be-java", 0.4f, 0, "{\"ok\":false}");
            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(a, b));

            int count = generatorNoGp.generateForPlan(PLAN_ID);

            assertThat(count).isEqualTo(0);
            verify(pairRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Delta below 0.3 — skipped")
        void deltaBelow03_skipped() {
            PlanItem a = buildItem("T1", WorkerType.BE, "be-java", 0.5f, 0, "{\"ok\":true}");
            PlanItem b = buildItem("T2", WorkerType.BE, "be-go", 0.4f, 0, "{\"ok\":false}");
            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(a, b));

            int count = generatorNoGp.generateForPlan(PLAN_ID);

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("Already generated pair — skipped")
        void alreadyGenerated_skipped() {
            PlanItem a = buildItem("T1", WorkerType.BE, "be-java", 0.9f, 0, "{\"ok\":true}");
            PlanItem b = buildItem("T2", WorkerType.BE, "be-go", 0.4f, 0, "{\"ok\":false}");
            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(a, b));
            when(pairRepository.existsByTaskKeyAndGenerationSource(anyString(), eq("same_plan_cross_profile")))
                    .thenReturn(true);

            int count = generatorNoGp.generateForPlan(PLAN_ID);

            assertThat(count).isEqualTo(0);
        }
    }

    // ── Strategy 2: retry_comparison ───────────────────────────────────────────

    @Nested
    @DisplayName("Strategy: retry_comparison")
    class Retry {

        @Test
        @DisplayName("Item with retries generates pair")
        void itemWithRetries_generatesPair() {
            PlanItem item = buildItem("T1", WorkerType.BE, "be-java", 0.8f, 2, "{\"ok\":true}");
            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(item));

            int count = generatorNoGp.generateForPlan(PLAN_ID);

            assertThat(count).isEqualTo(1);
            List<PreferencePair> pairs = capturePersistedPairs();
            assertThat(pairs.get(0).getGenerationSource()).isEqualTo("retry_comparison");
            assertThat(pairs.get(0).getChosenReward()).isEqualTo(0.8f);
            assertThat(pairs.get(0).getRejectedReward()).isEqualTo(-0.5f);
        }

        @Test
        @DisplayName("Item without retries — no pair")
        void noRetries_noPair() {
            PlanItem item = buildItem("T1", WorkerType.BE, "be-java", 0.8f, 0, "{\"ok\":true}");
            // Only one profile → no cross-profile pair either
            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(item));

            int count = generatorNoGp.generateForPlan(PLAN_ID);

            assertThat(count).isEqualTo(0);
        }
    }

    // ── Strategy 3: gp_residual_surprise ───────────────────────────────────────

    @Nested
    @DisplayName("Strategy: gp_residual_surprise")
    class GpResidual {

        @Test
        @DisplayName("High residual generates pair with gpResidual populated")
        void highResidual_generatesPair() {
            PlanItem a = buildItem("T1", WorkerType.BE, "be-java", 0.9f, 0, "{\"ok\":true}");
            PlanItem b = buildItem("T2", WorkerType.BE, "be-go", 0.4f, 0, "{\"ok\":false}");
            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(a, b));

            // GP predicts 0.3 for be-java → residual = |0.9 - 0.3| = 0.6 (high surprise)
            // GP predicts 0.35 for be-go → residual = |0.4 - 0.35| = 0.05
            when(taskOutcomeService.embedTask(anyString(), anyString())).thenReturn(new float[1024]);
            when(taskOutcomeService.predict(any(float[].class), eq("BE"), eq("be-java")))
                    .thenReturn(new GpPrediction(0.3, 0.1));
            when(taskOutcomeService.predict(any(float[].class), eq("BE"), eq("be-go")))
                    .thenReturn(new GpPrediction(0.35, 0.1));

            int count = generator.generateForPlan(PLAN_ID);

            // Expect: 1 cross-profile + 1 gp-residual = 2
            assertThat(count).isEqualTo(2);
            List<PreferencePair> pairs = capturePersistedPairs();

            PreferencePair gpPair = pairs.stream()
                    .filter(p -> "gp_residual_surprise".equals(p.getGenerationSource()))
                    .findFirst().orElseThrow();
            assertThat(gpPair.getGpResidual()).isNotNull();
            assertThat(gpPair.getGpResidual()).isGreaterThanOrEqualTo(0.15f);
            assertThat(gpPair.getChosenReward()).isEqualTo(0.9f);
        }

        @Test
        @DisplayName("Low residual — gp_residual_surprise pair skipped")
        void lowResidual_skipped() {
            PlanItem a = buildItem("T1", WorkerType.BE, "be-java", 0.9f, 0, "{\"ok\":true}");
            PlanItem b = buildItem("T2", WorkerType.BE, "be-go", 0.4f, 0, "{\"ok\":false}");
            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(a, b));

            // GP predicts accurately: residual < 0.15 for both
            when(taskOutcomeService.embedTask(anyString(), anyString())).thenReturn(new float[1024]);
            when(taskOutcomeService.predict(any(float[].class), eq("BE"), eq("be-java")))
                    .thenReturn(new GpPrediction(0.88, 0.01));
            when(taskOutcomeService.predict(any(float[].class), eq("BE"), eq("be-go")))
                    .thenReturn(new GpPrediction(0.39, 0.01));

            int count = generator.generateForPlan(PLAN_ID);

            // Only cross-profile (delta 0.5 >= 0.3), no gp-residual (max residual ~0.02)
            assertThat(count).isEqualTo(1);
            List<PreferencePair> pairs = capturePersistedPairs();
            assertThat(pairs).allMatch(p -> "same_plan_cross_profile".equals(p.getGenerationSource()));
        }

        @Test
        @DisplayName("No GP service — strategy skipped entirely")
        void noGpService_strategySkipped() {
            PlanItem a = buildItem("T1", WorkerType.BE, "be-java", 0.9f, 0, "{\"ok\":true}");
            PlanItem b = buildItem("T2", WorkerType.BE, "be-go", 0.4f, 0, "{\"ok\":false}");
            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(a, b));

            int count = generatorNoGp.generateForPlan(PLAN_ID);

            // Only cross-profile pair, no GP residual
            assertThat(count).isEqualTo(1);
            List<PreferencePair> pairs = capturePersistedPairs();
            assertThat(pairs).noneMatch(p -> "gp_residual_surprise".equals(p.getGenerationSource()));
            verify(taskOutcomeService, never()).embedTask(anyString(), anyString());
        }

        @Test
        @DisplayName("gpResidual field is populated with max(residualA, residualB)")
        void gpResidualFieldPopulated() {
            PlanItem a = buildItem("T1", WorkerType.BE, "be-java", 0.9f, 0, "{\"ok\":true}");
            PlanItem b = buildItem("T2", WorkerType.BE, "be-go", 0.2f, 0, "{\"ok\":false}");
            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(a, b));

            // be-java: reward 0.9, predicted 0.5 → residual 0.4
            // be-go: reward 0.2, predicted 0.5 → residual 0.3
            // max = 0.4
            when(taskOutcomeService.embedTask(anyString(), anyString())).thenReturn(new float[1024]);
            when(taskOutcomeService.predict(any(float[].class), eq("BE"), eq("be-java")))
                    .thenReturn(new GpPrediction(0.5, 0.1));
            when(taskOutcomeService.predict(any(float[].class), eq("BE"), eq("be-go")))
                    .thenReturn(new GpPrediction(0.5, 0.1));

            generator.generateForPlan(PLAN_ID);

            List<PreferencePair> pairs = capturePersistedPairs();
            PreferencePair gpPair = pairs.stream()
                    .filter(p -> "gp_residual_surprise".equals(p.getGenerationSource()))
                    .findFirst().orElseThrow();
            // max(|0.9-0.5|, |0.2-0.5|) = max(0.4, 0.3) = 0.4
            assertThat(gpPair.getGpResidual()).isCloseTo(0.4f, org.assertj.core.data.Offset.offset(0.01f));
        }
    }

    // ── Integration ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Integration")
    class Integration {

        @Test
        @DisplayName("All three strategies produce pairs in one call")
        void allThreeStrategies_combined() {
            // Cross-profile items (BE: two profiles, delta 0.5)
            PlanItem a = buildItem("T1", WorkerType.BE, "be-java", 0.9f, 0, "{\"ok\":true}");
            PlanItem b = buildItem("T2", WorkerType.BE, "be-go", 0.4f, 0, "{\"ok\":false}");
            // Retry item (FE: retries > 0, only one profile → no cross-profile)
            PlanItem c = buildItem("T3", WorkerType.FE, "fe-react", 0.7f, 3, "{\"ok\":true}");

            when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(a, b, c));

            // GP predicts poorly for be-java → high residual
            when(taskOutcomeService.embedTask(anyString(), anyString())).thenReturn(new float[1024]);
            when(taskOutcomeService.predict(any(float[].class), eq("BE"), eq("be-java")))
                    .thenReturn(new GpPrediction(0.3, 0.1));
            when(taskOutcomeService.predict(any(float[].class), eq("BE"), eq("be-go")))
                    .thenReturn(new GpPrediction(0.35, 0.1));

            int count = generator.generateForPlan(PLAN_ID);

            // 1 cross-profile + 1 retry + 1 gp-residual = 3
            assertThat(count).isEqualTo(3);
            List<PreferencePair> pairs = capturePersistedPairs();
            assertThat(pairs.stream().map(PreferencePair::getGenerationSource))
                    .containsExactlyInAnyOrder(
                            "same_plan_cross_profile",
                            "retry_comparison",
                            "gp_residual_surprise");
        }
    }
}
