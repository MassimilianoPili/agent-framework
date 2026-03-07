package com.agentframework.orchestrator.gp;

import com.agentframework.orchestrator.budget.TokenBudgetService;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BayesianSuccessPredictorService} — service bridge for Bayesian prediction.
 */
@ExtendWith(MockitoExtension.class)
class BayesianSuccessPredictorServiceTest {

    @Mock
    private TaskOutcomeRepository taskOutcomeRepository;

    @Mock
    private TokenBudgetService tokenBudgetService;

    private BayesianSuccessPredictorService service;

    @BeforeEach
    void setUp() {
        // Return empty list → prior predictor
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(List.of());
        service = new BayesianSuccessPredictorService(taskOutcomeRepository, tokenBudgetService);
        service.init();
    }

    @Test
    void predictForItem_noTrainingData_returnsPrior() {
        PlanItem item = new PlanItem(UUID.randomUUID(), 0, "BE-001", "Test task",
                "Description", WorkerType.BE, "be-java", List.of());

        SuccessPrediction prediction = service.predictForItem(item, null, null, UUID.randomUUID());

        // Prior predictor always returns P ≈ 0.5
        assertThat(prediction.probability()).isCloseTo(0.5, within(0.01));
        assertThat(prediction.shouldDispatch()).isTrue(); // 0.5 >= 0.3
    }

    @Test
    void predictForItem_buildsCorrectFeatures() {
        PlanItem item = new PlanItem(UUID.randomUUID(), 0, "FE-001", "Frontend task",
                "Build UI", WorkerType.FE, "fe-react", List.of());

        SuccessPrediction prediction = service.predictForItem(item, null, null, UUID.randomUUID());

        // Feature vector should have FEATURE_DIM dimensions
        assertThat(prediction.featureVector()).hasSize(BayesianSuccessPredictor.FEATURE_DIM);
        // With no GP prediction, defaults should be used
        assertThat(prediction.featureVector()[1024]).isCloseTo(0.5, within(1e-9)); // default gp_mu
        assertThat(prediction.featureVector()[1025]).isCloseTo(1.0, within(1e-9)); // default sigma2
    }
}
