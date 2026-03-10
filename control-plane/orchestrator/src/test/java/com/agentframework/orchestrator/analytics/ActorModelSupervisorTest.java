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
 * ONE_FOR_ONE vs ONE_FOR_ALL strategy selection, restart policy enforcement,
 * escalation on restart limit exceeded, REST_FOR_ONE ordering, state transitions.</p>
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
        ReflectionTestUtils.setField(supervisor, "maxRestarts", 3);
        ReflectionTestUtils.setField(supervisor, "restartWindowSeconds", 60);
        supervisor.reset();
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
    @DisplayName("all high-reward actors → RUNNING, ONE_FOR_ONE, no backpressure")
    void analyse_healthyActors_allRunning() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) rows.add(row("be-java", 0.9));
        for (int i = 0; i < 3; i++) rows.add(row("fe-ts",   0.8));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        var report = supervisor.analyse();

        assertThat(report).isNotNull();
        assertThat(report.crashedActors()).isEmpty();
        assertThat(report.restartedActors()).isEmpty();
        assertThat(report.escalatedActors()).isEmpty();
        assertThat(report.backpressureDetected()).isFalse();
        assertThat(report.supervisorStrategy())
                .isEqualTo(ActorModelSupervisor.SupervisorStrategy.ONE_FOR_ONE);

        // All actors in RUNNING state
        report.actors().values().forEach(a ->
                assertThat(a.state()).isEqualTo(ActorModelSupervisor.ActorState.RUNNING));
    }

    // ── Crash detection + restart ─────────────────────────────────────────────

    @Test
    @DisplayName("high crash rate → actor restarted (ONE_FOR_ONE)")
    void analyse_crashDetected_actorRestarted() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) rows.add(row("be-java", 0.0));  // 70% crash rate
        for (int i = 0; i < 3; i++) rows.add(row("be-java", 0.8));
        for (int i = 0; i < 3; i++) rows.add(row("fe-ts",   0.9));  // healthy
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        var report = supervisor.analyse();

        // be-java was crashed and restarted (back to RUNNING)
        assertThat(report.restartedActors()).contains("be-java");
        assertThat(report.actors().get("be-java").state())
                .isEqualTo(ActorModelSupervisor.ActorState.RUNNING);
        assertThat(report.actors().get("be-java").restartsInWindow()).isEqualTo(1);
        // fe-ts was never crashed
        assertThat(report.actors().get("fe-ts").state())
                .isEqualTo(ActorModelSupervisor.ActorState.RUNNING);
    }

    // ── Backpressure ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("message count > threshold → backpressure detected")
    void analyse_highMessageCount_backpressure() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(row("be-java", 0.8));  // 10 > threshold=5
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        var report = supervisor.analyse();

        assertThat(report.backpressureDetected()).isTrue();
        assertThat(report.actors().get("be-java").backpressured()).isTrue();
    }

    // ── Strategy selection ────────────────────────────────────────────────────

    @Test
    @DisplayName("all actors crashed → ONE_FOR_ALL, all restarted")
    void analyse_allCrashed_oneForAll() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(row("be-java", 0.0));
        for (int i = 0; i < 5; i++) rows.add(row("fe-ts",   0.0));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        var report = supervisor.analyse();

        assertThat(report.supervisorStrategy())
                .isEqualTo(ActorModelSupervisor.SupervisorStrategy.ONE_FOR_ALL);
        // ONE_FOR_ALL restarts ALL children, not just crashed
        assertThat(report.restartedActors()).containsExactlyInAnyOrder("be-java", "fe-ts");
    }

    @Test
    @DisplayName("minority crashed → ONE_FOR_ONE, only crashed actor restarted")
    void analyse_minorityCrashed_oneForOne() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(row("be-java", 0.0));  // crashed
        for (int i = 0; i < 3; i++) rows.add(row("fe-ts",   0.9));  // healthy
        for (int i = 0; i < 3; i++) rows.add(row("dba",     0.8));  // healthy
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        var report = supervisor.analyse();

        assertThat(report.supervisorStrategy())
                .isEqualTo(ActorModelSupervisor.SupervisorStrategy.ONE_FOR_ONE);
        assertThat(report.restartedActors()).containsExactly("be-java");
    }

    // ── Restart policy enforcement (escalation) ──────────────────────────────

    @Test
    @DisplayName("exceeding restart limit → actor STOPPED, escalation triggered")
    void analyse_restartLimitExceeded_escalation() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(row("be-java", 0.0));
        for (int i = 0; i < 3; i++) rows.add(row("fe-ts",   0.9));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        // Each analyse() detects crash (70%+ crash rate) and restarts.
        // After restart, actor goes RUNNING with crashRate=0. Next analyse()
        // recomputes crashRate from same data → crashes again → restart.
        // maxRestarts=3: calls 1-3 restart, call 4 escalates.
        supervisor.analyse(); // restart 1
        supervisor.analyse(); // restart 2
        supervisor.analyse(); // restart 3
        var report = supervisor.analyse(); // should escalate (4th crash)

        assertThat(report.escalatedActors()).contains("be-java");
        assertThat(report.actors().get("be-java").state())
                .isEqualTo(ActorModelSupervisor.ActorState.STOPPED);
    }

    // ── REST_FOR_ONE strategy ─────────────────────────────────────────────────

    @Test
    @DisplayName("REST_FOR_ONE restarts crashed actor and all registered after it")
    void computeRestartSet_restForOne_includesSubsequent() {
        // Register children in order: a, b, c
        List<String> crashed = List.of("b");
        var restartSet = supervisor.computeRestartSet(crashed,
                ActorModelSupervisor.SupervisorStrategy.REST_FOR_ONE);

        // With no children registered, set is empty (no index found)
        assertThat(restartSet).isEmpty();
    }

    @Test
    @DisplayName("REST_FOR_ONE with registered children includes subsequent actors")
    void computeRestartSet_restForOne_withRegisteredChildren() {
        // Pre-register children by running an analysis
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("alpha", 0.9));
        rows.add(row("beta",  0.9));
        rows.add(row("gamma", 0.9));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);
        supervisor.analyse(); // registers alpha, beta, gamma in order

        var restartSet = supervisor.computeRestartSet(
                List.of("beta"), ActorModelSupervisor.SupervisorStrategy.REST_FOR_ONE);

        // beta crashed → restart beta + gamma (registered after), but NOT alpha
        assertThat(restartSet).containsExactly("beta", "gamma");
    }

    // ── Report completeness ───────────────────────────────────────────────────

    @Test
    @DisplayName("report contains all worker types with correct fields")
    void analyse_reportContainsAllActors() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("be-java", 0.8));
        rows.add(row("fe-ts",   0.7));
        rows.add(row("dba",     0.9));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        var report = supervisor.analyse();

        assertThat(report.actors()).containsKeys("be-java", "fe-ts", "dba");
        assertThat(report.actors().get("be-java").messagesProcessed()).isEqualTo(1);
        assertThat(report.actors().get("be-java").state())
                .isEqualTo(ActorModelSupervisor.ActorState.RUNNING);
    }

    // ── State persistence across calls ────────────────────────────────────────

    @Test
    @DisplayName("STOPPED actor is not restarted on subsequent analysis")
    void analyse_stoppedActorStaysStopped() {
        // maxRestarts=1: first call restarts, second call escalates
        ReflectionTestUtils.setField(supervisor, "maxRestarts", 1);

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(row("be-java", 0.0));
        rows.add(row("fe-ts", 0.9));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        supervisor.analyse(); // restart 1
        var report2 = supervisor.analyse(); // escalation (2nd crash, limit=1)

        assertThat(report2.escalatedActors()).contains("be-java");

        // 3rd call: actor should stay STOPPED, no restart/escalation attempted
        var report3 = supervisor.analyse();

        assertThat(report3.actors().get("be-java").state())
                .isEqualTo(ActorModelSupervisor.ActorState.STOPPED);
        assertThat(report3.restartedActors()).doesNotContain("be-java");
        assertThat(report3.escalatedActors()).doesNotContain("be-java");
    }
}
