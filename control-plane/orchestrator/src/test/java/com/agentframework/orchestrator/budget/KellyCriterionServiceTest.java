package com.agentframework.orchestrator.budget;

import com.agentframework.orchestrator.budget.KellyCriterion.KellyRecommendation;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KellyCriterionService}.
 *
 * <p>Verifies Kelly computation from task outcomes, insufficient data handling,
 * and budget adjustment.</p>
 */
@ExtendWith(MockitoExtension.class)
class KellyCriterionServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private KellyCriterionService service;

    @BeforeEach
    void setUp() {
        service = new KellyCriterionService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "kellyFraction", 0.5);
        ReflectionTestUtils.setField(service, "maxFraction", 0.5);
    }

    @Test
    @DisplayName("computeForProfile with sufficient data returns recommendation")
    void computeForProfile_withSufficientData_returnsRecommendation() {
        // 20 outcomes: 14 wins (reward ~0.8) + 6 losses (reward ~0.3)
        // winProb = 0.7, winPayoff ≈ 0.3, lossPayoff ≈ 0.2
        List<Object[]> data = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 14; i++) {
            data.add(makeRow(0.75 + i * 0.005, now));  // wins: 0.75-0.82
        }
        for (int i = 0; i < 6; i++) {
            data.add(makeRow(0.25 + i * 0.01, now));   // losses: 0.25-0.30
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 500))
                .thenReturn(data);

        KellyRecommendation rec = service.computeForProfile("BE", "be-java");

        assertThat(rec.shouldBet()).isTrue();
        assertThat(rec.fullKellyFraction()).isGreaterThan(0.0);
        assertThat(rec.adjustedFraction()).isCloseTo(rec.fullKellyFraction() * 0.5, within(1e-10));
        assertThat(rec.clampedFraction()).isLessThanOrEqualTo(0.5);
    }

    @Test
    @DisplayName("computeForProfile with no data returns zero fraction")
    void computeForProfile_noData_returnsZeroFraction() {
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-go", 500))
                .thenReturn(List.of());

        KellyRecommendation rec = service.computeForProfile("BE", "be-go");

        assertThat(rec.shouldBet()).isFalse();
        assertThat(rec.fullKellyFraction()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("adjustBudget with half-Kelly halves effective budget")
    void adjustBudget_halvesWithHalfKelly() {
        // All wins at 0.9 → very strong edge → Kelly fraction near max
        List<Object[]> data = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 15; i++) {
            data.add(makeRow(0.9, now));  // strong wins
        }
        for (int i = 0; i < 5; i++) {
            data.add(makeRow(0.2, now));  // some losses
        }
        when(taskOutcomeRepository.findTrainingDataRaw("BE", "be-java", 500))
                .thenReturn(data);

        long adjusted = service.adjustBudget(1000L, "BE", "be-java");

        // With strong edge, Kelly recommends a fraction; adjusted budget should be < base
        assertThat(adjusted).isGreaterThan(0L);
        assertThat(adjusted).isLessThanOrEqualTo(500L); // clamped at maxFraction=0.5
    }

    /**
     * Creates a mock row matching findTrainingDataRaw format.
     * Index 10 = actual_reward (the only field used by KellyCriterionService).
     */
    private Object[] makeRow(double reward, Instant now) {
        Object[] row = new Object[12];
        row[0] = UUID.randomUUID();       // id
        row[1] = UUID.randomUUID();       // plan_item_id
        row[2] = UUID.randomUUID();       // plan_id
        row[3] = "TASK-1";               // task_key
        row[4] = "BE";                   // worker_type
        row[5] = "be-java";             // worker_profile
        row[6] = "[0.1,0.2,0.3]";       // embedding_text
        row[7] = 1500.0;                // elo_at_dispatch
        row[8] = 0.7;                   // gp_mu
        row[9] = 0.01;                  // gp_sigma2
        row[10] = reward;               // actual_reward
        row[11] = Timestamp.from(now);  // created_at
        return row;
    }
}
