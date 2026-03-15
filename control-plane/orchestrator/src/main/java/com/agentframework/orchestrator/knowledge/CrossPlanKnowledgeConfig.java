package com.agentframework.orchestrator.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Cross-Plan Knowledge Transfer Engine (#178).
 *
 * <pre>
 * cross-plan-knowledge:
 *   enabled: false
 *   prompt-refinement:
 *     min-usage-count: 3
 *     similarity-threshold: 0.75
 *   error-patterns:
 *     topk-retrieval: 5
 *     min-confidence: 0.6
 *   transfer-confidence:
 *     source-similarity-min: 0.7
 *     historical-success-threshold: 0.65
 *   insight-extraction:
 *     min-applicability: 0.5
 * </pre>
 */
@ConfigurationProperties(prefix = "cross-plan-knowledge")
public record CrossPlanKnowledgeConfig(
        PromptRefinementConfig promptRefinement,
        ErrorPatternsConfig errorPatterns,
        TransferConfidenceConfig transferConfidence,
        InsightExtractionConfig insightExtraction
) {
    public CrossPlanKnowledgeConfig {
        if (promptRefinement == null) promptRefinement = new PromptRefinementConfig(3, 0.75);
        if (errorPatterns == null) errorPatterns = new ErrorPatternsConfig(5, 0.6);
        if (transferConfidence == null) transferConfidence = new TransferConfidenceConfig(0.7, 0.65);
        if (insightExtraction == null) insightExtraction = new InsightExtractionConfig(0.5);
    }

    /**
     * @param minUsageCount       minimum times a prompt must be used before promotion
     * @param similarityThreshold archetype embedding similarity threshold for matching
     */
    public record PromptRefinementConfig(int minUsageCount, double similarityThreshold) {
        public PromptRefinementConfig {
            if (minUsageCount <= 0) minUsageCount = 3;
            if (similarityThreshold <= 0) similarityThreshold = 0.75;
        }
    }

    /**
     * @param topkRetrieval max error patterns to retrieve per query
     * @param minConfidence minimum confidence threshold for using a pattern
     */
    public record ErrorPatternsConfig(int topkRetrieval, double minConfidence) {
        public ErrorPatternsConfig {
            if (topkRetrieval <= 0) topkRetrieval = 5;
            if (minConfidence <= 0) minConfidence = 0.6;
        }
    }

    /**
     * @param sourceSimilarityMin      minimum similarity between source and target archetype
     * @param historicalSuccessThreshold minimum historical success rate for transfer
     */
    public record TransferConfidenceConfig(double sourceSimilarityMin, double historicalSuccessThreshold) {
        public TransferConfidenceConfig {
            if (sourceSimilarityMin <= 0) sourceSimilarityMin = 0.7;
            if (historicalSuccessThreshold <= 0) historicalSuccessThreshold = 0.65;
        }
    }

    /**
     * @param minApplicability minimum applicability score for extracted insights
     */
    public record InsightExtractionConfig(double minApplicability) {
        public InsightExtractionConfig {
            if (minApplicability <= 0) minApplicability = 0.5;
        }
    }
}
