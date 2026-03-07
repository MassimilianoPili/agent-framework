package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.CalibrationAudit.CalibrationReport;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CalibrationAuditService}.
 *
 * <p>Verifies audit execution, Dutch Book event publishing, and insufficient data handling.</p>
 */
@ExtendWith(MockitoExtension.class)
class CalibrationAuditServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CalibrationAuditService service;

    @BeforeEach
    void setUp() {
        service = new CalibrationAuditService(taskOutcomeRepository, eventPublisher);
        ReflectionTestUtils.setField(service, "numBins", 10);
        ReflectionTestUtils.setField(service, "dutchBookThreshold", 0.15);
    }

    @Test
    @DisplayName("auditAll with sufficient well-calibrated data returns report")
    void auditAll_withSufficientData_returnsReport() {
        // 30 predictions: roughly calibrated (predicted ≈ actual rate)
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double gpMu = 0.3 + (i % 5) * 0.1; // 0.3 to 0.7
            double reward = (i % 3 == 0) ? 0.3 : 0.8; // ~67% success
            data.add(new Object[]{gpMu, reward, "BE"});
        }
        when(taskOutcomeRepository.findCalibrationData(1000)).thenReturn(data);

        CalibrationReport report = service.auditAll();

        assertThat(report).isNotNull();
        assertThat(report.totalPredictions()).isEqualTo(30);
        assertThat(report.ece()).isGreaterThanOrEqualTo(0.0);
        assertThat(report.brierScore()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("auditAll publishes CALIBRATION_DRIFT when Dutch Book detected")
    void auditAll_dutchBookDetected_publishesEvent() {
        // All predictions at 0.9 but only 20% success → badly miscalibrated
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            double reward = (i < 5) ? 0.8 : 0.2; // only 5/25 = 20% success
            data.add(new Object[]{0.9, reward, "BE"});
        }
        when(taskOutcomeRepository.findCalibrationData(1000)).thenReturn(data);

        CalibrationReport report = service.auditAll();

        assertThat(report).isNotNull();
        assertThat(report.dutchBookVulnerable()).isTrue();

        // Verify CALIBRATION_DRIFT event published
        ArgumentCaptor<SpringPlanEvent> captor = ArgumentCaptor.forClass(SpringPlanEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(SpringPlanEvent.CALIBRATION_DRIFT);
    }

    @Test
    @DisplayName("auditAll with insufficient data returns null")
    void auditAll_insufficientData_returnsEmptyReport() {
        // Only 5 predictions (below MIN_PREDICTIONS=20)
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            data.add(new Object[]{0.5, 0.6, "BE"});
        }
        when(taskOutcomeRepository.findCalibrationData(1000)).thenReturn(data);

        CalibrationReport report = service.auditAll();

        assertThat(report).isNull();
        verifyNoInteractions(eventPublisher);
    }
}
