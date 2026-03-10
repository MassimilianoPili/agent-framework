package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the Actor Model (Hewitt 1973; Agha 1986) with Erlang/OTP supervision semantics.
 *
 * <p>Each worker type is modelled as a <em>supervised child actor</em> with lifecycle states
 * (RUNNING → CRASHED → RESTARTING → RUNNING or STOPPED). The supervisor maintains a
 * <em>supervision tree</em> with configurable restart policies.</p>
 *
 * <h3>Supervision semantics (Erlang/OTP):</h3>
 * <ul>
 *   <li><b>ONE_FOR_ONE</b>: restart only the crashed child — isolated failures.</li>
 *   <li><b>ONE_FOR_ALL</b>: restart all children if any one crashes — tight coupling.</li>
 *   <li><b>REST_FOR_ONE</b>: restart the crashed child and all children registered after it.</li>
 * </ul>
 *
 * <h3>Restart policy:</h3>
 * <p>Maximum {@code maxRestarts} within {@code restartWindowSeconds}. If exceeded, the actor
 * is permanently STOPPED and escalation is triggered (alert to parent supervisor).</p>
 *
 * <p>Crash signal: a task outcome with reward = 0 is treated as an actor crash.</p>
 */
@Service
@ConditionalOnProperty(prefix = "actor-model", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ActorModelSupervisor {

    private static final Logger log = LoggerFactory.getLogger(ActorModelSupervisor.class);

    private final TaskOutcomeRepository taskOutcomeRepository;

    /** Supervision tree: ordered map preserving registration order (needed for REST_FOR_ONE). */
    private final Map<String, ChildActor> children = new LinkedHashMap<>();

    /** Restart event log per actor. */
    private final Map<String, List<RestartEvent>> restartHistory = new ConcurrentHashMap<>();

    @Value("${actor-model.backpressure-threshold:10}")
    private int backpressureThreshold;

    @Value("${actor-model.crash-rate-threshold:0.3}")
    private double crashRateThreshold;

    @Value("${actor-model.max-restarts:3}")
    private int maxRestarts;

    @Value("${actor-model.restart-window-seconds:60}")
    private int restartWindowSeconds;

    public ActorModelSupervisor(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Analyses the actor system: discovers children from task outcomes, detects crashes,
     * applies supervision strategy, and enforces restart policy.
     *
     * @return supervision report, or {@code null} if no data exists
     */
    public ActorSystemReport analyse() {
        List<Object[]> rows = taskOutcomeRepository.findRewardsByWorkerType();
        if (rows.isEmpty()) return null;

        // Group rewards by worker type (preserving order for REST_FOR_ONE)
        Map<String, List<Double>> byType = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String wt     = (String) row[0];
            double reward = ((Number) row[1]).doubleValue();
            byType.computeIfAbsent(wt, k -> new ArrayList<>()).add(reward);
        }

        // Ensure all observed worker types are registered as children
        for (String type : byType.keySet()) {
            children.computeIfAbsent(type, ChildActor::new);
        }

        // Detect crashes and update actor states
        List<String> newlyCrashed = new ArrayList<>();
        boolean backpressure = false;

        for (Map.Entry<String, List<Double>> e : byType.entrySet()) {
            String type = e.getKey();
            List<Double> rewards = e.getValue();
            ChildActor child = children.get(type);

            if (child.state == ActorState.STOPPED) continue; // permanently stopped

            int n = rewards.size();
            long zeroes = rewards.stream().filter(r -> r == 0.0).count();
            double crashRate = (double) zeroes / n;
            double avgReward = rewards.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            boolean bp = n > backpressureThreshold;

            child.messagesProcessed = n;
            child.crashRate = crashRate;
            child.avgReward = avgReward;
            child.backpressured = bp;

            if (bp) backpressure = true;

            if (crashRate >= crashRateThreshold && child.state == ActorState.RUNNING) {
                child.state = ActorState.CRASHED;
                newlyCrashed.add(type);
            }
        }

        // Apply supervision strategy
        SupervisorStrategy strategy = selectStrategy(newlyCrashed, children.size());
        List<String> toRestart = computeRestartSet(newlyCrashed, strategy);

        // Apply restart policy to each actor in the restart set
        List<String> restarted = new ArrayList<>();
        List<String> escalated = new ArrayList<>();
        Instant now = Instant.now();

        for (String type : toRestart) {
            ChildActor child = children.get(type);
            if (child == null) continue;

            if (exceedsRestartLimit(type, now)) {
                // Restart limit exceeded → permanent stop + escalation
                child.state = ActorState.STOPPED;
                escalated.add(type);
                log.warn("ActorModel: actor '{}' exceeded restart limit ({} in {}s) → STOPPED, escalating",
                        type, maxRestarts, restartWindowSeconds);
            } else {
                // Restart the actor
                child.state = ActorState.RESTARTING;
                recordRestart(type, now);
                child.state = ActorState.RUNNING; // restart completes
                child.crashRate = 0.0; // reset crash state after restart
                restarted.add(type);
                log.debug("ActorModel: restarted actor '{}'", type);
            }
        }

        // Build actor status map
        Map<String, ActorStatus> actorStatuses = new LinkedHashMap<>();
        List<String> allCrashed = new ArrayList<>();
        for (Map.Entry<String, ChildActor> e : children.entrySet()) {
            ChildActor c = e.getValue();
            actorStatuses.put(e.getKey(), new ActorStatus(
                    c.workerType, c.state, c.messagesProcessed,
                    c.crashRate, c.avgReward, c.backpressured,
                    getRestartCount(e.getKey(), now)));
            if (c.state == ActorState.CRASHED || c.state == ActorState.STOPPED) {
                allCrashed.add(e.getKey());
            }
        }

        List<String> recommendations = buildRecommendations(
                allCrashed, restarted, escalated, backpressure, strategy);

        log.debug("ActorModel: children={} crashed={} restarted={} escalated={} strategy={}",
                children.size(), newlyCrashed, restarted, escalated, strategy);

        return new ActorSystemReport(
                actorStatuses, strategy, backpressure,
                allCrashed, restarted, escalated, recommendations);
    }

    /**
     * Returns the ordered list of children to restart given the strategy.
     */
    List<String> computeRestartSet(List<String> crashed, SupervisorStrategy strategy) {
        if (crashed.isEmpty()) return List.of();

        return switch (strategy) {
            case ONE_FOR_ONE -> new ArrayList<>(crashed);
            case ONE_FOR_ALL -> new ArrayList<>(children.keySet());
            case REST_FOR_ONE -> {
                // Find earliest crashed actor in registration order, restart it + all after
                List<String> ordered = new ArrayList<>(children.keySet());
                int earliest = ordered.size();
                for (String c : crashed) {
                    int idx = ordered.indexOf(c);
                    if (idx >= 0 && idx < earliest) earliest = idx;
                }
                yield new ArrayList<>(ordered.subList(earliest, ordered.size()));
            }
        };
    }

    private boolean exceedsRestartLimit(String type, Instant now) {
        List<RestartEvent> events = restartHistory.getOrDefault(type, List.of());
        Instant windowStart = now.minusSeconds(restartWindowSeconds);
        long recentRestarts = events.stream()
                .filter(e -> e.timestamp().isAfter(windowStart))
                .count();
        return recentRestarts >= maxRestarts;
    }

    private void recordRestart(String type, Instant timestamp) {
        restartHistory.computeIfAbsent(type, k -> new ArrayList<>())
                .add(new RestartEvent(type, timestamp));
    }

    private int getRestartCount(String type, Instant now) {
        List<RestartEvent> events = restartHistory.getOrDefault(type, List.of());
        Instant windowStart = now.minusSeconds(restartWindowSeconds);
        return (int) events.stream()
                .filter(e -> e.timestamp().isAfter(windowStart))
                .count();
    }

    private SupervisorStrategy selectStrategy(List<String> crashed, int totalActors) {
        if (crashed.isEmpty()) return SupervisorStrategy.ONE_FOR_ONE;
        if (crashed.size() >= totalActors) return SupervisorStrategy.ONE_FOR_ALL;
        return crashed.size() > totalActors / 2
                ? SupervisorStrategy.ONE_FOR_ALL
                : SupervisorStrategy.ONE_FOR_ONE;
    }

    private List<String> buildRecommendations(List<String> crashed, List<String> restarted,
                                               List<String> escalated, boolean bp,
                                               SupervisorStrategy strategy) {
        List<String> recs = new ArrayList<>();
        if (crashed.isEmpty() && !bp) {
            recs.add("All actors RUNNING. Supervision tree healthy.");
            return recs;
        }
        if (!restarted.isEmpty()) {
            recs.add("Restarted actors " + restarted + " using " + strategy
                    + " strategy. Monitor for repeated failures.");
        }
        if (!escalated.isEmpty()) {
            recs.add("ESCALATION: actors " + escalated + " exceeded restart limit ("
                    + maxRestarts + " in " + restartWindowSeconds + "s) → permanently STOPPED."
                    + " Manual intervention required.");
        }
        if (bp) {
            recs.add("Backpressure detected (mailbox depth > " + backpressureThreshold + ")."
                    + " Scale consumer instances or increase Redis Stream parallelism.");
        }
        return recs;
    }

    /** Resets internal state (useful for testing). */
    void reset() {
        children.clear();
        restartHistory.clear();
    }

    // ── DTOs and State ───────────────────────────────────────────────────────

    /** Actor lifecycle states. */
    public enum ActorState { RUNNING, CRASHED, RESTARTING, STOPPED }

    /** Erlang/OTP supervisor restart strategy. */
    public enum SupervisorStrategy { ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE }

    /** Mutable internal child actor representation. */
    static class ChildActor {
        final String workerType;
        ActorState state = ActorState.RUNNING;
        int messagesProcessed;
        double crashRate;
        double avgReward;
        boolean backpressured;

        ChildActor(String workerType) {
            this.workerType = workerType;
        }
    }

    /** Immutable restart event for history tracking. */
    record RestartEvent(String workerType, Instant timestamp) {}

    /**
     * Per-actor status snapshot (immutable, exposed via report).
     */
    public record ActorStatus(
            String workerType,
            ActorState state,
            int messagesProcessed,
            double crashRate,
            double avgReward,
            boolean backpressured,
            int restartsInWindow
    ) {}

    /**
     * Actor system supervision report.
     */
    public record ActorSystemReport(
            Map<String, ActorStatus> actors,
            SupervisorStrategy supervisorStrategy,
            boolean backpressureDetected,
            List<String> crashedActors,
            List<String> restartedActors,
            List<String> escalatedActors,
            List<String> recommendations
    ) {}
}
