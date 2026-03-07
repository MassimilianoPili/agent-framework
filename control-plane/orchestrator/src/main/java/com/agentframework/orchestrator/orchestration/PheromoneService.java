package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Ant Colony Optimisation (ACO) pheromone service for workflow pattern learning.
 *
 * <p>Tracks successful workflow patterns as pheromone trails on {@link WorkerType} transitions.
 * When a plan completes with positive reward, pheromone is deposited proportionally along the
 * dependency edges. Hourly evaporation decays stale patterns.</p>
 *
 * <p>The planner can query {@link #formatHintsForPlanner()} to receive natural-language
 * suggestions based on the strongest pheromone trails.</p>
 *
 * @see PheromoneMatrix
 * @see <a href="https://mitpress.mit.edu/9780262042192/">
 *     Dorigo &amp; Stützle (2004), Ant Colony Optimization</a>
 */
@Service
@ConditionalOnProperty(prefix = "pheromone", name = "enabled", havingValue = "true")
public class PheromoneService {

    private static final Logger log = LoggerFactory.getLogger(PheromoneService.class);
    private static final String REDIS_KEY = "pheromone:matrix";

    @Value("${pheromone.evaporation-rate:0.1}")
    private double evaporationRate;

    @Value("${pheromone.initial-pheromone:1.0}")
    private double initialPheromone;

    @Value("${pheromone.alpha:1.0}")
    private double alpha;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private PheromoneMatrix matrix;

    public PheromoneService(@Qualifier("redisMessagingTemplate") StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        loadFromRedis();
        log.info("Pheromone service initialised (matrix {}x{}, rho={}, alpha={})",
                matrix.size(), matrix.size(), evaporationRate, alpha);
    }

    /**
     * Deposits pheromone for a completed plan based on its reward.
     *
     * <p>For each dependency edge (dep.workerType → item.workerType), deposits
     * {@code reward / totalEdges} pheromone.</p>
     *
     * @param plan   the completed plan
     * @param reward the plan's aggregate reward (typically 0.0–1.0)
     */
    public void depositForPlan(Plan plan, double reward) {
        if (reward <= 0) return;

        int totalEdges = countEdges(plan);
        if (totalEdges == 0) return;

        double delta = reward / totalEdges;

        Map<String, WorkerType> workerTypes = new LinkedHashMap<>();
        for (PlanItem item : plan.getItems()) {
            workerTypes.put(item.getTaskKey(), item.getWorkerType());
        }

        for (PlanItem item : plan.getItems()) {
            for (String dep : item.getDependsOn()) {
                WorkerType from = workerTypes.get(dep);
                if (from != null) {
                    matrix.deposit(from, item.getWorkerType(), delta);
                }
            }
        }

        saveToRedis();
        log.debug("Deposited pheromone for plan {} (reward={}, edges={}, delta={})",
                plan.getId(), reward, totalEdges, delta);
    }

    /**
     * Evaporates pheromone: τ(t+1) = (1 - ρ) × τ(t).
     * Scheduled hourly by default.
     */
    @Scheduled(fixedDelayString = "${pheromone.evaporation-interval-ms:3600000}")
    public void evaporate() {
        matrix.evaporate(evaporationRate);
        saveToRedis();
        log.debug("Pheromone evaporation applied (rho={})", evaporationRate);
    }

    /**
     * Suggests the most likely next worker types given a starting worker type.
     *
     * @param from starting worker type
     * @param topK max suggestions
     * @return ordered list of most likely next worker types
     */
    public List<WorkerType> suggestWorkflow(WorkerType from, int topK) {
        return matrix.suggestNext(from, alpha, topK);
    }

    /**
     * Formats pheromone hints as natural language for injection into the planner prompt.
     *
     * <p>Generates a summary of the strongest pheromone trails (top 3 per common starting type).
     * Returns empty string if no meaningful patterns exist.</p>
     */
    public String formatHintsForPlanner() {
        StringBuilder sb = new StringBuilder();
        sb.append("Based on historically successful plans, these workflow patterns are recommended:\n");

        WorkerType[] commonTypes = {
            WorkerType.CONTEXT_MANAGER, WorkerType.SCHEMA_MANAGER,
            WorkerType.HOOK_MANAGER, WorkerType.BE, WorkerType.FE,
            WorkerType.DBA, WorkerType.REVIEW
        };

        boolean hasHints = false;
        for (WorkerType from : commonTypes) {
            List<WorkerType> top = matrix.suggestNext(from, alpha, 3);
            if (!top.isEmpty()) {
                sb.append("- After ").append(from.name()).append(": ")
                  .append(top.stream().map(WorkerType::name)
                          .reduce((a, b) -> a + " → " + b).orElse(""))
                  .append("\n");
                hasHints = true;
            }
        }

        return hasHints ? sb.toString() : "";
    }

    // ── Package-private for testing ─────────────────────────────────────────────

    PheromoneMatrix getMatrix() {
        return matrix;
    }

    // ── Redis persistence ───────────────────────────────────────────────────────

    private void loadFromRedis() {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY);
            if (json != null) {
                double[] flat = objectMapper.readValue(json, double[].class);
                matrix = PheromoneMatrix.fromFlatArray(flat, initialPheromone);
                log.debug("Loaded pheromone matrix from Redis");
                return;
            }
        } catch (Exception e) {
            log.warn("Failed to load pheromone matrix from Redis, using fresh: {}", e.getMessage());
        }
        matrix = new PheromoneMatrix(initialPheromone);
    }

    private void saveToRedis() {
        try {
            String json = objectMapper.writeValueAsString(matrix.toFlatArray());
            redisTemplate.opsForValue().set(REDIS_KEY, json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to save pheromone matrix to Redis: {}", e.getMessage());
        }
    }

    private int countEdges(Plan plan) {
        return plan.getItems().stream()
                .mapToInt(item -> item.getDependsOn().size())
                .sum();
    }
}
