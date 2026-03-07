package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Root cause analysis for task outcomes using Pearl's causal inference.
 *
 * <p>For a given task, loads its outcome and a background population of
 * outcomes from the same worker type, then runs backdoor adjustment
 * on the {@link CausalDag#defaultDag() default causal DAG} to attribute
 * the outcome to its contributing causal factors.</p>
 *
 * <p>Variable mapping from task_outcomes columns:</p>
 * <ul>
 *   <li>{@code worker_elo} → elo_at_dispatch</li>
 *   <li>{@code token_budget} → gp_mu (proxy: GP predicted reward ≈ budget adequacy)</li>
 *   <li>{@code context_quality} → 1 − gp_sigma2 (proxy: high uncertainty = low quality)</li>
 *   <li>{@code task_complexity} → ‖task_embedding‖₂ (L2 norm of embedding)</li>
 *   <li>{@code task_success} → actual_reward &gt; 0.5 (binary)</li>
 * </ul>
 *
 * @see CausalDag
 * @see CausalAttribution
 * @see <a href="https://doi.org/10.1017/CBO9780511803161">
 *     Pearl (2009), Causality, 2nd ed.</a>
 */
@Service
@ConditionalOnProperty(prefix = "causal", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RootCauseAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RootCauseAnalyzer.class);
    private static final int MAX_BACKGROUND_OUTCOMES = 500;
    private static final int MIN_BACKGROUND_SIZE = 10;
    private static final double CONFOUNDING_THRESHOLD = 0.1;

    /** Causal factors we analyse (subset of DAG nodes that map to data columns). */
    static final List<String> CAUSAL_FACTORS = List.of(
            "context_quality", "worker_elo", "token_budget", "task_complexity"
    );

    private final TaskOutcomeRepository taskOutcomeRepository;

    public RootCauseAnalyzer(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Root cause analysis for a specific task outcome.
     *
     * <ol>
     *   <li>Load outcome of the target task (by planId + taskKey)</li>
     *   <li>Load background population (same worker_type, up to 500)</li>
     *   <li>Build the {@link CausalDag#defaultDag() default causal DAG}</li>
     *   <li>For each causal factor, compute observational vs interventional P</li>
     *   <li>Rank by |causalContribution| descending</li>
     * </ol>
     *
     * @param planId  the plan UUID
     * @param taskKey the task key within the plan
     * @return root cause report with ranked attributions
     * @throws IllegalArgumentException if no outcome found for the given task
     */
    public RootCauseReport analyseTask(UUID planId, String taskKey) {
        // 1. Load target task outcome
        List<Object[]> targetRows = taskOutcomeRepository.findByPlanIdAndTaskKey(planId, taskKey);
        if (targetRows.isEmpty()) {
            throw new IllegalArgumentException(
                    "No outcome found for planId=" + planId + ", taskKey=" + taskKey);
        }

        Object[] target = targetRows.get(0);
        // Columns: elo_at_dispatch, gp_mu, gp_sigma2, actual_reward, embedding_text, worker_type
        double targetElo = toDouble(target[0], 1600.0);
        double targetGpMu = toDouble(target[1], 0.5);
        double targetGpSigma2 = toDouble(target[2], 1.0);
        double targetReward = toDouble(target[3], 0.0);
        String embeddingText = (String) target[4];
        String workerType = (String) target[5];

        boolean taskSucceeded = targetReward > 0.5;
        double targetComplexity = embeddingNorm(embeddingText);
        double targetContextQuality = 1.0 - targetGpSigma2;

        // 2. Load background population
        List<Object[]> bgRows = taskOutcomeRepository.findCausalDataByWorkerType(
                workerType, MAX_BACKGROUND_OUTCOMES);

        if (bgRows.size() < MIN_BACKGROUND_SIZE) {
            log.debug("Insufficient background data for causal analysis: {} rows (need {})",
                    bgRows.size(), MIN_BACKGROUND_SIZE);
            double baselineRate = bgRows.isEmpty() ? 0.5
                    : bgRows.stream().mapToDouble(r -> toDouble(r[3], 0.0) > 0.5 ? 1.0 : 0.0).average().orElse(0.5);
            return new RootCauseReport(taskKey, planId, taskSucceeded, baselineRate,
                    List.of(), null, Instant.now());
        }

        // 3. Build data arrays from background population
        int n = bgRows.size();
        double[] eloArr = new double[n];
        double[] gpMuArr = new double[n];
        double[] cqArr = new double[n];
        double[] complexityArr = new double[n];
        double[] successArr = new double[n];
        // Intermediate nodes (filled with derived values)
        double[] qualityArr = new double[n];
        double[] durationArr = new double[n];
        double[] difficultyArr = new double[n];

        double baselineSuccessSum = 0;
        for (int i = 0; i < n; i++) {
            Object[] row = bgRows.get(i);
            eloArr[i] = toDouble(row[0], 1600.0);
            gpMuArr[i] = toDouble(row[1], 0.5);
            double sigma2 = toDouble(row[2], 1.0);
            cqArr[i] = 1.0 - sigma2;
            double reward = toDouble(row[3], 0.0);
            successArr[i] = reward > 0.5 ? 1.0 : 0.0;
            baselineSuccessSum += successArr[i];

            String bgEmb = (String) row[4];
            complexityArr[i] = embeddingNorm(bgEmb);

            // Derived intermediate nodes (proxied from available data)
            qualityArr[i] = (eloArr[i] - 1400.0) / 400.0; // normalized elo → quality
            durationArr[i] = gpMuArr[i];                    // budget proxy
            difficultyArr[i] = complexityArr[i];             // complexity → difficulty
        }

        double baselineSuccessRate = baselineSuccessSum / n;

        // 4. Build data map for CausalDag
        Map<String, double[]> data = new LinkedHashMap<>();
        data.put("context_quality", cqArr);
        data.put("worker_elo", eloArr);
        data.put("quality", qualityArr);
        data.put("token_budget", gpMuArr);
        data.put("duration", durationArr);
        data.put("task_complexity", complexityArr);
        data.put("difficulty", difficultyArr);
        data.put("task_success", successArr);

        // 5. Compute attributions
        CausalDag dag = CausalDag.defaultDag();
        List<CausalAttribution> attributions = new ArrayList<>();

        Map<String, Double> targetValues = Map.of(
                "context_quality", targetContextQuality,
                "worker_elo", targetElo,
                "token_budget", targetGpMu,
                "task_complexity", targetComplexity
        );

        for (String factor : CAUSAL_FACTORS) {
            double observedValue = targetValues.getOrDefault(factor, 0.5);

            double observationalP = dag.observationalProbability(
                    factor, "task_success", observedValue, data);
            double interventionalP = dag.interventionalProbability(
                    factor, "task_success", observedValue, data);
            double causalContribution = interventionalP - baselineSuccessRate;
            boolean confounded = Math.abs(observationalP - interventionalP) > CONFOUNDING_THRESHOLD;

            attributions.add(new CausalAttribution(
                    factor, observationalP, interventionalP,
                    causalContribution, confounded));
        }

        // Sort by |causalContribution| descending
        attributions.sort(Comparator.comparingDouble(
                (CausalAttribution a) -> Math.abs(a.causalContribution())).reversed());

        String primaryCause = attributions.isEmpty() ? null : attributions.get(0).factor();

        log.info("Root cause analysis for '{}' (plan={}): success={}, baseline={}, primary={}",
                taskKey, planId, taskSucceeded,
                String.format("%.3f", baselineSuccessRate), primaryCause);

        return new RootCauseReport(taskKey, planId, taskSucceeded, baselineSuccessRate,
                attributions, primaryCause, Instant.now());
    }

    /**
     * Parses embedding text "[0.1,0.2,...]" and computes its L2 norm.
     * The norm serves as a proxy for task complexity.
     */
    static double embeddingNorm(String embeddingText) {
        if (embeddingText == null || embeddingText.length() < 3) return 0.0;
        String inner = embeddingText.startsWith("[")
                ? embeddingText.substring(1, embeddingText.length() - 1)
                : embeddingText;
        String[] parts = inner.split(",");
        double sumSq = 0;
        try {
            for (String part : parts) {
                double v = Double.parseDouble(part.trim());
                sumSq += v * v;
            }
        } catch (NumberFormatException e) {
            return 0.0;
        }
        return Math.sqrt(sumSq);
    }

    /** Safely extracts a double from a nullable Number column. */
    private static double toDouble(Object obj, double defaultValue) {
        return obj instanceof Number n ? n.doubleValue() : defaultValue;
    }

    /**
     * Root cause analysis report for a single task.
     *
     * @param taskKey              the task key
     * @param planId               the plan UUID
     * @param taskSucceeded        whether the task succeeded (reward &gt; 0.5)
     * @param baselineSuccessRate  population baseline success rate
     * @param attributions         causal attributions ranked by |contribution|
     * @param primaryCause         factor with highest |causalContribution|, or null
     * @param analysedAt           timestamp of the analysis
     */
    public record RootCauseReport(
            String taskKey,
            UUID planId,
            boolean taskSucceeded,
            double baselineSuccessRate,
            List<CausalAttribution> attributions,
            String primaryCause,
            Instant analysedAt
    ) {}
}
