package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Confidence-based worker-to-worker handoff routing.
 *
 * <p>Enables direct worker→worker delegation when confidence is high enough,
 * bypassing the central orchestrator for reduced latency. When confidence is low,
 * routes through the centralized orchestrator for safety.</p>
 *
 * <p>Inspired by AutoGen Swarm (Wu et al. 2023) with improvements from
 * Cemri et al. (ICLR 2025) failure mode analysis:</p>
 * <ul>
 *   <li><b>Confidence threshold</b>: direct handoff only above threshold</li>
 *   <li><b>Chain depth limit</b>: prevents infinite delegation loops</li>
 *   <li><b>Depth-scaled threshold</b>: deeper chains require higher confidence</li>
 *   <li><b>Visited-set anti-cycle</b>: no repeated delegation to same worker</li>
 * </ul>
 *
 * @see <a href="https://arxiv.org/abs/2308.08155">
 *     AutoGen (Wu et al. 2023) — multi-agent with handoff</a>
 */
@Service
@ConditionalOnProperty(prefix = "handoff-routing", name = "enabled", havingValue = "true", matchIfMissing = false)
public class HandoffRouterService {

    private static final Logger log = LoggerFactory.getLogger(HandoffRouterService.class);

    @Value("${handoff-routing.confidence-threshold:0.7}")
    private double confidenceThreshold;

    @Value("${handoff-routing.max-chain-length:5}")
    private int maxChainLength;

    /** Outcome tracking per worker pair: "from→to" → (successes, total). */
    private final ConcurrentHashMap<String, long[]> pairOutcomes = new ConcurrentHashMap<>();

    /** Global counters. */
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong directHandoffs = new AtomicLong(0);
    private final AtomicLong centralizedFallbacks = new AtomicLong(0);
    private double confidenceSum = 0.0;

    /**
     * Routes a handoff request, deciding between direct handoff and centralized routing.
     *
     * <p>Decision logic:
     * <ul>
     *   <li>Chain depth ≥ maxChainLength → centralized (prevent loops)</li>
     *   <li>No candidates or empty list → centralized</li>
     *   <li>Confidence ≥ threshold (scaled by depth) → direct to first viable candidate</li>
     *   <li>Otherwise → centralized fallback</li>
     * </ul></p>
     *
     * @param request the handoff request with source, candidates, confidence, and chain depth
     * @return routing decision
     */
    public HandoffDecision route(HandoffRequest request) {
        totalRequests.incrementAndGet();
        synchronized (this) {
            confidenceSum += request.confidence();
        }

        // Chain depth limit
        if (request.chainDepth() >= maxChainLength) {
            centralizedFallbacks.incrementAndGet();
            return new HandoffDecision(false, null,
                    String.format("Chain depth %d exceeds max %d — centralized routing",
                            request.chainDepth(), maxChainLength));
        }

        // Empty candidates
        if (request.candidates() == null || request.candidates().isEmpty()) {
            centralizedFallbacks.incrementAndGet();
            return new HandoffDecision(false, null, "No candidates available — centralized routing");
        }

        // Filter out visited workers (anti-cycle)
        List<String> viable = request.candidates().stream()
                .filter(c -> !request.visitedWorkers().contains(c))
                .toList();

        if (viable.isEmpty()) {
            centralizedFallbacks.incrementAndGet();
            return new HandoffDecision(false, null,
                    "All candidates already visited — centralized routing to avoid cycle");
        }

        // Depth-scaled threshold: deeper chains need higher confidence
        double scaledThreshold = confidenceThreshold + (request.chainDepth() * 0.1);

        if (request.confidence() >= scaledThreshold) {
            // Direct handoff — pick candidate with best historical success rate
            String selected = selectBestCandidate(request.fromWorker(), viable);
            directHandoffs.incrementAndGet();

            log.debug("Direct handoff: {} → {} (confidence={}, depth={})",
                      request.fromWorker(), selected, request.confidence(), request.chainDepth());

            return new HandoffDecision(true, selected,
                    String.format("Direct handoff: confidence %.2f ≥ threshold %.2f (depth=%d)",
                            request.confidence(), scaledThreshold, request.chainDepth()));
        } else {
            centralizedFallbacks.incrementAndGet();
            return new HandoffDecision(false, null,
                    String.format("Confidence %.2f < threshold %.2f (depth=%d) — centralized routing",
                            request.confidence(), scaledThreshold, request.chainDepth()));
        }
    }

    /**
     * Records the outcome of a handoff for tracking pair success rates.
     *
     * @param fromWorker source worker
     * @param toWorker   destination worker
     * @param successful whether the handoff led to successful task completion
     */
    public void recordOutcome(String fromWorker, String toWorker, boolean successful) {
        String pairKey = fromWorker + "→" + toWorker;
        pairOutcomes.compute(pairKey, (k, counts) -> {
            if (counts == null) counts = new long[]{0, 0};
            if (successful) counts[0]++;
            counts[1]++;
            return counts;
        });
    }

    /**
     * Returns aggregated handoff statistics.
     */
    public HandoffStats getStats() {
        long total = totalRequests.get();
        double avgConfidence;
        synchronized (this) {
            avgConfidence = total > 0 ? confidenceSum / total : 0.0;
        }
        return new HandoffStats(total, directHandoffs.get(), centralizedFallbacks.get(), avgConfidence);
    }

    /**
     * Returns the success rate for a specific worker pair.
     *
     * @return success rate [0, 1] or NaN if no data
     */
    public double getPairSuccessRate(String fromWorker, String toWorker) {
        long[] counts = pairOutcomes.get(fromWorker + "→" + toWorker);
        if (counts == null || counts[1] == 0) return Double.NaN;
        return (double) counts[0] / counts[1];
    }

    private String selectBestCandidate(String fromWorker, List<String> candidates) {
        String best = candidates.get(0);
        double bestRate = -1.0;

        for (String candidate : candidates) {
            long[] counts = pairOutcomes.get(fromWorker + "→" + candidate);
            if (counts != null && counts[1] > 0) {
                double rate = (double) counts[0] / counts[1];
                if (rate > bestRate) {
                    bestRate = rate;
                    best = candidate;
                }
            }
        }

        return best;
    }

    /**
     * A handoff request from one worker to potential targets.
     *
     * @param fromWorker     the worker initiating the handoff
     * @param candidates     candidate worker types to hand off to
     * @param reason         reason for the handoff
     * @param confidence     confidence in the handoff [0, 1]
     * @param chainDepth     current depth in the delegation chain (0 = first handoff)
     * @param visitedWorkers set of workers already visited in this chain (for anti-cycle)
     */
    public record HandoffRequest(
            String fromWorker,
            List<String> candidates,
            String reason,
            double confidence,
            int chainDepth,
            Set<String> visitedWorkers
    ) {}

    /**
     * Result of a handoff routing decision.
     *
     * @param directHandoff  true if handoff goes directly to another worker
     * @param selectedWorker the selected target worker (null if centralized)
     * @param routingReason  explanation of the routing decision
     */
    public record HandoffDecision(
            boolean directHandoff,
            String selectedWorker,
            String routingReason
    ) {}

    /**
     * Aggregated handoff statistics.
     *
     * @param totalRequests        total number of routing requests
     * @param directHandoffs       number of direct worker→worker handoffs
     * @param centralizedFallbacks number of centralized routing fallbacks
     * @param avgConfidence        average confidence across all requests
     */
    public record HandoffStats(
            long totalRequests,
            long directHandoffs,
            long centralizedFallbacks,
            double avgConfidence
    ) {}
}
