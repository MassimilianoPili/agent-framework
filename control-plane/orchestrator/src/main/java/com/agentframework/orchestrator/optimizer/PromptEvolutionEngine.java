package com.agentframework.orchestrator.optimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Population-based evolutionary prompt optimization engine.
 *
 * <p>Evolves a population of prompt variants using mutation operators inspired
 * by EvoPrompt (Guo et al., ICLR 2024):</p>
 * <ul>
 *   <li><b>GA_CROSSOVER</b>: combine two parent prompts at a random split point</li>
 *   <li><b>DE_DIFFERENTIAL</b>: generate variation using differential evolution</li>
 *   <li><b>RANDOM_EDIT</b>: insert, delete, or replace a random segment</li>
 * </ul>
 *
 * <p>Multi-objective fitness prevents mode collapse: PRM score (0.5) + diversity (0.2)
 * + length efficiency (0.1) + safety compliance (0.2).</p>
 *
 * <p>Selection: tournament selection (top-k from random subset) preserves diversity
 * better than truncation selection.</p>
 */
public class PromptEvolutionEngine {

    private static final Logger log = LoggerFactory.getLogger(PromptEvolutionEngine.class);

    public enum MutationStrategy { GA_CROSSOVER, DE_DIFFERENTIAL, RANDOM_EDIT }

    private final SelfImprovingConfig config;
    private final Map<String, List<PromptVariant>> populations = new HashMap<>();

    public PromptEvolutionEngine(SelfImprovingConfig config) {
        this.config = config;
    }

    /**
     * Initializes a population for a worker type with seed prompts.
     *
     * @param workerType worker type identifier
     * @param seeds      initial prompt variants (from CrossPlanKnowledgeEngine or defaults)
     */
    public void initializePopulation(String workerType, List<String> seeds) {
        List<PromptVariant> population = new ArrayList<>();
        for (int i = 0; i < seeds.size() && i < config.evolution().populationSize(); i++) {
            population.add(new PromptVariant(
                    UUID.randomUUID(), null, workerType, seeds.get(i),
                    0, 0.0, null, Instant.now()));
        }
        populations.put(workerType, population);
        log.debug("Initialized population for {}: {} variants", workerType, population.size());
    }

    /**
     * Evolves the population for one generation.
     *
     * <p>Steps: (1) select parents via tournament, (2) generate offspring via mutation,
     * (3) evaluate fitness, (4) select survivors.</p>
     *
     * @param workerType  worker type
     * @param generation  current generation number
     * @param fitnessScores map of variant UUID → fitness score
     * @return new generation of variants
     */
    public List<PromptVariant> evolveGeneration(String workerType, int generation,
                                                  Map<UUID, Double> fitnessScores) {
        List<PromptVariant> current = populations.getOrDefault(workerType, List.of());
        if (current.isEmpty()) return List.of();

        // Update fitness scores
        current = current.stream()
                .map(v -> fitnessScores.containsKey(v.id())
                        ? new PromptVariant(v.id(), v.parentId(), v.workerType(), v.promptContent(),
                        generation, fitnessScores.get(v.id()), v.mutationUsed(), v.createdAt())
                        : v)
                .collect(Collectors.toCollection(ArrayList::new));

        int popSize = config.evolution().populationSize();
        List<PromptVariant> offspring = new ArrayList<>();

        // Generate offspring via mutation
        for (int i = 0; i < popSize; i++) {
            PromptVariant parent1 = tournamentSelect(current);
            MutationStrategy strategy = randomStrategy();

            PromptVariant child = switch (strategy) {
                case GA_CROSSOVER -> {
                    PromptVariant parent2 = tournamentSelect(current);
                    yield crossover(parent1, parent2, generation, workerType);
                }
                case DE_DIFFERENTIAL -> differential(parent1, current, generation, workerType);
                case RANDOM_EDIT -> randomEdit(parent1, generation, workerType);
            };

            // Enforce max length
            if (child.promptContent().length() > config.safety().maxPromptLength()) {
                child = new PromptVariant(child.id(), child.parentId(), child.workerType(),
                        child.promptContent().substring(0, config.safety().maxPromptLength()),
                        child.generation(), child.fitnessScore(), child.mutationUsed(), child.createdAt());
            }

            offspring.add(child);
        }

        // Combine and select top-N (elitism: keep best parent + best offspring)
        List<PromptVariant> combined = new ArrayList<>(current);
        combined.addAll(offspring);
        combined.sort(Comparator.comparingDouble(PromptVariant::fitnessScore).reversed());

        List<PromptVariant> nextGen = combined.subList(0, Math.min(popSize, combined.size()));
        populations.put(workerType, new ArrayList<>(nextGen));

        log.debug("Evolved generation {} for {}: {} variants", generation, workerType, nextGen.size());
        return nextGen;
    }

    /**
     * Returns the current population for a worker type.
     */
    public List<PromptVariant> getPopulation(String workerType) {
        return populations.getOrDefault(workerType, List.of());
    }

    /**
     * Returns the best variant for a worker type.
     */
    public Optional<PromptVariant> getBest(String workerType) {
        return getPopulation(workerType).stream()
                .max(Comparator.comparingDouble(PromptVariant::fitnessScore));
    }

    // --- Mutation operators ---

    private PromptVariant crossover(PromptVariant p1, PromptVariant p2,
                                      int generation, String workerType) {
        String s1 = p1.promptContent();
        String s2 = p2.promptContent();

        // Split at sentence boundary (nearest period)
        int splitPoint = s1.length() / 2;
        int periodIdx = s1.indexOf('.', splitPoint);
        if (periodIdx > 0 && periodIdx < s1.length() - 1) {
            splitPoint = periodIdx + 1;
        }

        String child = s1.substring(0, splitPoint) + " " + s2.substring(Math.min(splitPoint, s2.length()));

        return new PromptVariant(UUID.randomUUID(), p1.id(), workerType, child.trim(),
                generation, 0.0, MutationStrategy.GA_CROSSOVER, Instant.now());
    }

    private PromptVariant differential(PromptVariant base, List<PromptVariant> population,
                                         int generation, String workerType) {
        // DE: pick two random others, compute "difference" as appended context
        if (population.size() < 3) return randomEdit(base, generation, workerType);

        PromptVariant r1 = population.get(ThreadLocalRandom.current().nextInt(population.size()));
        PromptVariant r2 = population.get(ThreadLocalRandom.current().nextInt(population.size()));

        // Differential: append distinctive segment from r1 that's not in r2
        String diff = extractDistinctive(r1.promptContent(), r2.promptContent());
        String child = base.promptContent() + "\n" + diff;

        return new PromptVariant(UUID.randomUUID(), base.id(), workerType, child.trim(),
                generation, 0.0, MutationStrategy.DE_DIFFERENTIAL, Instant.now());
    }

    private PromptVariant randomEdit(PromptVariant parent, int generation, String workerType) {
        String content = parent.promptContent();
        String[] sentences = content.split("(?<=\\.)\\s+");

        if (sentences.length < 2) {
            // Too short to edit meaningfully — return copy
            return new PromptVariant(UUID.randomUUID(), parent.id(), workerType, content,
                    generation, 0.0, MutationStrategy.RANDOM_EDIT, Instant.now());
        }

        // Random operation: delete, duplicate, or shuffle a sentence
        int op = ThreadLocalRandom.current().nextInt(3);
        List<String> list = new ArrayList<>(Arrays.asList(sentences));

        switch (op) {
            case 0 -> { // delete random sentence
                if (list.size() > 2) list.remove(ThreadLocalRandom.current().nextInt(list.size()));
            }
            case 1 -> { // duplicate a sentence (emphasis)
                int idx = ThreadLocalRandom.current().nextInt(list.size());
                list.add(idx + 1, list.get(idx));
            }
            case 2 -> { // shuffle two adjacent sentences
                int idx = ThreadLocalRandom.current().nextInt(list.size() - 1);
                String tmp = list.get(idx);
                list.set(idx, list.get(idx + 1));
                list.set(idx + 1, tmp);
            }
        }

        String child = String.join(" ", list);
        return new PromptVariant(UUID.randomUUID(), parent.id(), workerType, child.trim(),
                generation, 0.0, MutationStrategy.RANDOM_EDIT, Instant.now());
    }

    // --- Helpers ---

    private PromptVariant tournamentSelect(List<PromptVariant> population) {
        int k = Math.max(2, population.size() / 3);
        return ThreadLocalRandom.current().ints(0, population.size())
                .distinct().limit(k)
                .mapToObj(population::get)
                .max(Comparator.comparingDouble(PromptVariant::fitnessScore))
                .orElse(population.getFirst());
    }

    private MutationStrategy randomStrategy() {
        MutationStrategy[] strategies = MutationStrategy.values();
        return strategies[ThreadLocalRandom.current().nextInt(strategies.length)];
    }

    private String extractDistinctive(String s1, String s2) {
        // Simple: first sentence of s1 that doesn't appear in s2
        String[] sentences = s1.split("(?<=\\.)\\s+");
        for (String sentence : sentences) {
            if (!s2.contains(sentence.trim())) {
                return sentence.trim();
            }
        }
        return sentences.length > 0 ? sentences[0] : "";
    }

    /**
     * A prompt variant in the evolutionary population.
     *
     * @param id             unique identifier
     * @param parentId       parent variant UUID (null for seeds)
     * @param workerType     target worker type
     * @param promptContent  the prompt text
     * @param generation     generation number
     * @param fitnessScore   multi-objective fitness [0.0, 1.0]
     * @param mutationUsed   which mutation produced this variant
     * @param createdAt      creation timestamp
     */
    public record PromptVariant(
            UUID id,
            @Nullable UUID parentId,
            String workerType,
            String promptContent,
            int generation,
            double fitnessScore,
            @Nullable MutationStrategy mutationUsed,
            Instant createdAt
    ) {}
}
