package com.agentframework.orchestrator.analytics.trace;

import com.agentframework.orchestrator.domain.WorkerType;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates contrastive explanations: "Why worker A was chosen over worker B?"
 *
 * <p>Contrastive explanations are empirically more useful than feature importance
 * for dispatch decisions (Miller 2019, AI journal, ~4985 cit). Instead of listing
 * all factors, we identify the <em>dominant differentiator</em> — the single
 * component where the chosen worker most exceeds the alternative.</p>
 *
 * @see <a href="https://doi.org/10.1016/j.artint.2019.103209">Miller, Explanation in AI (2019)</a>
 */
@Component
public class ContrastiveExplainer {

    private static final Map<String, String> COMPONENT_DESCRIPTIONS = Map.of(
            "gp_mu", "GP posterior expected reward",
            "mcts", "MCTS policy value",
            "elo", "ELO rating",
            "cost", "estimated cost (USD)",
            "hedge", "Hedge algorithm weight",
            "fdt", "Functional Decision Theory policy reward",
            "review", "historical review score"
    );

    /**
     * Generates a contrastive explanation for why {@code chosen} was selected over {@code alternative}.
     *
     * @param chosen         the selected worker type
     * @param alternative    the rejected worker type
     * @param chosenScores   component scores for the chosen worker
     * @param altScores      component scores for the alternative
     * @return human-readable contrastive explanation
     */
    public String explain(WorkerType chosen, WorkerType alternative,
                          Map<String, Double> chosenScores, Map<String, Double> altScores) {
        Map<String, Double> deltas = componentDelta(chosenScores, altScores);

        if (deltas.isEmpty()) {
            return String.format("%s chosen over %s (no score difference detected)", chosen, alternative);
        }

        // Find the dominant differentiator (largest positive delta)
        Map.Entry<String, Double> dominant = deltas.entrySet().stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .orElse(null);

        if (dominant == null || dominant.getValue() <= 0) {
            return String.format("%s chosen over %s by tie-break (all scores approximately equal)",
                    chosen, alternative);
        }

        String componentName = COMPONENT_DESCRIPTIONS.getOrDefault(dominant.getKey(), dominant.getKey());
        double chosenVal = chosenScores.getOrDefault(dominant.getKey(), 0.0);
        double altVal = altScores.getOrDefault(dominant.getKey(), 0.0);

        return String.format("%s chosen over %s: %s is higher (%.3f vs %.3f, delta=+%.3f)",
                chosen, alternative, componentName, chosenVal, altVal, dominant.getValue());
    }

    /**
     * Computes per-component delta between chosen and alternative scores.
     *
     * @return map of component name → (chosen_score - alternative_score)
     */
    public Map<String, Double> componentDelta(Map<String, Double> chosenScores,
                                               Map<String, Double> altScores) {
        Map<String, Double> deltas = new LinkedHashMap<>();

        for (String key : chosenScores.keySet()) {
            double chosenVal = chosenScores.getOrDefault(key, 0.0);
            double altVal = altScores.getOrDefault(key, 0.0);
            double delta = chosenVal - altVal;
            if (Math.abs(delta) > 1e-6) {
                deltas.put(key, delta);
            }
        }

        // Include keys only in altScores (negative delta)
        for (String key : altScores.keySet()) {
            if (!chosenScores.containsKey(key)) {
                deltas.put(key, -altScores.get(key));
            }
        }

        return deltas;
    }
}
