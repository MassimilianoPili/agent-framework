package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
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
 * Unit tests for {@link ViableSystemAuditor}.
 *
 * <p>Covers S1–S5 subsystem status, Shannon entropy, recommendations,
 * and edge cases (no data, single worker type).</p>
 */
@ExtendWith(MockitoExtension.class)
class ViableSystemAuditorTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private ViableSystemAuditor auditor;

    @BeforeEach
    void setUp() {
        auditor = new ViableSystemAuditor(taskOutcomeRepository);
        ReflectionTestUtils.setField(auditor, "maxSamples", 2000);
    }

    /** Row for findRewardsByWorkerType: [worker_type, reward] */
    private Object[] rewardRow(String type, double reward) {
        return new Object[]{type, reward};
    }

    /** Row for findPlanWorkerRewardSummary: [plan_id_text, worker_type, reward] */
    private Object[] planRow(String planId, String type, double reward) {
        return new Object[]{planId, type, reward};
    }

    // ── No data ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns null when no task outcomes exist")
    void audit_noData_returnsNull() {
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(List.of());

        assertThat(auditor.audit()).isNull();
    }

    // ── Healthy framework ────────────────────────────────────────────────────────

    @Test
    @DisplayName("diverse workers, multi-type plans, high reward → all subsystems FUNCTIONAL")
    void audit_healthyFramework_allFunctional() {
        // 3 worker types — good S1 variety (H > 1 bit)
        List<Object[]> rewards = new ArrayList<>();
        for (int i = 0; i < 10; i++) rewards.add(rewardRow("be-java", 0.85));
        for (int i = 0; i < 10; i++) rewards.add(rewardRow("fe-ts",   0.80));
        for (int i = 0; i < 10; i++) rewards.add(rewardRow("dba",     0.75));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rewards);

        // Plans with multiple worker types (S2 coordination, S4 intelligence)
        List<Object[]> planSummary = new ArrayList<>();
        planSummary.add(planRow("plan-1", "be-java", 0.85));
        planSummary.add(planRow("plan-1", "fe-ts",   0.80));
        planSummary.add(planRow("plan-2", "fe-ts",   0.80));
        planSummary.add(planRow("plan-2", "dba",     0.75));
        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(planSummary);

        ViableSystemAuditor.VSMAuditReport report = auditor.audit();

        assertThat(report).isNotNull();
        assertThat(report.s1Status()).isEqualTo(ViableSystemAuditor.SubsystemStatus.FUNCTIONAL);
        assertThat(report.s2Status()).isEqualTo(ViableSystemAuditor.SubsystemStatus.FUNCTIONAL);
        assertThat(report.s3Status()).isEqualTo(ViableSystemAuditor.SubsystemStatus.FUNCTIONAL);
        assertThat(report.s4Status()).isEqualTo(ViableSystemAuditor.SubsystemStatus.FUNCTIONAL);
        assertThat(report.s5Status()).isEqualTo(ViableSystemAuditor.SubsystemStatus.FUNCTIONAL);
    }

    // ── S1 — Operations ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("single worker type → S1 DEGRADED (H = 0 bits, no variety)")
    void audit_singleWorkerType_s1Degraded() {
        List<Object[]> rewards = new ArrayList<>();
        for (int i = 0; i < 10; i++) rewards.add(rewardRow("be-java", 0.8));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rewards);
        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(List.of());

        ViableSystemAuditor.VSMAuditReport report = auditor.audit();

        assertThat(report).isNotNull();
        assertThat(report.s1Variety()).isCloseTo(0.0, within(1e-9));
        assertThat(report.s1Status()).isEqualTo(ViableSystemAuditor.SubsystemStatus.DEGRADED);
        assertThat(report.distinctWorkerTypes()).isEqualTo(1);
    }

    @Test
    @DisplayName("S1 entropy is non-negative")
    void audit_s1Variety_nonNegative() {
        List<Object[]> rewards = new ArrayList<>();
        rewards.add(rewardRow("be-java", 0.8));
        rewards.add(rewardRow("fe-ts",   0.7));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rewards);
        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(List.of());

        ViableSystemAuditor.VSMAuditReport report = auditor.audit();

        assertThat(report.s1Variety()).isGreaterThanOrEqualTo(0.0);
    }

    // ── S2 — Coordination ──────────────────────────────────────────────────────

    @Test
    @DisplayName("all single-worker-type plans → S2 ABSENT")
    void audit_noMultiTypePlans_s2Absent() {
        List<Object[]> rewards = new ArrayList<>();
        for (int i = 0; i < 5; i++) rewards.add(rewardRow("be-java", 0.8));
        for (int i = 0; i < 5; i++) rewards.add(rewardRow("fe-ts",   0.7));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rewards);

        // Each plan has only 1 worker type
        List<Object[]> planSummary = List.of(
                planRow("plan-1", "be-java", 0.8),
                planRow("plan-2", "fe-ts",   0.7)
        );
        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(planSummary);

        ViableSystemAuditor.VSMAuditReport report = auditor.audit();

        assertThat(report.s2Status()).isEqualTo(ViableSystemAuditor.SubsystemStatus.ABSENT);
        assertThat(report.s2CoordinationRatio()).isCloseTo(0.0, within(1e-9));
    }

    // ── S3 — Control ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("low mean reward → S3 DEGRADED")
    void audit_lowReward_s3Degraded() {
        List<Object[]> rewards = new ArrayList<>();
        for (int i = 0; i < 5; i++) rewards.add(rewardRow("be-java", 0.2));
        for (int i = 0; i < 5; i++) rewards.add(rewardRow("fe-ts",   0.1));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rewards);
        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(List.of());

        ViableSystemAuditor.VSMAuditReport report = auditor.audit();

        assertThat(report.s3MeanReward()).isLessThan(0.5);
        assertThat(report.s3Status()).isEqualTo(ViableSystemAuditor.SubsystemStatus.DEGRADED);
    }

    // ── S5 — Policy ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S5 is always FUNCTIONAL (human-in-the-loop invariant)")
    void audit_s5_alwaysFunctional() {
        List<Object[]> rewards = new ArrayList<>();
        rewards.add(rewardRow("be-java", 0.1)); // degraded everything else
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rewards);
        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(List.of());

        ViableSystemAuditor.VSMAuditReport report = auditor.audit();

        assertThat(report.s5Status()).isEqualTo(ViableSystemAuditor.SubsystemStatus.FUNCTIONAL);
    }

    // ── Recommendations ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("degraded S1 produces recommendation mentioning Ashby's Law")
    void audit_degradedS1_ashbyRecommendation() {
        List<Object[]> rewards = new ArrayList<>();
        for (int i = 0; i < 10; i++) rewards.add(rewardRow("be-java", 0.8));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rewards);
        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(List.of());

        ViableSystemAuditor.VSMAuditReport report = auditor.audit();

        assertThat(report.recommendations()).anyMatch(r -> r.contains("Ashby"));
    }

    @Test
    @DisplayName("healthy framework produces a positive recommendation")
    void audit_allFunctional_positiveRecommendation() {
        List<Object[]> rewards = new ArrayList<>();
        for (int i = 0; i < 10; i++) rewards.add(rewardRow("be-java", 0.85));
        for (int i = 0; i < 10; i++) rewards.add(rewardRow("fe-ts",   0.80));
        for (int i = 0; i < 10; i++) rewards.add(rewardRow("dba",     0.75));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rewards);

        List<Object[]> planSummary = List.of(
                planRow("p1", "be-java", 0.85), planRow("p1", "fe-ts", 0.80),
                planRow("p2", "fe-ts",   0.80), planRow("p2", "dba",   0.75)
        );
        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(planSummary);

        ViableSystemAuditor.VSMAuditReport report = auditor.audit();

        assertThat(report.recommendations()).anyMatch(r -> r.toLowerCase().contains("functional") ||
                r.toLowerCase().contains("viable"));
    }

    // ── totals ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("totalTasksAnalysed matches input row count")
    void audit_totalTasks_correct() {
        List<Object[]> rewards = new ArrayList<>();
        for (int i = 0; i < 7; i++) rewards.add(rewardRow("be-java", 0.8));
        for (int i = 0; i < 3; i++) rewards.add(rewardRow("fe-ts",   0.7));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rewards);
        when(taskOutcomeRepository.findPlanWorkerRewardSummary()).thenReturn(List.of());

        ViableSystemAuditor.VSMAuditReport report = auditor.audit();

        assertThat(report.totalTasksAnalysed()).isEqualTo(10);
        assertThat(report.distinctWorkerTypes()).isEqualTo(2);
    }
}
