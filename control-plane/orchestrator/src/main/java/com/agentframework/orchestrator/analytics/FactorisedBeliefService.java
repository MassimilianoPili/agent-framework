package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Factorised belief models for multi-agent orchestration using Active Inference.
 *
 * <p>Each observer agent maintains an independent Gaussian belief (μ, σ²) about
 * every subject agent's competence. Beliefs are updated via conjugate Bayesian
 * precision-weighted pooling:</p>
 *
 * <pre>
 *   τ_new = τ_old + 1/noise²
 *   μ_new = (τ_old · μ_old + observation / noise²) / τ_new
 * </pre>
 *
 * <p>Action selection uses <b>Expected Free Energy (EFE)</b>, which naturally
 * balances exploitation (high μ) and exploration (high σ²):</p>
 *
 * <pre>
 *   EFE(a) = pragmatic(a) + λ · epistemic(a)
 *   pragmatic = -|μ(a) - desired|           (prefer agents close to desired outcome)
 *   epistemic = 0.5 · ln(1 + σ²(a)/noise²) (prefer uncertain agents for information gain)
 * </pre>
 *
 * <p>Li et al. (2026) show that GP-UCB is a special case of EFE, making this
 * a principled extension of the GP engine already in the framework.</p>
 *
 * @see <a href="https://arxiv.org/abs/2602.06029">
 *     Li et al. (2026) — GP-UCB as special case of EFE</a>
 * @see <a href="https://link.springer.com/chapter/10.1007/978-3-031-70415-4_20">
 *     Ruiz-Serra et al. (AAMAS 2025) — Factorised Active Inference</a>
 */
@Service
@ConditionalOnProperty(prefix = "factorised-beliefs", name = "enabled", havingValue = "true", matchIfMissing = false)
public class FactorisedBeliefService {

    private static final Logger log = LoggerFactory.getLogger(FactorisedBeliefService.class);

    /** Observation noise variance — controls how much each observation shifts the belief. */
    private static final double NOISE_VARIANCE = 0.1;

    @Value("${factorised-beliefs.prior-mu:0.5}")
    private double priorMu;

    @Value("${factorised-beliefs.prior-sigma2:1.0}")
    private double priorSigma2;

    @Value("${factorised-beliefs.exploration-lambda:1.0}")
    private double explorationLambda;

    /** O(n²) belief matrix: observer → (subject → belief). */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AgentBelief>> beliefs =
            new ConcurrentHashMap<>();

    /**
     * Updates the observer's belief about the subject given an observed reward.
     *
     * <p>Uses conjugate Gaussian precision-weighted pooling:
     * <pre>
     *   τ_new = τ_old + 1/noise²
     *   μ_new = (τ_old · μ_old + observation / noise²) / τ_new
     *   σ²_new = 1 / τ_new
     * </pre></p>
     *
     * @param observer       the observing agent
     * @param subject        the agent being observed
     * @param observedReward the observed performance [0, 1]
     * @return the updated belief
     */
    public AgentBelief updateBelief(String observer, String subject, double observedReward) {
        ConcurrentHashMap<String, AgentBelief> observerBeliefs =
                beliefs.computeIfAbsent(observer, k -> new ConcurrentHashMap<>());

        AgentBelief current = observerBeliefs.getOrDefault(subject,
                new AgentBelief(observer, subject, priorMu, priorSigma2, 0));

        // Precision-weighted update (conjugate Gaussian)
        double precisionOld = 1.0 / current.sigma2();
        double precisionObs = 1.0 / NOISE_VARIANCE;
        double precisionNew = precisionOld + precisionObs;

        double muNew = (precisionOld * current.mu() + precisionObs * observedReward) / precisionNew;
        double sigma2New = 1.0 / precisionNew;

        AgentBelief updated = new AgentBelief(observer, subject, muNew, sigma2New,
                current.observations() + 1);
        observerBeliefs.put(subject, updated);

        log.debug("Belief update: {}→{} mu={} σ²={} (n={})",
                  observer, subject, muNew, sigma2New, updated.observations());

        return updated;
    }

    /**
     * Computes Expected Free Energy scores for candidate agents from an observer's perspective.
     *
     * <p>EFE decomposes into:
     * <ul>
     *   <li><b>Pragmatic value</b>: negative distance from desired reward — prefer agents
     *       whose expected competence is close to what we need</li>
     *   <li><b>Epistemic value</b>: information gain from selecting this agent —
     *       prefer uncertain agents to reduce belief entropy</li>
     * </ul></p>
     *
     * @param observer      the observing agent making the selection
     * @param candidates    candidate agent types to evaluate
     * @param desiredReward the target performance level [0, 1]
     * @return EFE scores for each candidate, lower is better (minimise free energy)
     */
    public List<EfeScore> computeEfe(String observer, List<String> candidates, double desiredReward) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        ConcurrentHashMap<String, AgentBelief> observerBeliefs =
                beliefs.getOrDefault(observer, new ConcurrentHashMap<>());

        return candidates.stream()
                .map(candidate -> {
                    AgentBelief belief = observerBeliefs.getOrDefault(candidate,
                            new AgentBelief(observer, candidate, priorMu, priorSigma2, 0));

                    // Pragmatic: negative absolute distance from desired outcome
                    double pragmatic = -Math.abs(belief.mu() - desiredReward);

                    // Epistemic: information gain (entropy reduction) from observing this agent
                    double epistemic = 0.5 * Math.log(1.0 + belief.sigma2() / NOISE_VARIANCE);

                    // EFE = pragmatic + lambda * epistemic (higher is better for selection)
                    double totalEfe = pragmatic + explorationLambda * epistemic;

                    return new EfeScore(candidate, epistemic, pragmatic, totalEfe);
                })
                .sorted(Comparator.comparingDouble(EfeScore::totalEfe).reversed())
                .toList();
    }

    /**
     * Selects the best candidate agent by minimising Expected Free Energy.
     *
     * @param observer      the observing agent
     * @param candidates    candidate agent types
     * @param desiredReward target performance level
     * @return the candidate with the highest EFE score (best balance of exploit + explore),
     *         or empty if no candidates
     */
    public Optional<EfeScore> selectByEfe(String observer, List<String> candidates,
                                           double desiredReward) {
        List<EfeScore> scores = computeEfe(observer, candidates, desiredReward);
        return scores.isEmpty() ? Optional.empty() : Optional.of(scores.get(0));
    }

    /**
     * Returns a snapshot of the full O(n²) belief matrix.
     *
     * @return map of observer → (subject → belief)
     */
    public Map<String, Map<String, AgentBelief>> getBeliefMatrix() {
        Map<String, Map<String, AgentBelief>> snapshot = new HashMap<>();
        beliefs.forEach((observer, subjectMap) ->
                snapshot.put(observer, new HashMap<>(subjectMap)));
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Retrieves a specific belief, or the prior if no observations exist.
     *
     * @param observer the observing agent
     * @param subject  the agent being observed
     * @return the current belief (or prior)
     */
    public AgentBelief getBelief(String observer, String subject) {
        ConcurrentHashMap<String, AgentBelief> observerBeliefs = beliefs.get(observer);
        if (observerBeliefs == null) {
            return new AgentBelief(observer, subject, priorMu, priorSigma2, 0);
        }
        return observerBeliefs.getOrDefault(subject,
                new AgentBelief(observer, subject, priorMu, priorSigma2, 0));
    }

    /**
     * Returns the total number of belief entries across all observers.
     */
    public int getTotalBeliefCount() {
        return beliefs.values().stream().mapToInt(ConcurrentHashMap::size).sum();
    }

    /**
     * A Gaussian belief that observer holds about subject's competence.
     *
     * @param observer     the agent holding this belief
     * @param subject      the agent this belief is about
     * @param mu           mean estimate of competence [0, 1]
     * @param sigma2       variance (uncertainty) of the estimate
     * @param observations number of observations that shaped this belief
     */
    public record AgentBelief(
            String observer,
            String subject,
            double mu,
            double sigma2,
            int observations
    ) {}

    /**
     * Expected Free Energy decomposition for a candidate agent.
     *
     * @param workerType     the candidate agent type
     * @param epistemicValue information gain from selecting this agent
     * @param pragmaticValue expected utility (negative distance from desired)
     * @param totalEfe       combined EFE score (higher = better selection)
     */
    public record EfeScore(
            String workerType,
            double epistemicValue,
            double pragmaticValue,
            double totalEfe
    ) {}
}
