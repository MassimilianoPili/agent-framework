package com.agentframework.orchestrator.gp;

import java.util.Set;

/**
 * Structured feedback on context quality for a completed task (#35).
 *
 * <p>Produced by {@link ContextQualityService#computeAndStore} and logged for
 * observability. The numeric {@code compositeScore} feeds into GP training via
 * {@code task_outcomes.context_quality_score}; the qualitative fields
 * ({@code unusedSelectedFiles}, {@code missingFiles}, {@code suggestion}) provide
 * actionable feedback for CONTEXT_MANAGER tuning.</p>
 *
 * <h3>Scoring components</h3>
 * <ol>
 *   <li><b>File Relevance</b> (weight 0.45): proxy for Mutual Information I(Context; Result)</li>
 *   <li><b>Entropy Score</b> (weight 0.30): penalises contexts too broad or too narrow</li>
 *   <li><b>KL Divergence Score</b> (weight 0.25): measures alignment between
 *       CM-selected files and worker-used files</li>
 * </ol>
 *
 * @param compositeScore       weighted aggregate in [0, 1]
 * @param fileRelevance        fraction of CM-selected files used by the worker
 * @param entropyScore         sigmoid-based score for context breadth
 * @param klDivergenceScore    geometric mean of bidirectional coverage (proxy for 1 - D_KL)
 * @param unusedSelectedFiles  files selected by CM but ignored by the worker
 * @param missingFiles         files used by the worker but not in CM selection
 * @param suggestion           human-readable feedback for CM improvement
 */
public record ContextQualityFeedback(
        double compositeScore,
        double fileRelevance,
        double entropyScore,
        double klDivergenceScore,
        Set<String> unusedSelectedFiles,
        Set<String> missingFiles,
        String suggestion
) {}
