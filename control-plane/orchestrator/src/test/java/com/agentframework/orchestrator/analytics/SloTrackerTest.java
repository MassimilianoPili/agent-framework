package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.SliDefinitionService.SliReport;
import com.agentframework.orchestrator.analytics.SliDefinitionService.SliValue;
import com.agentframework.orchestrator.analytics.SloTracker.SloCheck;
import com.agentframework.orchestrator.analytics.SloTracker.SloComplianceReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SloTracker}.
 */
@ExtendWith(MockitoExtension.class)
class SloTrackerTest {

    @Mock private SliDefinitionService sliDefinitionService;

    private SloTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SloTracker(sliDefinitionService);
        ReflectionTestUtils.setField(tracker, "availabilityTarget", 0.95);
        ReflectionTestUtils.setField(tracker, "latencyP99Target", 300.0);
        ReflectionTestUtils.setField(tracker, "qualityTarget", 0.5);
    }

    @Test
    @DisplayName("all SLOs met when SLIs exceed targets")
    void allSlosMet() {
        when(sliDefinitionService.computeSlis("BE")).thenReturn(sliReport(0.99, 100.0, 0.8));

        SloComplianceReport report = tracker.checkCompliance("BE");

        assertThat(report.allMet()).isTrue();
        assertThat(report.checks()).hasSize(3);
        assertThat(report.checks()).allMatch(SloCheck::met);
    }

    @Test
    @DisplayName("availability SLO violation detected")
    void availabilityViolation() {
        when(sliDefinitionService.computeSlis("BE")).thenReturn(sliReport(0.80, 100.0, 0.8));

        SloComplianceReport report = tracker.checkCompliance("BE");

        assertThat(report.allMet()).isFalse();
        SloCheck avail = report.checks().stream()
                .filter(c -> c.sliName().equals("availability"))
                .findFirst().orElseThrow();
        assertThat(avail.met()).isFalse();
        assertThat(avail.actual()).isEqualTo(0.80);
        assertThat(avail.target()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("latency SLO violation when P99 exceeds target")
    void latencyViolation() {
        when(sliDefinitionService.computeSlis("BE")).thenReturn(sliReport(0.99, 500.0, 0.8));

        SloComplianceReport report = tracker.checkCompliance("BE");

        assertThat(report.allMet()).isFalse();
        SloCheck latency = report.checks().stream()
                .filter(c -> c.sliName().equals("latency_p99_seconds"))
                .findFirst().orElseThrow();
        assertThat(latency.met()).isFalse();
        assertThat(latency.actual()).isEqualTo(500.0);
    }

    @Test
    @DisplayName("quality SLO violation when below threshold")
    void qualityViolation() {
        when(sliDefinitionService.computeSlis("BE")).thenReturn(sliReport(0.99, 100.0, 0.3));

        SloComplianceReport report = tracker.checkCompliance("BE");

        assertThat(report.allMet()).isFalse();
        SloCheck quality = report.checks().stream()
                .filter(c -> c.sliName().equals("quality"))
                .findFirst().orElseThrow();
        assertThat(quality.met()).isFalse();
    }

    @Test
    @DisplayName("report without latency SLI skips latency check")
    void noLatencySli() {
        SliReport report = new SliReport("BE", List.of(
                new SliValue("availability", 0.99, "ratio", ""),
                new SliValue("quality", 0.8, "ratio", "")
        ), 10);
        when(sliDefinitionService.computeSlis("BE")).thenReturn(report);

        SloComplianceReport result = tracker.checkCompliance("BE");

        assertThat(result.checks()).hasSize(2);
        assertThat(result.allMet()).isTrue();
    }

    @Test
    @DisplayName("at-boundary availability exactly at target is met")
    void atBoundary_availability() {
        when(sliDefinitionService.computeSlis("BE")).thenReturn(sliReport(0.95, 100.0, 0.5));

        SloComplianceReport report = tracker.checkCompliance("BE");

        assertThat(report.allMet()).isTrue();
    }

    private SliReport sliReport(double availability, double latencyP99, double quality) {
        return new SliReport("BE", List.of(
                new SliValue("availability", availability, "ratio", ""),
                new SliValue("latency_p99_seconds", latencyP99, "seconds", ""),
                new SliValue("quality", quality, "ratio", "")
        ), 100);
    }
}
