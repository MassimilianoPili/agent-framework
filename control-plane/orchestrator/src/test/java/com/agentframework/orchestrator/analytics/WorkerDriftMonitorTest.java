package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkerDriftMonitor}.
 *
 * <p>Verifies drift detection: insufficient data handling,
 * no-drift scenario, drift event publishing, and penalty calculation.</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkerDriftMonitorTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private WorkerDriftMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new WorkerDriftMonitor(taskOutcomeRepository, eventPublisher);
        ReflectionTestUtils.setField(monitor, "threshold", 0.15);
    }

    @Test
    @DisplayName("checkAllProfiles skips profiles with insufficient data")
    void checkAllProfiles_insufficientData_skipsProfile() {
        when(taskOutcomeRepository.findDistinctProfiles()).thenReturn(List.of("be-java"));

        // Only 5 recent outcomes (< MIN_SAMPLES=10)
        Instant now = Instant.now();
        List<Object[]> timeseries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            timeseries.add(new Object[]{
                    0.7,
                    Timestamp.from(now.minus(i, ChronoUnit.DAYS))
            });
        }
        when(taskOutcomeRepository.findRewardTimeseriesByProfile("be-java"))
                .thenReturn(timeseries);

        List<DriftResult> results = monitor.checkAllProfiles();

        assertThat(results).isEmpty();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("checkAllProfiles returns false when distributions are similar")
    void checkAllProfiles_noDrift_driftDetectedFalse() {
        when(taskOutcomeRepository.findDistinctProfiles()).thenReturn(List.of("be-java"));

        // Generate 30 outcomes: 15 historical (8-22 days ago), 15 recent (within 6 days)
        // Same distribution → no drift
        Instant now = Instant.now();
        List<Object[]> timeseries = new ArrayList<>();

        // Historical: rewards around 0.7, spread across days 8-22
        for (int i = 0; i < 15; i++) {
            timeseries.add(new Object[]{
                    0.65 + i * 0.01,
                    Timestamp.from(now.minus(8 + i, ChronoUnit.DAYS))
            });
        }
        // Recent: same distribution around 0.7, spread across last 6 days (in hours)
        for (int i = 0; i < 15; i++) {
            timeseries.add(new Object[]{
                    0.65 + i * 0.01,
                    Timestamp.from(now.minus(i * 8, ChronoUnit.HOURS))
            });
        }

        when(taskOutcomeRepository.findRewardTimeseriesByProfile("be-java"))
                .thenReturn(timeseries);

        List<DriftResult> results = monitor.checkAllProfiles();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).driftDetected()).isFalse();
        assertThat(results.get(0).w1Distance()).isLessThan(0.15);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("checkAllProfiles publishes event when significant drift detected")
    void checkAllProfiles_significantDrift_publishesEvent() {
        when(taskOutcomeRepository.findDistinctProfiles()).thenReturn(List.of("be-go"));

        Instant now = Instant.now();
        List<Object[]> timeseries = new ArrayList<>();

        // Historical: rewards around 0.3 (low), days 8-22 ago
        for (int i = 0; i < 15; i++) {
            timeseries.add(new Object[]{
                    0.25 + i * 0.01,
                    Timestamp.from(now.minus(8 + i, ChronoUnit.DAYS))
            });
        }
        // Recent: rewards around 0.8 (high) → big shift, within last 6 days
        for (int i = 0; i < 15; i++) {
            timeseries.add(new Object[]{
                    0.75 + i * 0.01,
                    Timestamp.from(now.minus(i * 8, ChronoUnit.HOURS))
            });
        }

        when(taskOutcomeRepository.findRewardTimeseriesByProfile("be-go"))
                .thenReturn(timeseries);

        List<DriftResult> results = monitor.checkAllProfiles();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).driftDetected()).isTrue();
        assertThat(results.get(0).w1Distance()).isGreaterThan(0.15);

        // Verify WORKER_DRIFT_DETECTED event published
        ArgumentCaptor<SpringPlanEvent> captor = ArgumentCaptor.forClass(SpringPlanEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(SpringPlanEvent.WORKER_DRIFT_DETECTED);
    }

    @Test
    @DisplayName("penaltyFor returns positive value for drifting profile")
    void penaltyFor_driftingProfile_returnsPositive() {
        when(taskOutcomeRepository.findDistinctProfiles()).thenReturn(List.of("be-rust"));

        Instant now = Instant.now();
        List<Object[]> timeseries = new ArrayList<>();

        // Historical: rewards near 0.2, days 10-21 ago
        for (int i = 0; i < 12; i++) {
            timeseries.add(new Object[]{
                    0.15 + i * 0.01,
                    Timestamp.from(now.minus(10 + i, ChronoUnit.DAYS))
            });
        }
        // Recent: rewards near 0.9 → large drift, within last 5 days (in hours)
        for (int i = 0; i < 12; i++) {
            timeseries.add(new Object[]{
                    0.85 + i * 0.01,
                    Timestamp.from(now.minus(i * 8, ChronoUnit.HOURS))
            });
        }

        when(taskOutcomeRepository.findRewardTimeseriesByProfile("be-rust"))
                .thenReturn(timeseries);

        monitor.checkAllProfiles();

        double penalty = monitor.penaltyFor("be-rust");
        assertThat(penalty).isGreaterThan(0.0);
        assertThat(penalty).isLessThanOrEqualTo(0.5);

        // Non-drifting profile should return 0
        assertThat(monitor.penaltyFor("unknown-profile")).isEqualTo(0.0);
    }
}
