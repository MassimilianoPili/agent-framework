package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ProspectTheory.ProspectEvaluation;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProspectTheoryService}.
 *
 * <p>Verifies profile evaluation with loss aversion, insufficient data handling,
 * and adjustment factor computation.</p>
 */
@ExtendWith(MockitoExtension.class)
class ProspectTheoryServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private ProspectTheoryService service;

    @BeforeEach
    void setUp() {
        service = new ProspectTheoryService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "lambda", 2.25);
    }

    @Test
    @DisplayName("evaluate with sufficient data returns valid evaluation")
    void evaluate_withSufficientData_returnsEvaluation() {
        Instant now = Instant.now();
        List<Object[]> timeseries = new ArrayList<>();
        // 20 outcomes: rewards evenly distributed around 0.7 (above reference)
        for (int i = 0; i < 20; i++) {
            timeseries.add(new Object[]{
                    0.6 + i * 0.01,
                    Timestamp.from(now.minus(i, ChronoUnit.HOURS))
            });
        }
        when(taskOutcomeRepository.findRewardTimeseriesByProfile("be-java"))
                .thenReturn(timeseries);

        ProspectEvaluation eval = service.evaluate("BE", "be-java");

        assertThat(eval.profile()).isEqualTo("be-java");
        // Raw EV is around 0.695, shifted by 0.5 → ~0.195
        assertThat(eval.rawExpectedValue()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("evaluate with insufficient data returns neutral")
    void evaluate_withInsufficientData_returnsNeutral() {
        List<Object[]> single = new ArrayList<>();
        single.add(new Object[]{0.7, Timestamp.from(Instant.now())});
        when(taskOutcomeRepository.findRewardTimeseriesByProfile("be-go"))
                .thenReturn(single);

        ProspectEvaluation eval = service.evaluate("BE", "be-go");

        assertThat(eval.prospectValue()).isEqualTo(0.0);
        assertThat(eval.rawExpectedValue()).isEqualTo(0.0);
        assertThat(eval.lossAversionPenalty()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("adjustmentFactor for high-variance profile returns negative")
    void adjustmentFactor_highVarianceProfile_returnsNegative() {
        Instant now = Instant.now();
        List<Object[]> timeseries = new ArrayList<>();
        // 20 outcomes: alternating between very low (0.1) and very high (0.9)
        // High variance → loss aversion penalizes heavily
        for (int i = 0; i < 20; i++) {
            double reward = (i % 2 == 0) ? 0.1 : 0.9;
            timeseries.add(new Object[]{
                    reward,
                    Timestamp.from(now.minus(i, ChronoUnit.HOURS))
            });
        }
        when(taskOutcomeRepository.findRewardTimeseriesByProfile("be-rust"))
                .thenReturn(timeseries);

        double adjustment = service.adjustmentFactor("be-rust");

        // Loss aversion on 50% losses makes PV < rawEV → negative adjustment
        assertThat(adjustment).isLessThan(0.0);
        assertThat(adjustment).isGreaterThanOrEqualTo(-0.5);
    }
}
