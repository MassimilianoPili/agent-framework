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
 * Unit tests for {@link ActorModelSupervisor}.
 *
 * <p>Covers: no data, healthy actors, crash detection, backpressure,
 * ONE_FOR_ONE vs ONE_FOR_ALL strategy selection.</p>
 */
@ExtendWith(MockitoExtension.class)
class ActorModelSupervisorTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private ActorModelSupervisor supervisor;

    @BeforeEach
    void setUp() {
        supervisor = new ActorModelSupervisor(taskOutcomeRepository);
        ReflectionTestUtils.setField(supervisor, "backpressureThreshold", 5);
        ReflectionTestUtils.setField(supervisor, "crashRateThreshold", 0.3);
    }

    private Object[] row(String type, double reward) {
        return new Object[]{type, reward};
    }

    // ── No data ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns null when no task outcomes exist")
    void analyse_noData_returnsNull() {
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(List.of());
        assertThat(supervisor.analyse()).isNull();
    }

    // ── Healthy system ────────────────────────────────────────────────────────

    @Test
    @DisplayName("all high-reward actors → healthy, ONE_FOR_ONE, no backpressure")
    void analyse_healthyActors_oneForOneNoBp() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) rows.add(row("be-java", 0.9));
        for (int i = 0; i < 3; i++) rows.add(row("fe-ts",   0.8));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        ActorModelSupervisor.ActorSystemReport report = supervisor.analyse();

        assertThat(report).isNotNull();
        assertThat(report.crashedActors()).isEmpty();
        assertThat(report.backpressureDetected()).isFalse();
        assertThat(report.supervisorStrategy())
                .isEqualTo(ActorModelSupervisor.SupervisorStrategy.ONE_FOR_ONE);
    }

    // ── Crash detection ───────────────────────────────────────────────────────

    @Test
    @DisplayName("many zero-reward outcomes → actor flagged as crashed")
    void analyse_manyZeroRewards_crashDetected() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) rows.add(row("be-java", 0.0));  // 7/10 = 70% crash rate
        for (int i = 0; i < 3; i++) rows.add(row("be-java", 0.8));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        ActorModelSupervisor.ActorSystemReport report = supervisor.analyse();

        assertThat(report.crashedActors()).contains("be-java");
        assertThat(report.actors().get("be-java").crashRate()).isGreaterThan(0.3);
    }

    // ── Backpressure ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("message count > threshold → backpressure detected")
    void analyse_highMessageCount_backpressure() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(row("be-java", 0.8));  // 10 > threshold=5
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        ActorModelSupervisor.ActorSystemReport report = supervisor.analyse();

        assertThat(report.backpressureDetected()).isTrue();
        assertThat(report.actors().get("be-java").backpressured()).isTrue();
    }

    // ── Strategy selection ────────────────────────────────────────────────────

    @Test
    @DisplayName("all actors crashed → ONE_FOR_ALL strategy")
    void analyse_allCrashed_oneForAll() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(row("be-java", 0.0));
        for (int i = 0; i < 5; i++) rows.add(row("fe-ts",   0.0));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        ActorModelSupervisor.ActorSystemReport report = supervisor.analyse();

        assertThat(report.supervisorStrategy())
                .isEqualTo(ActorModelSupervisor.SupervisorStrategy.ONE_FOR_ALL);
        assertThat(report.crashedActors()).containsExactlyInAnyOrder("be-java", "fe-ts");
    }

    @Test
    @DisplayName("minority crashed → ONE_FOR_ONE strategy")
    void analyse_minorityCrashed_oneForOne() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(row("be-java", 0.0));  // crashed
        for (int i = 0; i < 3; i++) rows.add(row("fe-ts",   0.9));  // healthy
        for (int i = 0; i < 3; i++) rows.add(row("dba",     0.8));  // healthy
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        ActorModelSupervisor.ActorSystemReport report = supervisor.analyse();

        assertThat(report.supervisorStrategy())
                .isEqualTo(ActorModelSupervisor.SupervisorStrategy.ONE_FOR_ONE);
    }

    // ── Report completeness ───────────────────────────────────────────────────

    @Test
    @DisplayName("report contains all worker types in actors map")
    void analyse_reportContainsAllActors() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("be-java", 0.8));
        rows.add(row("fe-ts",   0.7));
        rows.add(row("dba",     0.9));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        ActorModelSupervisor.ActorSystemReport report = supervisor.analyse();

        assertThat(report.actors()).containsKeys("be-java", "fe-ts", "dba");
        assertThat(report.actors().get("be-java").messagesProcessed()).isEqualTo(1);
    }
}
