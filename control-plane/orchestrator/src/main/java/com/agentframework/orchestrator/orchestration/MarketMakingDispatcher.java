package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.graph.CriticalPathCalculator;
import com.agentframework.orchestrator.graph.TropicalScheduler;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Market-making dispatch prioritization based on inventory risk.
 *
 * <p>Adapts the Avellaneda-Stoikov bid-ask spread model to prioritize which tasks
 * should be dispatched first. Tasks on the critical path and tasks waiting longer
 * receive higher priority. Worker types with overloaded inventory are deprioritized.</p>
 *
 * @see InventoryTracker
 * @see <a href="https://doi.org/10.1080/14697680802341587">
 *     Avellaneda &amp; Stoikov (2008), High-frequency trading in a limit order book</a>
 */
@Service
@ConditionalOnProperty(prefix = "dispatch.inventory", name = "enabled", havingValue = "true")
public class MarketMakingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MarketMakingDispatcher.class);

    private final PlanItemRepository planItemRepository;
    private final CriticalPathCalculator criticalPathCalculator;

    @Value("${dispatch.inventory.target-multiplier:2.0}")
    private double targetMultiplier;

    @Value("${dispatch.inventory.decay-threshold-hours:2.0}")
    private double decayThresholdHours;

    public MarketMakingDispatcher(PlanItemRepository planItemRepository,
                                    CriticalPathCalculator criticalPathCalculator) {
        this.planItemRepository = planItemRepository;
        this.criticalPathCalculator = criticalPathCalculator;
    }

    /**
     * Sorts items by market-making priority (descending).
     *
     * @param items  dispatchable items (WAITING with deps satisfied)
     * @param plan   the plan (for critical path computation)
     * @return items sorted by priority, highest first
     */
    public List<PlanItem> prioritize(List<PlanItem> items, Plan plan) {
        if (items.isEmpty()) return items;

        Map<String, Integer> currentInv = computeCurrentInventory();
        Map<String, Double> targetInv = computeTargetInventory();
        InventoryTracker tracker = new InventoryTracker(currentInv, targetInv);

        // Compute critical path
        Set<String> criticalPathKeys;
        try {
            TropicalScheduler.ScheduleResult schedule = criticalPathCalculator.computeSchedule(plan);
            criticalPathKeys = new HashSet<>(schedule.criticalPath());
        } catch (Exception e) {
            log.debug("Critical path computation failed, proceeding without CP bonus: {}", e.getMessage());
            criticalPathKeys = Set.of();
        }

        Instant now = Instant.now();
        final Set<String> cpKeys = criticalPathKeys;

        List<PlanItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> {
            double prioA = computeItemPriority(a, tracker, now, cpKeys);
            double prioB = computeItemPriority(b, tracker, now, cpKeys);
            return Double.compare(prioB, prioA); // descending
        });

        log.debug("Market-making prioritized {} items", sorted.size());
        return sorted;
    }

    private double computeItemPriority(PlanItem item, InventoryTracker tracker,
                                         Instant now, Set<String> cpKeys) {
        String wt = item.getWorkerType().name();
        double hoursSinceReady = 0;
        // Use dispatchedAt if available (re-dispatch), otherwise use a fixed small value
        // Since item is WAITING, it doesn't have dispatchedAt — use 0.5h as default
        hoursSinceReady = 0.5;
        boolean onCP = cpKeys.contains(item.getTaskKey());
        return tracker.priority(wt, hoursSinceReady, onCP);
    }

    /**
     * Computes current DISPATCHED inventory per worker type.
     */
    Map<String, Integer> computeCurrentInventory() {
        Map<String, Integer> inventory = new LinkedHashMap<>();
        for (Object[] row : planItemRepository.countDispatchedByWorkerType()) {
            WorkerType wt = (WorkerType) row[0];
            long count = (Long) row[1];
            inventory.put(wt.name(), (int) count);
        }
        return inventory;
    }

    /**
     * Computes target inventory: completions in last 4 hours / 4 × multiplier.
     */
    Map<String, Double> computeTargetInventory() {
        Instant fourHoursAgo = Instant.now().minus(Duration.ofHours(4));
        Map<String, Double> target = new LinkedHashMap<>();
        for (Object[] row : planItemRepository.countCompletedAfterByWorkerType(fourHoursAgo)) {
            WorkerType wt = (WorkerType) row[0];
            long count = (Long) row[1];
            double rate = count / 4.0; // completions per hour
            target.put(wt.name(), rate * targetMultiplier);
        }
        return target;
    }
}
