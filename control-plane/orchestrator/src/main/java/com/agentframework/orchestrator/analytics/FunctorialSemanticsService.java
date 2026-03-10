package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Models a plan as a finite category and applies a functor to compute semantic consistency.
 *
 * <p>In category theory, a plan is a directed acyclic graph where:
 * <ul>
 *   <li><strong>Objects</strong>: PlanItem instances</li>
 *   <li><strong>Morphisms</strong>: dependency edges (A → B means B depends on A)</li>
 *   <li><strong>Identity</strong>: each item depends on itself (trivially)</li>
 *   <li><strong>Composition</strong>: if A→B and B→C then A→C (by transitivity)</li>
 * </ul>
 *
 * <p>The functor F maps each PlanItem to its GP-predicted reward (gp_mu from task outcomes),
 * and the natural transformation η maps each item to the gap (actual_reward − gp_mu).
 *
 * <p>Compositionality check: for each path A→B→C in the plan, is
 * F(path A→C) ≈ F(A→B) + F(B→C)?  In a compositional functor, the predicted reward
 * of a composite task should be the sum of component rewards.  We use L∞ error
 * (max absolute deviation) over all length-2 paths as the compositionality metric.
 *
 * @see <a href="https://doi.org/10.1017/CBO9780511803260">Mac Lane (1998), Categories for the Working Mathematician</a>
 */
@Service
@ConditionalOnProperty(prefix = "functorial-semantics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FunctorialSemanticsService {

    private static final Logger log = LoggerFactory.getLogger(FunctorialSemanticsService.class);

    static final int MIN_ITEMS = 2;

    /** Maximum allowed compositionality error to declare the functor "compositional". */
    @Value("${functorial-semantics.compositionality-threshold:0.3}")
    private double compositionalityThreshold;

    @Value("${functorial-semantics.compositionality-check:true}")
    private boolean compositionalityCheck;

    private final PlanItemRepository planItemRepository;
    private final TaskOutcomeRepository taskOutcomeRepository;

    public FunctorialSemanticsService(PlanItemRepository planItemRepository,
                                      TaskOutcomeRepository taskOutcomeRepository) {
        this.planItemRepository = planItemRepository;
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes a functorial semantics report for the given plan.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Load all PlanItems for the plan; build a taskKey→item index and morphism set.</li>
     *   <li>Query task outcomes to populate the functor F(item) = gp_mu.</li>
     *   <li>Populate natural transformation η(item) = actual_reward − gp_mu.</li>
     *   <li>If compositionalityCheck: for each length-2 path A→B→C compute compositionality error.</li>
     * </ol>
     *
     * @param planId plan UUID
     * @return functorial report, or null if fewer than MIN_ITEMS task outcomes are available
     */
    public FunctorialReport compute(UUID planId) {
        List<PlanItem> items = planItemRepository.findByPlanId(planId);
        if (items.size() < MIN_ITEMS) {
            log.debug("FunctorialSemantics for plan {}: {} items, need {}", planId, items.size(), MIN_ITEMS);
            return null;
        }

        // Build taskKey → item and taskKey → itemId maps
        Map<String, UUID> keyToId = new LinkedHashMap<>();
        for (PlanItem item : items) {
            keyToId.put(item.getTaskKey(), item.getId());
        }

        // Count morphisms (dependency edges)
        int numMorphisms = 0;
        for (PlanItem item : items) {
            numMorphisms += item.getDependsOn().size();
        }

        // Load outcomes: build itemId → (gp_mu, actual_reward) maps
        // findOutcomeByPlanItemId returns List<Object[]> with cols [id, gp_mu, actual_reward]
        // We iterate over items and query individually (reuse existing indexed query)
        Map<UUID, Double> functor = new LinkedHashMap<>();
        Map<UUID, Double> naturalTransform = new LinkedHashMap<>();

        for (PlanItem item : items) {
            List<Object[]> outcomeRows = taskOutcomeRepository.findOutcomeByPlanItemId(item.getId());
            if (outcomeRows.isEmpty()) continue;
            Object[] row = outcomeRows.get(0);
            // row columns: [0]=id, [1]=gp_mu, [2]=actual_reward

            double gpMu       = toDouble(row[1]);
            Double actualRew  = row[2] != null ? toDouble(row[2]) : null;

            functor.put(item.getId(), gpMu);
            if (actualRew != null) {
                naturalTransform.put(item.getId(), actualRew - gpMu);
            }
        }

        if (functor.size() < MIN_ITEMS) {
            log.debug("FunctorialSemantics for plan {}: only {} outcome rows, need {}", planId, functor.size(), MIN_ITEMS);
            return null;
        }

        // Compositionality check: L∞ error over all length-2 paths A→B→C
        boolean isCompositional = true;
        double maxCompositionalityError = 0.0;

        if (compositionalityCheck) {
            // Build adjacency: for each length-2 path A→B→C, check if the functor
            // (GP prediction) composes correctly against actual observed rewards.
            //
            // predicted_composite(A→C) = gp_mu(C) - gp_mu(A)
            // actual_composite(A→C) = actual_reward(C) - actual_reward(A)
            // error = |predicted - actual|
            //
            // This is non-trivially zero: it measures whether GP predictions
            // accurately model reality across composite dependency paths.
            Map<String, PlanItem> keyToItem = new LinkedHashMap<>();
            for (PlanItem item : items) {
                keyToItem.put(item.getTaskKey(), item);
            }

            for (PlanItem itemC : items) {
                UUID idC = itemC.getId();
                Double gpC = functor.get(idC);
                Double actualC = naturalTransform.get(idC);
                if (gpC == null || actualC == null) continue;
                // actual_reward(C) = η(C) + F(C) = naturalTransform + functor
                double realC = gpC + actualC;

                for (String keyB : itemC.getDependsOn()) {
                    PlanItem itemB = keyToItem.get(keyB);
                    if (itemB == null) continue;
                    UUID idB = itemB.getId();
                    if (functor.get(idB) == null) continue; // only needed for path existence

                    for (String keyA : itemB.getDependsOn()) {
                        UUID idA = keyToId.get(keyA);
                        if (idA == null) continue;
                        Double gpA = functor.get(idA);
                        Double actualA = naturalTransform.get(idA);
                        if (gpA == null || actualA == null) continue;
                        double realA = gpA + actualA;

                        // Functor prediction for composite path vs actual observation
                        double predictedComposite = gpC - gpA;
                        double actualComposite = realC - realA;
                        double error = Math.abs(predictedComposite - actualComposite);

                        if (error > maxCompositionalityError) maxCompositionalityError = error;
                    }
                }
            }

            isCompositional = maxCompositionalityError <= compositionalityThreshold;
        }

        log.debug("FunctorialSemantics for plan {}: {} objects, {} morphisms, compositional={}, L∞={}",
                  planId, functor.size(), numMorphisms, isCompositional, maxCompositionalityError);

        return new FunctorialReport(functor.size(), numMorphisms, functor, naturalTransform,
                                    isCompositional, maxCompositionalityError);
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    /**
     * Functorial semantics report for a plan.
     *
     * @param numObjects             number of plan items with outcome data (functor domain)
     * @param numMorphisms           number of dependency edges (morphisms in the category)
     * @param functor                F: itemId → GP-predicted reward (gp_mu)
     * @param naturalTransform       η: itemId → (actual_reward − gp_mu), for items with actual reward
     * @param isCompositional        true if the functor satisfies compositionality up to threshold
     * @param maxCompositionalityError L∞ compositionality error over all length-2 paths
     */
    public record FunctorialReport(
            int numObjects,
            int numMorphisms,
            Map<UUID, Double> functor,
            Map<UUID, Double> naturalTransform,
            boolean isCompositional,
            double maxCompositionalityError
    ) {}
}
