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
 * Applies curriculum learning (Bengio et al., 2009) to few-shot prompting.
 *
 * <p>Instead of random or recency-based few-shot example selection, this service
 * orders exemplars from <b>simplest to most complex</b>, creating a cognitive ramp
 * that helps the LLM build understanding progressively. The selection strategy is:</p>
 *
 * <ol>
 *   <li><b>1 easy example</b> — anchors the pattern with minimal complexity</li>
 *   <li><b>1-2 medium examples</b> — builds understanding of typical cases</li>
 *   <li><b>1-2 similar examples</b> — closest to the current task for transfer</li>
 * </ol>
 *
 * <p>Golden examples are maintained per workerType in an in-memory registry.
 * The registry supports adding, deduplication (removing examples with complexity
 * too close to existing ones), and capacity limits per worker type.</p>
 *
 * @see <a href="https://arxiv.org/abs/0904.3664">
 *     Curriculum Learning (Bengio et al., 2009)</a>
 */
@Service
@ConditionalOnProperty(prefix = "curriculum-prompting", name = "enabled", havingValue = "true", matchIfMissing = false)
public class CurriculumPromptingService {

    private static final Logger log = LoggerFactory.getLogger(CurriculumPromptingService.class);

    /** Minimum complexity difference to avoid near-duplicate examples. */
    private static final double DEDUP_COMPLEXITY_EPSILON = 0.05;

    private final ConcurrentHashMap<String, List<GoldenExample>> registry = new ConcurrentHashMap<>();

    @Value("${curriculum-prompting.max-examples-per-type:50}")
    private int maxExamplesPerType;

    @Value("${curriculum-prompting.selection-k:3}")
    private int selectionK;

    /**
     * Selects K exemplars for few-shot prompting using curriculum strategy.
     *
     * <p>Selection phases:
     * <ol>
     *   <li>Filter examples by workerType</li>
     *   <li>Pick 1 easy (lowest complexity)</li>
     *   <li>Pick medium examples (complexity in [0.3, 0.7] range, closest to 0.5)</li>
     *   <li>Pick similar examples (closest complexity to the current task)</li>
     *   <li>Order final selection: easy → medium → similar</li>
     * </ol></p>
     *
     * @param workerType      the worker type to select examples for
     * @param taskDescription description of the current task (for logging)
     * @param taskComplexity  estimated complexity of the current task [0, 1]
     * @param k               number of exemplars to select (overrides default if > 0)
     * @return curriculum selection with ordered exemplars and rationale
     */
    public CurriculumSelection selectExemplars(String workerType, String taskDescription,
                                                double taskComplexity, int k) {
        int effectiveK = k > 0 ? k : selectionK;
        List<GoldenExample> pool = registry.getOrDefault(workerType, List.of());

        if (pool.isEmpty()) {
            return new CurriculumSelection(List.of(), "No golden examples available for " + workerType);
        }

        List<GoldenExample> sorted = pool.stream()
                .sorted(Comparator.comparingDouble(GoldenExample::complexity))
                .toList();

        List<GoldenExample> selected = new ArrayList<>();

        // Phase 1: 1 easy example (lowest complexity)
        selected.add(sorted.get(0));

        // Phase 2: medium examples (complexity closest to 0.5, in [0.3, 0.7])
        List<GoldenExample> mediums = sorted.stream()
                .filter(e -> e.complexity() >= 0.3 && e.complexity() <= 0.7)
                .filter(e -> !selected.contains(e))
                .sorted(Comparator.comparingDouble(e -> Math.abs(e.complexity() - 0.5)))
                .toList();

        int mediumSlots = Math.min(effectiveK / 2, mediums.size());
        for (int i = 0; i < mediumSlots; i++) {
            selected.add(mediums.get(i));
        }

        // Phase 3: similar examples (closest to current task complexity)
        List<GoldenExample> similar = sorted.stream()
                .filter(e -> !selected.contains(e))
                .sorted(Comparator.comparingDouble(e -> Math.abs(e.complexity() - taskComplexity)))
                .toList();

        while (selected.size() < effectiveK && !similar.isEmpty()) {
            selected.add(similar.get(selected.size() - mediumSlots - 1 < similar.size()
                    ? selected.size() - mediumSlots - 1 : 0));
            if (selected.size() - mediumSlots - 1 >= similar.size()) break;
        }

        // Cap at K and order: easy → medium → similar (by complexity ascending)
        List<GoldenExample> result = selected.stream()
                .distinct()
                .limit(effectiveK)
                .sorted(Comparator.comparingDouble(GoldenExample::complexity))
                .toList();

        String rationale = String.format("Selected %d/%d exemplars for %s (taskComplexity=%.2f): %s",
                result.size(), pool.size(), workerType, taskComplexity,
                result.stream().map(e -> String.format("%.2f", e.complexity())).collect(Collectors.joining(" → ")));

        log.debug(rationale);

        return new CurriculumSelection(result, rationale);
    }

    /**
     * Adds a golden example to the registry for the given worker type.
     * Evicts the oldest example if the registry exceeds capacity.
     *
     * @param example the golden example to add
     */
    public void addExample(GoldenExample example) {
        List<GoldenExample> pool = registry.computeIfAbsent(example.workerType(),
                k -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (pool) {
            pool.add(example);

            // Capacity eviction: remove oldest if over limit
            while (pool.size() > maxExamplesPerType) {
                pool.remove(0);
            }
        }

        log.debug("Added golden example for {}: complexity={}, pool size={}",
                  example.workerType(), example.complexity(), pool.size());
    }

    /**
     * Removes near-duplicate examples (complexity within epsilon of each other).
     * Keeps the example with the higher relevance (longer solution text as proxy).
     *
     * @param workerType the worker type to deduplicate
     * @return number of examples removed
     */
    public int deduplicateExamples(String workerType) {
        List<GoldenExample> pool = registry.get(workerType);
        if (pool == null || pool.size() <= 1) {
            return 0;
        }

        synchronized (pool) {
            List<GoldenExample> sorted = pool.stream()
                    .sorted(Comparator.comparingDouble(GoldenExample::complexity))
                    .collect(Collectors.toCollection(ArrayList::new));

            List<GoldenExample> deduplicated = new ArrayList<>();
            deduplicated.add(sorted.get(0));

            for (int i = 1; i < sorted.size(); i++) {
                GoldenExample prev = deduplicated.get(deduplicated.size() - 1);
                GoldenExample curr = sorted.get(i);

                if (Math.abs(curr.complexity() - prev.complexity()) >= DEDUP_COMPLEXITY_EPSILON) {
                    deduplicated.add(curr);
                } else {
                    // Keep the one with longer solution (proxy for higher quality)
                    if (curr.solution().length() > prev.solution().length()) {
                        deduplicated.set(deduplicated.size() - 1, curr);
                    }
                }
            }

            int removed = pool.size() - deduplicated.size();
            pool.clear();
            pool.addAll(deduplicated);

            if (removed > 0) {
                log.info("Deduplicated {} examples for {} (epsilon={}), {} remaining",
                         removed, workerType, DEDUP_COMPLEXITY_EPSILON, pool.size());
            }

            return removed;
        }
    }

    /**
     * Returns the number of golden examples stored for a worker type.
     */
    public int getExampleCount(String workerType) {
        List<GoldenExample> pool = registry.get(workerType);
        return pool != null ? pool.size() : 0;
    }

    /**
     * Returns statistics for all worker types in the registry.
     */
    public Map<String, Integer> getRegistryStats() {
        Map<String, Integer> stats = new HashMap<>();
        registry.forEach((type, pool) -> stats.put(type, pool.size()));
        return Collections.unmodifiableMap(stats);
    }

    /**
     * A curated golden example for few-shot prompting.
     *
     * @param taskDescription description of the example task
     * @param solution        the expected/ideal solution
     * @param complexity      task complexity score [0, 1] (0 = trivial, 1 = very complex)
     * @param workerType      the worker type this example applies to
     */
    public record GoldenExample(
            String taskDescription,
            String solution,
            double complexity,
            String workerType
    ) {}

    /**
     * Result of a curriculum exemplar selection.
     *
     * @param selected  the ordered exemplars (easy → medium → similar)
     * @param rationale human-readable explanation of the selection
     */
    public record CurriculumSelection(
            List<GoldenExample> selected,
            String rationale
    ) {}
}
