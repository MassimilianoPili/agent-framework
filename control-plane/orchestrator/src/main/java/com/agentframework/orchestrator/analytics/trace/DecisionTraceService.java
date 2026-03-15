package com.agentframework.orchestrator.analytics.trace;

import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Captures and explains worker dispatch decisions at three levels of detail.
 *
 * <p>Implements the XRL taxonomy (Milani et al., ACM Computing Surveys 2023):</p>
 * <ul>
 *   <li><b>L1 (what)</b>: "Chose BE because score 0.87 &gt; FE 0.62"</li>
 *   <li><b>L2 (why)</b>: "GP mu high for similar tasks (3 prior with reward &gt;0.8)"</li>
 *   <li><b>L3 (counterfactual)</b>: "If we had chosen FE, expected reward 0.62 (delta -0.25)"</li>
 * </ul>
 *
 * <p>Uses contrastive explanations (Miller 2019) rather than SHAP aggregation,
 * which is not sound when aggregating from independent components (Faith-Shap,
 * Tsai et al. JMLR 2023).</p>
 *
 * @see <a href="https://doi.org/10.1145/3616864">Milani et al., XRL Survey (ACM 2023)</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.decision-trace", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(DecisionTraceConfig.class)
public class DecisionTraceService {

    private static final Logger log = LoggerFactory.getLogger(DecisionTraceService.class);

    private final ContrastiveExplainer contrastiveExplainer;
    private final DecisionTraceConfig config;

    public DecisionTraceService(ContrastiveExplainer contrastiveExplainer, DecisionTraceConfig config) {
        this.contrastiveExplainer = contrastiveExplainer;
        this.config = config;
    }

    /**
     * Creates a decision trace for a worker dispatch.
     *
     * @param item              the plan item being dispatched
     * @param selected          the chosen worker type
     * @param candidateScores   scores per candidate: outer key = WorkerType, inner key = component name
     * @return complete decision trace with explanations at configured depth
     */
    public DecisionTrace traceDispatch(PlanItem item, WorkerType selected,
                                        Map<WorkerType, Map<String, Double>> candidateScores) {
        Map<String, Double> selectedScores = candidateScores.getOrDefault(selected, Map.of());

        // Build alternatives (sorted by aggregate score, descending)
        List<Alternative> alternatives = candidateScores.entrySet().stream()
                .filter(e -> e.getKey() != selected)
                .map(e -> {
                    double aggScore = e.getValue().values().stream()
                            .mapToDouble(Double::doubleValue).average().orElse(0.0);
                    String reason = buildAlternativeReason(e.getKey(), e.getValue(), config.traceDepth());
                    return new Alternative(e.getKey(), aggScore, reason);
                })
                .sorted(Comparator.comparingDouble(Alternative::score).reversed())
                .limit(config.maxAlternatives())
                .collect(Collectors.toList());

        // Contrastive explanation against the best alternative
        String contrastiveExplanation = "";
        if (!alternatives.isEmpty() && config.traceDepth() >= 3) {
            Alternative bestAlt = alternatives.get(0);
            Map<String, Double> altScores = candidateScores.getOrDefault(bestAlt.type(), Map.of());
            contrastiveExplanation = contrastiveExplainer.explain(selected, bestAlt.type(),
                    selectedScores, altScores);
        }

        // Aggregate component scores for the selected worker
        double selectedAggScore = selectedScores.values().stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);

        DecisionTrace trace = new DecisionTrace(
                item.getTaskKey(),
                selected,
                selectedAggScore,
                alternatives,
                contrastiveExplanation,
                new LinkedHashMap<>(selectedScores),
                Instant.now()
        );

        log.debug("DecisionTrace: task={} selected={} score={} alternatives={} depth={}",
                item.getTaskKey(), selected, String.format("%.4f", selectedAggScore),
                alternatives.size(), config.traceDepth());

        return trace;
    }

    /**
     * Builds a human-readable explanation for an alternative worker.
     */
    private String buildAlternativeReason(WorkerType type, Map<String, Double> scores, int depth) {
        if (scores.isEmpty()) return type + " (no score data)";

        double avg = scores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        if (depth >= 2) {
            // L2: include dominant component
            Optional<Map.Entry<String, Double>> best = scores.entrySet().stream()
                    .max(Comparator.comparingDouble(Map.Entry::getValue));
            if (best.isPresent()) {
                return String.format("%s: avg=%.3f, best component=%s (%.3f)",
                        type, avg, best.get().getKey(), best.get().getValue());
            }
        }

        // L1: simple score
        return String.format("%s: score=%.3f", type, avg);
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * Complete decision trace for a single dispatch.
     *
     * @param taskKey                task identifier
     * @param chosen                 selected worker type
     * @param chosenScore            aggregate score of selected worker
     * @param alternatives           ranked alternatives with explanations
     * @param contrastiveExplanation "why A not B" explanation (L3, empty if depth < 3)
     * @param componentScores        per-component scores for the selected worker
     * @param timestamp              when the decision was made
     */
    public record DecisionTrace(
            String taskKey,
            WorkerType chosen,
            double chosenScore,
            List<Alternative> alternatives,
            String contrastiveExplanation,
            Map<String, Double> componentScores,
            Instant timestamp
    ) {}

    /**
     * An alternative worker that was considered but not selected.
     *
     * @param type   worker type
     * @param score  aggregate score
     * @param reason human-readable explanation of why this alternative was not chosen
     */
    public record Alternative(
            WorkerType type,
            double score,
            String reason
    ) {}
}
