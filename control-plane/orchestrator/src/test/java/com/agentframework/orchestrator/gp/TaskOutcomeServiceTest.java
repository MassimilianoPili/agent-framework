package com.agentframework.orchestrator.gp;

import com.agentframework.gp.config.GpProperties;
import com.agentframework.gp.engine.GaussianProcessEngine;
import com.agentframework.gp.engine.GpModelCache;
import com.agentframework.gp.model.GpPosterior;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.gp.model.TrainingPoint;
import com.agentframework.orchestrator.reward.WorkerEloStats;
import com.agentframework.orchestrator.reward.WorkerEloStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TaskOutcomeService}.
 * Verifies embedding, prediction (with/without cache), outcome recording, and reward updates.
 */
@ExtendWith(MockitoExtension.class)
class TaskOutcomeServiceTest {

    @Mock private TaskOutcomeRepository outcomeRepository;
    @Mock private WorkerEloStatsRepository eloStatsRepository;
    @Mock private GaussianProcessEngine gpEngine;
    @Mock private EmbeddingModel embeddingModel;

    private TaskOutcomeService service;

    private static final GpProperties PROPS = new GpProperties(
            true,
            new GpProperties.Kernel(1.0, 1.0),
            0.1,
            500,
            0.5,
            new GpProperties.Cache(5, true));

    @BeforeEach
    void setUp() {
        service = new TaskOutcomeService(
                outcomeRepository, eloStatsRepository,
                gpEngine, embeddingModel, PROPS);
    }

    // ── embedTask ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("embedTask")
    class EmbedTask {

        @Test
        @DisplayName("concatenates title and description before embedding")
        void concatenatesTitleAndDescription() {
            float[] expected = {0.1f, 0.2f, 0.3f};
            when(embeddingModel.embed("Build API: Create REST endpoints")).thenReturn(expected);

            float[] result = service.embedTask("Build API", "Create REST endpoints");

            assertThat(result).isEqualTo(expected);
            verify(embeddingModel).embed("Build API: Create REST endpoints");
        }

        @Test
        @DisplayName("handles null description gracefully")
        void handlesNullDescription() {
            float[] expected = {0.4f, 0.5f};
            when(embeddingModel.embed("Fix bug: ")).thenReturn(expected);

            float[] result = service.embedTask("Fix bug", null);

            assertThat(result).isEqualTo(expected);
        }
    }

    // ── predict ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("predict")
    class Predict {

        @Test
        @DisplayName("returns prior when no training data exists")
        void returnsPriorWithNoTrainingData() {
            GpPrediction prior = new GpPrediction(0.5, 1.0);
            when(outcomeRepository.findTrainingDataRaw("BE", "be-java", 500))
                    .thenReturn(List.of());
            when(gpEngine.prior(0.5)).thenReturn(prior);

            GpPrediction result = service.predict(new float[]{0.1f}, "BE", "be-java");

            assertThat(result.mu()).isEqualTo(0.5);
            assertThat(result.sigma2()).isEqualTo(1.0);
            verify(gpEngine, never()).fit(any());
        }

        @Test
        @DisplayName("fits GP and predicts when training data exists")
        void fitsAndPredictsWithTrainingData() {
            // Simulate raw DB rows: [id, planItemId, planId, taskKey, workerType, workerProfile,
            //                        embeddingText, eloAtDispatch, gpMu, gpSigma2, actualReward, createdAt]
            Object[] row = new Object[12];
            row[6] = "[0.1,0.2,0.3]";  // embedding_text
            row[10] = 0.85;             // actual_reward
            when(outcomeRepository.findTrainingDataRaw("BE", "be-java", 500))
                    .thenReturn(List.<Object[]>of(row));

            GpPosterior mockPosterior = mock(GpPosterior.class);
            when(gpEngine.fit(anyList())).thenReturn(mockPosterior);

            GpPrediction expected = new GpPrediction(0.82, 0.05);
            float[] queryEmbedding = {0.15f, 0.25f, 0.35f};
            when(gpEngine.predict(mockPosterior, queryEmbedding)).thenReturn(expected);

            GpPrediction result = service.predict(queryEmbedding, "BE", "be-java");

            assertThat(result.mu()).isEqualTo(0.82);
            assertThat(result.sigma2()).isEqualTo(0.05);
            verify(gpEngine).fit(argThat(points ->
                    points.size() == 1
                    && points.get(0).reward() == 0.85
                    && points.get(0).embedding().length == 3));
        }

        @Test
        @DisplayName("uses cached posterior on second call")
        void usesCachedPosterior() {
            Object[] row = new Object[12];
            row[6] = "[0.5,0.6]";
            row[10] = 0.7;
            when(outcomeRepository.findTrainingDataRaw("BE", "be-java", 500))
                    .thenReturn(List.<Object[]>of(row));

            GpPosterior mockPosterior = mock(GpPosterior.class);
            when(gpEngine.fit(anyList())).thenReturn(mockPosterior);
            when(gpEngine.predict(eq(mockPosterior), any(float[].class)))
                    .thenReturn(new GpPrediction(0.75, 0.03));

            // First call — fits GP and caches
            service.predict(new float[]{0.5f, 0.6f}, "BE", "be-java");

            // Second call — should use cache, not re-fit
            service.predict(new float[]{0.4f, 0.5f}, "BE", "be-java");

            verify(gpEngine, times(1)).fit(anyList());
            verify(gpEngine, times(2)).predict(eq(mockPosterior), any(float[].class));
        }
    }

    // ── recordOutcomeAtDispatch ─────────────────────────────────────────────

    @Nested
    @DisplayName("recordOutcomeAtDispatch")
    class RecordOutcomeAtDispatch {

        @Test
        @DisplayName("persists outcome with ELO snapshot and GP prediction")
        void persistsOutcomeWithEloAndPrediction() {
            UUID planItemId = UUID.randomUUID();
            UUID planId = UUID.randomUUID();
            float[] embedding = {0.1f, 0.2f};
            GpPrediction prediction = new GpPrediction(0.65, 0.12);

            // Default ELO is 1600.0 (immutable after construction)
            WorkerEloStats stats = new WorkerEloStats("be-java");
            when(eloStatsRepository.findById("be-java")).thenReturn(Optional.of(stats));

            service.recordOutcomeAtDispatch(planItemId, planId, "BE-001", "BE", "be-java",
                    embedding, prediction);

            verify(outcomeRepository).insertWithEmbedding(
                    any(UUID.class), eq(planItemId), eq(planId), eq("BE-001"),
                    eq("BE"), eq("be-java"), eq("[0.1,0.2]"), eq(1600.0),
                    eq(0.65), eq(0.12));
        }
    }

    // ── updateReward ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateReward")
    class UpdateReward {

        @Test
        @DisplayName("updates actual_reward and invalidates cache")
        void updatesRewardAndInvalidatesCache() {
            UUID planItemId = UUID.randomUUID();
            when(outcomeRepository.updateActualReward(planItemId, 0.88)).thenReturn(1);

            service.updateReward(planItemId, 0.88, "BE", "be-java");

            verify(outcomeRepository).updateActualReward(planItemId, 0.88);
            // Cache invalidation verified: next predict() call should re-fit
        }

        @Test
        @DisplayName("no cache invalidation when no rows updated")
        void noCacheInvalidationWhenNoRowsUpdated() {
            UUID planItemId = UUID.randomUUID();
            when(outcomeRepository.updateActualReward(planItemId, 0.5)).thenReturn(0);

            service.updateReward(planItemId, 0.5, "BE", "be-java");

            verify(outcomeRepository).updateActualReward(planItemId, 0.5);
        }
    }

    // ── floatArrayToString / parseEmbeddingText ─────────────────────────────

    @Nested
    @DisplayName("embedding serialization")
    class EmbeddingSerialization {

        @Test
        @DisplayName("floatArrayToString produces pgvector format")
        void floatArrayToString_format() {
            float[] arr = {0.1f, 0.2f, 0.3f};
            String result = TaskOutcomeService.floatArrayToString(arr);
            assertThat(result).isEqualTo("[0.1,0.2,0.3]");
        }

        @Test
        @DisplayName("floatArrayToString handles null")
        void floatArrayToString_null() {
            assertThat(TaskOutcomeService.floatArrayToString(null)).isNull();
        }

        @Test
        @DisplayName("parseEmbeddingText round-trips correctly")
        void parseEmbeddingText_roundTrip() {
            float[] original = {0.5f, -0.3f, 1.0f};
            String text = TaskOutcomeService.floatArrayToString(original);
            float[] parsed = TaskOutcomeService.parseEmbeddingText(text);

            assertThat(parsed).hasSize(3);
            assertThat(parsed[0]).isCloseTo(0.5f, within(1e-6f));
            assertThat(parsed[1]).isCloseTo(-0.3f, within(1e-6f));
            assertThat(parsed[2]).isCloseTo(1.0f, within(1e-6f));
        }

        @Test
        @DisplayName("parseEmbeddingText returns null for invalid input")
        void parseEmbeddingText_invalid() {
            assertThat(TaskOutcomeService.parseEmbeddingText(null)).isNull();
            assertThat(TaskOutcomeService.parseEmbeddingText("")).isNull();
            assertThat(TaskOutcomeService.parseEmbeddingText("[]")).isNull();
        }
    }
}
