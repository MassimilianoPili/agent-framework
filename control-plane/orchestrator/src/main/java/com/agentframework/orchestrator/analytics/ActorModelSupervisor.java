package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Applies the Actor Model (Hewitt 1973; Agha 1986) to worker supervision analysis.
 *
 * <p>In this framework each worker type is modelled as an <em>actor</em>:
 * an autonomous computational entity with a mailbox (Redis Stream consumer group).
 * Messages are task dispatches; the actor processes them and produces results.</p>
 *
 * <p>Supervision strategies (Erlang/Akka inspired):</p>
 * <ul>
 *   <li><b>ONE_FOR_ONE</b>: restart only the crashed actor — isolated failures.</li>
 *   <li><b>ONE_FOR_ALL</b>: restart all actors if any one crashes — tight coupling.</li>
 *   <li><b>REST_FOR_ONE</b>: restart the crashed actor and all actors started after it.</li>
 * </ul>
 *
 * <p>Crash proxy: fraction of task outcomes with reward = 0. Backpressure proxy: worker
 * message count exceeds the configurable threshold.</p>
 */
@Service
@ConditionalOnProperty(prefix = "actor-model", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ActorModelSupervisor {

    private static final Logger log = LoggerFactory.getLogger(ActorModelSupervisor.class);

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${actor-model.backpressure-threshold:10}")
    private int backpressureThreshold;

    @Value("${actor-model.crash-rate-threshold:0.3}")
    private double crashRateThreshold;

    public ActorModelSupervisor(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Analyses the actor system based on recorded task outcomes.
     *
     * @return actor system report, or {@code null} if no data exists
     */
    public ActorSystemReport analyse() {
        List<Object[]> rows = taskOutcomeRepository.findRewardsByWorkerType();
        if (rows.isEmpty()) return null;

        // Group rewards by worker type
        Map<String, List<Double>> byType = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String wt     = (String) row[0];
            double reward = ((Number) row[1]).doubleValue();
            byType.computeIfAbsent(wt, k -> new ArrayList<>()).add(reward);
        }

        Map<String, ActorStatus> actors          = new LinkedHashMap<>();
        List<String>             crashedActors   = new ArrayList<>();
        boolean                  backpressure    = false;

        for (Map.Entry<String, List<Double>> e : byType.entrySet()) {
            String       type    = e.getKey();
            List<Double> rewards = e.getValue();
            int          n       = rewards.size();

            long   zeroes    = rewards.stream().filter(r -> r == 0.0).count();
            double crashRate = (double) zeroes / n;
            double avgReward = rewards.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            boolean bp       = n > backpressureThreshold;

            if (bp) backpressure = true;
            if (crashRate >= crashRateThreshold) crashedActors.add(type);

            actors.put(type, new ActorStatus(type, n, crashRate, avgReward, bp));
        }

        SupervisorStrategy strategy = selectStrategy(crashedActors, actors.size());
        List<String> recommendations = buildRecommendations(crashedActors, backpressure, strategy);

        log.debug("ActorModel: actors={} crashed={} backpressure={} strategy={}",
                actors.size(), crashedActors, backpressure, strategy);

        return new ActorSystemReport(actors, strategy, backpressure, crashedActors, recommendations);
    }

    private SupervisorStrategy selectStrategy(List<String> crashed, int totalActors) {
        if (crashed.isEmpty()) return SupervisorStrategy.ONE_FOR_ONE;
        if (crashed.size() >= totalActors) return SupervisorStrategy.ONE_FOR_ALL;
        return crashed.size() > totalActors / 2
                ? SupervisorStrategy.ONE_FOR_ALL
                : SupervisorStrategy.ONE_FOR_ONE;
    }

    private List<String> buildRecommendations(List<String> crashed, boolean bp,
                                               SupervisorStrategy strategy) {
        List<String> recs = new ArrayList<>();
        if (crashed.isEmpty() && !bp) {
            recs.add("All actors are healthy. System exhibits stable Actor Model properties.");
            return recs;
        }
        if (!crashed.isEmpty()) {
            recs.add("Crashed actors: " + crashed + ". Recommended strategy: " + strategy
                    + ". Consider circuit breakers or retry limits for high crash-rate workers.");
        }
        if (bp) {
            recs.add("Backpressure detected (queue depth > " + backpressureThreshold + ")."
                    + " Scale up consumer instances or increase Redis Stream parallelism.");
        }
        return recs;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /** Erlang/Akka-inspired supervisor restart strategy. */
    public enum SupervisorStrategy { ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE }

    /**
     * Per-actor status snapshot.
     *
     * @param workerType        actor identifier (worker type)
     * @param messagesProcessed total tasks processed (mailbox throughput)
     * @param crashRate         fraction of tasks with reward = 0
     * @param avgReward         mean reward across all processed tasks
     * @param backpressured     true if message count exceeds backpressure threshold
     */
    public record ActorStatus(
            String workerType,
            int messagesProcessed,
            double crashRate,
            double avgReward,
            boolean backpressured
    ) {}

    /**
     * Actor system supervision report.
     *
     * @param actors               per-actor status keyed by worker type
     * @param supervisorStrategy   recommended Erlang-style restart strategy
     * @param backpressureDetected true if any actor's mailbox depth exceeds threshold
     * @param crashedActors        worker types whose crash rate ≥ threshold
     * @param recommendations      actionable guidance for degraded actors
     */
    public record ActorSystemReport(
            Map<String, ActorStatus> actors,
            SupervisorStrategy supervisorStrategy,
            boolean backpressureDetected,
            List<String> crashedActors,
            List<String> recommendations
    ) {}
}
