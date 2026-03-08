package com.agentframework.orchestrator.gp;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.domain.PlanOutcome;
import com.agentframework.orchestrator.repository.PlanOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PlanDecompositionPredictor}.
 *
 * <p>Verifies cold-start behaviour, GP prediction with sufficient data,
 * and outcome recording to the repository.</p>
 */
@ExtendWith(MockitoExtension.class)
class PlanDecompositionPredictorTest {

    @Mock private PlanOutcomeRepository planOutcomeRepository;
    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private PlanDecompositionPredictor predictor;

    @BeforeEach
    void setUp() {
        predictor = new PlanDecompositionPredictor(planOutcomeRepository, taskOutcomeRepository);
        ReflectionTestUtils.setField(predictor, "maxTrainingPoints", 200);
        ReflectionTestUtils.setField(predictor, "noiseVariance", 0.1);
        ReflectionTestUtils.setField(predictor, "lengthScale", 2.0);
    }

    private PlanOutcome makeOutcome(int nTasks, double reward) {
        return new PlanOutcome(UUID.randomUUID(), nTasks, true, true, 2, 1, reward);
    }

    @Test
    @DisplayName("predict returns empty on cold start (fewer than MIN_TRAINING_POINTS outcomes)")
    void predict_coldStart_returnsEmpty() {
        // Only 4 training points — below MIN_TRAINING_POINTS (5)
        when(planOutcomeRepository.findTrainingData(any(Pageable.class)))
                .thenReturn(List.of(
                        makeOutcome(3, 0.7), makeOutcome(4, 0.8),
                        makeOutcome(5, 0.6), makeOutcome(6, 0.9)));

        Optional<GpPrediction> result = predictor.predict(5, true, true, 2, 1);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("predict returns GpPrediction with sufficient training data")
    void predict_withSufficientData_returnsPrediction() {
        // 7 training points — above MIN_TRAINING_POINTS (5)
        List<PlanOutcome> trainingData = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            trainingData.add(makeOutcome(3 + i, 0.5 + i * 0.05));
        }
        when(planOutcomeRepository.findTrainingData(any(Pageable.class))).thenReturn(trainingData);

        Optional<GpPrediction> result = predictor.predict(5, true, true, 2, 1);

        assertThat(result).isPresent();
        assertThat(result.get().mu()).isFinite();
        assertThat(result.get().sigma2()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("recordOutcome saves PlanOutcome with mean reward from task outcomes")
    void recordOutcome_savesToRepository() {
        UUID planId = UUID.randomUUID();
        // Simulate 3 task outcome rows: [profile, actual_reward, worker_type, task_key]
        List<Object[]> rows = List.of(
                new Object[]{"be-java", 0.8, "BE", "T1"},
                new Object[]{"be-java", 0.7, "BE", "T2"},
                new Object[]{"fe-react", 0.9, "FE", "T3"});
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(rows);
        when(planOutcomeRepository.save(any(PlanOutcome.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        predictor.recordOutcome(planId, 4, true, false, 2, 1);

        ArgumentCaptor<PlanOutcome> captor = ArgumentCaptor.forClass(PlanOutcome.class);
        verify(planOutcomeRepository).save(captor.capture());
        PlanOutcome saved = captor.getValue();
        assertThat(saved.getPlanId()).isEqualTo(planId);
        assertThat(saved.getNTasks()).isEqualTo(4);
        assertThat(saved.isHasContextTask()).isTrue();
        assertThat(saved.isHasReviewTask()).isFalse();
        assertThat(saved.getNBeTasks()).isEqualTo(2);
        assertThat(saved.getNFeTasks()).isEqualTo(1);
        // Mean reward = (0.8 + 0.7 + 0.9) / 3 ≈ 0.8
        assertThat(saved.getActualReward()).isCloseTo(0.8, within(1e-9));
    }

    @Test
    @DisplayName("recordOutcome skips save when no task outcomes available")
    void recordOutcome_noTaskOutcomes_skipsSave() {
        UUID planId = UUID.randomUUID();
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(List.of());

        predictor.recordOutcome(planId, 3, false, false, 1, 0);

        verify(planOutcomeRepository, never()).save(any());
    }
}
