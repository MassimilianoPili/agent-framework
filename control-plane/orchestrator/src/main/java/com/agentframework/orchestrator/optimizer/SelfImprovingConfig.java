package com.agentframework.orchestrator.optimizer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Self-Improving Prompt and Strategy Optimizer (#182).
 *
 * <pre>
 * self-improving:
 *   enabled: false
 *   evolution:
 *     population-size: 10
 *     max-generations: 20
 *     diversity-penalty: 0.2
 *     selection: TOURNAMENT
 *   canary:
 *     fraction: 0.10
 *     min-samples: 30
 *     significance-level: 0.05
 *   safety:
 *     max-prompt-length: 4096
 *     rollback-on-regression: true
 *   bohb:
 *     max-budget: 100
 *     min-budget: 5
 *     eta: 3
 * </pre>
 */
@ConfigurationProperties(prefix = "self-improving")
public record SelfImprovingConfig(
        EvolutionConfig evolution,
        CanaryConfig canary,
        SafetyConfig safety,
        BOHBConfig bohb
) {
    public SelfImprovingConfig {
        if (evolution == null) evolution = new EvolutionConfig(10, 20, 0.2, "TOURNAMENT");
        if (canary == null) canary = new CanaryConfig(0.10, 30, 0.05);
        if (safety == null) safety = new SafetyConfig(4096, true);
        if (bohb == null) bohb = new BOHBConfig(100, 5, 3);
    }

    /**
     * @param populationSize   number of variants per generation
     * @param maxGenerations   maximum evolution generations
     * @param diversityPenalty semantic diversity penalty weight [0.0, 1.0]
     * @param selection        selection strategy: TOURNAMENT, TRUNCATION
     */
    public record EvolutionConfig(int populationSize, int maxGenerations,
                                    double diversityPenalty, String selection) {
        public EvolutionConfig {
            if (populationSize <= 0) populationSize = 10;
            if (maxGenerations <= 0) maxGenerations = 20;
            if (diversityPenalty < 0) diversityPenalty = 0.2;
            if (selection == null) selection = "TOURNAMENT";
        }
    }

    /**
     * @param fraction         fraction of tasks for canary deployment
     * @param minSamples       minimum samples before significance test
     * @param significanceLevel p-value threshold for promotion
     */
    public record CanaryConfig(double fraction, int minSamples, double significanceLevel) {
        public CanaryConfig {
            if (fraction <= 0) fraction = 0.10;
            if (minSamples <= 0) minSamples = 30;
            if (significanceLevel <= 0) significanceLevel = 0.05;
        }
    }

    /**
     * @param maxPromptLength      maximum prompt variant length in characters
     * @param rollbackOnRegression whether to auto-rollback on canary regression
     */
    public record SafetyConfig(int maxPromptLength, boolean rollbackOnRegression) {
        public SafetyConfig {
            if (maxPromptLength <= 0) maxPromptLength = 4096;
        }
    }

    /**
     * @param maxBudget max BOHB evaluation budget
     * @param minBudget min BOHB evaluation budget
     * @param eta       successive halving factor
     */
    public record BOHBConfig(int maxBudget, int minBudget, int eta) {
        public BOHBConfig {
            if (maxBudget <= 0) maxBudget = 100;
            if (minBudget <= 0) minBudget = 5;
            if (eta <= 1) eta = 3;
        }
    }
}
