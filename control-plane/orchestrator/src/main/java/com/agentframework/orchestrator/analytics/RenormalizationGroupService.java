package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Analyses plan DAG structure at multiple scales using Renormalization Group (RG) theory.
 *
 * <p>The Renormalization Group (Wilson, 1971) explains universality in critical systems:
 * at each coarse-graining step, irrelevant microscopic details are integrated out,
 * leaving only the essential long-range interactions.  Applied to task DAGs:</p>
 * <ul>
 *   <li><strong>Fine scale</strong>: individual tasks as nodes</li>
 *   <li><strong>Medium scale</strong>: blocks of ~3 consecutive tasks</li>
 *   <li><strong>Coarse scale</strong>: blocks of ~5 consecutive tasks</li>
 * </ul>
 * <p>Coupling at each scale = mean number of inter-block dependency edges per block pair.
 * Beta function = coupling(scale+1) / coupling(scale) — the "flow" of coupling strength.
 * A beta function value &gt; 1 indicates coupling grows (relevant in RG terms);
 * &lt; 1 indicates coupling shrinks (irrelevant).</p>
 *
 * @see <a href="https://doi.org/10.1103/PhysRevLett.28.240">Wilson &amp; Kogut (1974), The Renormalization Group</a>
 */
@Service
@ConditionalOnProperty(prefix = "renormalization-group", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RenormalizationGroupService {

    private static final Logger log = LoggerFactory.getLogger(RenormalizationGroupService.class);

    private static final int[] BLOCK_SIZES = {1, 3, 5};
    private static final String[] SCALE_NAMES = {"fine", "medium", "coarse"};

    private final PlanItemRepository planItemRepository;

    @Value("${renormalization-group.max-scales:3}")
    private int maxScales;

    public RenormalizationGroupService(PlanItemRepository planItemRepository) {
        this.planItemRepository = planItemRepository;
    }

    /**
     * Analyses the RG flow of coupling constants across scales for a plan.
     *
     * @param planId plan UUID
     * @return RG analysis report, or null if plan has fewer than 3 items
     */
    public RGAnalysisReport analyse(UUID planId) {
        List<PlanItem> items = planItemRepository.findByPlanId(planId);

        if (items.size() < 3) {
            log.debug("RG for plan {}: {} items, minimum 3 required", planId, items.size());
            return null;
        }

        // Sort by ordinal to get a canonical ordering
        items = new ArrayList<>(items);
        items.sort(Comparator.comparingInt(PlanItem::getOrdinal));

        // Build adjacency: taskKey → set of tasks that depend on it (reverse edges)
        Map<String, Set<String>> dependents = new HashMap<>();
        for (PlanItem item : items) {
            dependents.put(item.getTaskKey(), new HashSet<>());
        }
        for (PlanItem item : items) {
            for (String dep : item.getDependsOn()) {
                dependents.computeIfAbsent(dep, k -> new HashSet<>()).add(item.getTaskKey());
            }
        }

        int numScales = Math.min(maxScales, BLOCK_SIZES.length);
        Map<String, Double> couplingByScale = new LinkedHashMap<>();

        List<String> keys = items.stream().map(PlanItem::getTaskKey).toList();

        for (int s = 0; s < numScales; s++) {
            int blockSize = BLOCK_SIZES[s];
            String scaleName = SCALE_NAMES[s];

            // Partition items into blocks of size blockSize
            List<Set<String>> blocks = partitionIntoBlocks(keys, blockSize);

            // Build block index: taskKey → block index
            Map<String, Integer> blockOf = new HashMap<>();
            for (int b = 0; b < blocks.size(); b++) {
                for (String k : blocks.get(b)) {
                    blockOf.put(k, b);
                }
            }

            // Count inter-block dependency edges
            int interBlockEdges = 0;
            for (PlanItem item : items) {
                int ib = blockOf.getOrDefault(item.getTaskKey(), -1);
                for (String dep : item.getDependsOn()) {
                    int db = blockOf.getOrDefault(dep, -1);
                    if (ib >= 0 && db >= 0 && ib != db) {
                        interBlockEdges++;
                    }
                }
            }

            // Coupling = inter-block edges / number of block pairs
            int numBlocks = blocks.size();
            int blockPairs = numBlocks > 1 ? numBlocks * (numBlocks - 1) / 2 : 1;
            double coupling = (double) interBlockEdges / blockPairs;
            couplingByScale.put(scaleName, coupling);
        }

        // Beta function: flow of coupling between adjacent scales
        double[] couplingValues = couplingByScale.values().stream()
                .mapToDouble(Double::doubleValue).toArray();
        double[] betaFunction = new double[Math.max(0, couplingValues.length - 1)];
        for (int i = 0; i < betaFunction.length; i++) {
            betaFunction[i] = couplingValues[i] > 0
                    ? couplingValues[i + 1] / couplingValues[i]
                    : 0.0;
        }

        // Fixed points: where beta function ≈ 1 (coupling unchanged by scale transformation)
        double[] fixedPoints = new double[betaFunction.length];
        for (int i = 0; i < betaFunction.length; i++) {
            // Fixed point estimate: coupling where beta(coupling) = 1
            // In our linear model, this is where coupling is scale-invariant
            fixedPoints[i] = Math.abs(betaFunction[i] - 1.0) < 0.1
                    ? couplingValues[i]
                    : Double.NaN;
        }

        log.debug("RG for plan {}: {} items, {} scales, coupling: {}",
                  planId, items.size(), numScales, couplingByScale);

        return new RGAnalysisReport(items.size(), couplingByScale, betaFunction, fixedPoints);
    }

    private List<Set<String>> partitionIntoBlocks(List<String> keys, int blockSize) {
        List<Set<String>> blocks = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += blockSize) {
            Set<String> block = new LinkedHashSet<>();
            for (int j = i; j < Math.min(i + blockSize, keys.size()); j++) {
                block.add(keys.get(j));
            }
            blocks.add(block);
        }
        return blocks;
    }

    /**
     * Renormalization Group analysis report for a plan.
     *
     * @param numItems        total number of plan items
     * @param couplingByScale coupling constant at each scale (fine/medium/coarse)
     * @param betaFunction    β(s) = coupling(s+1)/coupling(s) — RG flow between scales
     * @param fixedPoints     coupling values where β ≈ 1 (scale-invariant points)
     */
    public record RGAnalysisReport(
            int numItems,
            Map<String, Double> couplingByScale,
            double[] betaFunction,
            double[] fixedPoints
    ) {}
}
