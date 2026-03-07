package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.orchestration.OptimalStopping.StoppingDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OptimalStoppingService}.
 *
 * <p>Verifies stopping decisions with sufficient history, no history,
 * and threshold computation.</p>
 */
@ExtendWith(MockitoExtension.class)
class OptimalStoppingServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private OptimalStoppingService service;

    @BeforeEach
    void setUp() {
        service = new OptimalStoppingService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "observationFraction", 0.3679);
    }

    @Test
    @DisplayName("evaluateForWorkerType with sufficient history returns decision")
    void evaluateForWorkerType_sufficientHistory_returnsDecision() {
        // 20 historical rewards for BE, interleaved with other types
        List<Object[]> allRewards = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            allRewards.add(new Object[]{"BE", 0.3 + i * 0.03});
        }
        // Add some FE rewards (should be filtered out)
        for (int i = 0; i < 5; i++) {
            allRewards.add(new Object[]{"FE", 0.5 + i * 0.05});
        }
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(allRewards);

        StoppingDecision decision = service.evaluateForWorkerType("BE", 0.8);

        assertThat(decision.totalCandidates()).isEqualTo(20);
        assertThat(decision.observationSize()).isGreaterThan(0);
        assertThat(decision.threshold()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("evaluateForWorkerType with no history accepts any candidate")
    void evaluateForWorkerType_noHistory_acceptsAny() {
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(List.of());

        StoppingDecision decision = service.evaluateForWorkerType("BE", 0.1);

        assertThat(decision.shouldAccept()).isTrue();
        assertThat(decision.threshold()).isEqualTo(0.0);
        assertThat(decision.totalCandidates()).isEqualTo(0);
    }

    @Test
    @DisplayName("currentThreshold computes from historical rewards")
    void currentThreshold_computesFromHistoricalRewards() {
        List<Object[]> allRewards = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            allRewards.add(new Object[]{"BE", 0.1 * (i + 1)});
        }
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(allRewards);

        double threshold = service.currentThreshold("BE");

        // Observation phase ≈ first 3 rewards (1/e of 10), max of {0.1, 0.2, 0.3} = 0.3
        assertThat(threshold).isGreaterThan(0.0);
        assertThat(threshold).isLessThanOrEqualTo(1.0);
    }
}
