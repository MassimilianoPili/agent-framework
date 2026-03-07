package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.HedgeAlgorithm.HedgeState;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Online learning service using the Hedge (Multiplicative Weights) algorithm.
 *
 * <p>Maintains per-workerType weight distributions over worker profiles.
 * After each task outcome, the losing profile's weight is reduced
 * exponentially, causing the algorithm to converge on the best profiles.</p>
 *
 * <p>State is persisted in Redis DB 4 (cache-database) with key pattern
 * {@code hedge:{workerType}}. The state includes expert names, weights,
 * round number, and learning rate.</p>
 *
 * <p>Regret bound: after T rounds with K experts, cumulative regret ≤ O(√(T ln K)).</p>
 *
 * @see HedgeAlgorithm
 * @see <a href="https://doi.org/10.1006/game.1997.0541">
 *     Freund &amp; Schapire (1997), A Decision-Theoretic Generalization of
 *     On-Line Learning, J. Computer and System Sciences</a>
 */
@Service
@ConditionalOnProperty(prefix = "hedge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HedgeAlgorithmService {

    private static final Logger log = LoggerFactory.getLogger(HedgeAlgorithmService.class);
    private static final String REDIS_KEY_PREFIX = "hedge:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WorkerProfileRegistry profileRegistry;

    @Value("${hedge.learning-rate:0.0}")
    private double configuredEta;

    @Value("${hedge.horizon:1000}")
    private int horizon;

    public HedgeAlgorithmService(@Qualifier("redisMessagingTemplate") StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  WorkerProfileRegistry profileRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Loads the current hedge state for a worker type from Redis.
     *
     * <p>If no state exists, initializes uniform weights over all profiles
     * registered for this worker type.</p>
     *
     * @param workerType the worker type
     * @return current hedge state
     */
    public HedgeState getState(WorkerType workerType) {
        String key = REDIS_KEY_PREFIX + workerType.name();
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, HedgeState.class);
            }
        } catch (Exception e) {
            log.debug("Failed to load hedge state from Redis for {}: {}", workerType, e.getMessage());
        }

        // Initialize uniform weights
        List<String> profiles = profileRegistry.profilesForWorkerType(workerType);
        String[] experts = profiles.toArray(new String[0]);
        double[] weights = HedgeAlgorithm.uniformWeights(experts.length);
        double eta = configuredEta > 0
                ? configuredEta
                : HedgeAlgorithm.learningRate(experts.length, horizon);

        return new HedgeState(experts, weights, 0, eta);
    }

    /**
     * Records a task outcome and updates hedge weights.
     *
     * <p>The loss for the given profile is {@code 1.0 - reward} (higher reward = lower loss).
     * All other profiles receive loss 0 (no information about alternatives).</p>
     *
     * @param workerType the worker type
     * @param profile    the profile that was used
     * @param loss       loss value (typically 1.0 - actual_reward)
     * @return updated hedge state
     */
    public HedgeState recordOutcome(WorkerType workerType, String profile, double loss) {
        HedgeState state = getState(workerType);
        double[] losses = new double[state.experts().length];

        // Assign loss to the profile that was used
        for (int i = 0; i < state.experts().length; i++) {
            if (state.experts()[i].equals(profile)) {
                losses[i] = loss;
                break;
            }
        }

        double[] updatedWeights = HedgeAlgorithm.update(state.weights(), losses, state.eta());
        HedgeState updated = new HedgeState(
                state.experts(), updatedWeights, state.round() + 1, state.eta());

        // Persist to Redis
        try {
            String json = objectMapper.writeValueAsString(updated);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + workerType.name(), json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to persist hedge state to Redis for {}: {}", workerType, e.getMessage());
        }

        log.debug("Hedge update for {} profile '{}': loss={}, round={}",
                workerType, profile, String.format("%.3f", loss), updated.round());

        return updated;
    }

    /**
     * Exploration bonus for a specific profile.
     *
     * <p>Returns the ratio of the profile's weight to the uniform weight (1/K).
     * Values &gt; 1.0 mean the hedge algorithm favors this profile;
     * values &lt; 1.0 mean it's underweight (historically bad).</p>
     *
     * @param workerType the worker type
     * @param profile    the profile to check
     * @return weight ratio (1.0 = neutral, &gt;1 = favored, &lt;1 = disfavored)
     */
    public double explorationBonus(WorkerType workerType, String profile) {
        HedgeState state = getState(workerType);
        if (state.experts().length == 0) return 1.0;

        double uniform = 1.0 / state.experts().length;
        for (int i = 0; i < state.experts().length; i++) {
            if (state.experts()[i].equals(profile)) {
                return state.weights()[i] / uniform;
            }
        }
        return 1.0; // profile not found → neutral
    }

    /**
     * Returns the current weight map for a worker type.
     *
     * @param workerType the worker type
     * @return map of {profile → weight}
     */
    public Map<String, Double> getWeights(WorkerType workerType) {
        HedgeState state = getState(workerType);
        Map<String, Double> weights = new LinkedHashMap<>();
        for (int i = 0; i < state.experts().length; i++) {
            weights.put(state.experts()[i], state.weights()[i]);
        }
        return weights;
    }
}
