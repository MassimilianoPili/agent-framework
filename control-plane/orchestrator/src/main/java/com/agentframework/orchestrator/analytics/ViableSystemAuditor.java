package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Audits the agent-framework against Stafford Beer's Viable System Model (VSM, 1972).
 *
 * <p>The VSM characterises any viable (self-organising, self-maintaining) system
 * using five recursively-nested subsystems:
 * <ol>
 *   <li><b>S1 — Operations</b>: the primary activities that produce value.
 *       In this framework: <em>Worker types</em> executing tasks.</li>
 *   <li><b>S2 — Coordination</b>: anti-oscillation; prevents S1 units from
 *       interfering with each other. In this framework: <em>Redis Streams</em>
 *       (task routing, backpressure, consumer groups).</li>
 *   <li><b>S3 — Control</b>: resource bargaining and performance monitoring.
 *       In this framework: <em>OrchestrationService</em> (dispatch, retry,
 *       token budget, compensation).</li>
 *   <li><b>S4 — Intelligence</b>: environmental scanning and future adaptation.
 *       In this framework: <em>CouncilService</em> (pre-planning advisory,
 *       submodular member selection, GP-predicted reward).</li>
 *   <li><b>S5 — Policy</b>: identity, values, and ultimate authority.
 *       In this framework: <em>Human oversight</em> (the operator who defines
 *       the spec and reviews the final artifacts).</li>
 * </ol>
 *
 * <p><b>Variety and Ashby's Law.</b>
 * For a system to be viable, each control subsystem must have at least as much
 * regulatory variety as the disturbances it manages (Ashby's Law of Requisite Variety).
 * S1 variety is measured as the Shannon entropy H of the worker-type distribution:
 * <pre>
 *   H(S1) = −Σ pᵢ · log₂ pᵢ  (bits)
 * </pre>
 * Low H → too few worker types → S1 is rigid and brittle.
 * High H → diverse specialisations → S1 can absorb varied task demands.
 *
 * <p><b>S3–S4 balance.</b>
 * A healthy VSM requires S3 and S4 to operate in tandem: S3 optimises today's
 * operations while S4 scans for tomorrow's threats. In this framework, the proxy
 * is whether the council (S4) is actually influencing selection (measured as
 * correlation between distinct worker types used and the council member count).
 *
 * @see <a href="https://doi.org/10.1002/9781118795910.ch1">
 *     Beer (1972), Brain of the Firm — The Viable System Model</a>
 */
@Service
@ConditionalOnProperty(prefix = "vsm", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ViableSystemAuditor {

    private static final Logger log = LoggerFactory.getLogger(ViableSystemAuditor.class);

    /** Minimum entropy (bits) for S1 to be considered adequately varied. */
    private static final double S1_MIN_VARIETY_BITS = 1.0;

    /** Minimum distinct worker types for an active S1. */
    private static final int S1_MIN_WORKER_TYPES = 2;

    /** Minimum task completion rate for S3 to be rated as functional. */
    private static final double S3_MIN_MEAN_REWARD = 0.5;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${vsm.max-samples:2000}")
    private int maxSamples;

    public ViableSystemAuditor(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Runs the VSM audit over recorded task outcomes.
     *
     * @return audit report, or {@code null} when no task-outcome data exists
     */
    public VSMAuditReport audit() {
        List<Object[]> rows = taskOutcomeRepository.findRewardsByWorkerType();
        if (rows.isEmpty()) return null;

        // ── Build worker-type distribution ─────────────────────────────────────
        Map<String, List<Double>> rewardsByType = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String workerType = (String) row[0];
            double reward     = ((Number) row[1]).doubleValue();
            rewardsByType.computeIfAbsent(workerType, k -> new ArrayList<>()).add(reward);
        }

        int totalTasks = rows.size();
        int distinctTypes = rewardsByType.size();

        // ── S1 — Operations: Shannon entropy of worker-type distribution ────────
        double s1Entropy = computeShannonEntropy(rewardsByType, totalTasks);
        SubsystemStatus s1Status = distinctTypes < S1_MIN_WORKER_TYPES || s1Entropy < S1_MIN_VARIETY_BITS
                ? SubsystemStatus.DEGRADED : SubsystemStatus.FUNCTIONAL;

        // ── S2 — Coordination: proxy = multiple types co-executing (plan data) ──
        // Load cross-plan summary: [plan_id_text, worker_type, reward]
        List<Object[]> planSummary = taskOutcomeRepository.findPlanWorkerRewardSummary();
        Set<String> plansWithMultipleTypes = new HashSet<>();
        Map<String, Set<String>> planToTypes = new HashMap<>();
        for (Object[] row : planSummary) {
            String planId = (String) row[0];
            String wt     = (String) row[1];
            planToTypes.computeIfAbsent(planId, k -> new HashSet<>()).add(wt);
        }
        for (Map.Entry<String, Set<String>> e : planToTypes.entrySet()) {
            if (e.getValue().size() > 1) plansWithMultipleTypes.add(e.getKey());
        }
        double s2CoordinationRatio = planToTypes.isEmpty() ? 0.0
                : (double) plansWithMultipleTypes.size() / planToTypes.size();
        SubsystemStatus s2Status = s2CoordinationRatio > 0.0
                ? SubsystemStatus.FUNCTIONAL : SubsystemStatus.ABSENT;

        // ── S3 — Control: mean reward across all tasks ──────────────────────────
        double s3MeanReward = rows.stream()
                .mapToDouble(r -> ((Number) r[1]).doubleValue())
                .average().orElse(0.0);
        SubsystemStatus s3Status = s3MeanReward >= S3_MIN_MEAN_REWARD
                ? SubsystemStatus.FUNCTIONAL : SubsystemStatus.DEGRADED;

        // ── S4 — Intelligence: proxy = variety of types selected across plans ───
        // S4 (council) is evidenced by diverse worker selection across plans.
        // Proxy: number of distinct worker types per plan on average.
        double avgTypesPerPlan = planToTypes.isEmpty() ? 0.0
                : planToTypes.values().stream()
                        .mapToInt(Set::size).average().orElse(0.0);
        SubsystemStatus s4Status = avgTypesPerPlan > 1.5
                ? SubsystemStatus.FUNCTIONAL : SubsystemStatus.DEGRADED;

        // ── S5 — Policy: always present (human-in-the-loop by framework design) ─
        SubsystemStatus s5Status = SubsystemStatus.FUNCTIONAL;

        // ── Recommendations ────────────────────────────────────────────────────
        List<String> recommendations = buildRecommendations(
                distinctTypes, s1Entropy, s2CoordinationRatio, s3MeanReward,
                avgTypesPerPlan, s1Status, s2Status, s3Status, s4Status);

        log.debug("VSM audit: S1={} H={} bits, S2={} ratio={}, S3={} mu={}, S4={} avgTypes={}, S5={}",
                s1Status, s1Entropy, s2Status, s2CoordinationRatio,
                s3Status, s3MeanReward, s4Status, avgTypesPerPlan, s5Status);

        return new VSMAuditReport(
                totalTasks, distinctTypes,
                s1Entropy, s1Status,
                s2CoordinationRatio, s2Status,
                s3MeanReward, s3Status,
                avgTypesPerPlan, s4Status,
                s5Status,
                recommendations
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private double computeShannonEntropy(Map<String, List<Double>> rewardsByType, int total) {
        double h = 0.0;
        for (List<Double> rewards : rewardsByType.values()) {
            double p = (double) rewards.size() / total;
            if (p > 0) h -= p * (Math.log(p) / Math.log(2)); // bits
        }
        return h;
    }

    private List<String> buildRecommendations(int distinctTypes, double s1Entropy,
                                               double s2Ratio, double s3MeanReward,
                                               double avgTypesPerPlan,
                                               SubsystemStatus s1, SubsystemStatus s2,
                                               SubsystemStatus s3, SubsystemStatus s4) {
        List<String> recs = new ArrayList<>();
        if (s1 == SubsystemStatus.DEGRADED) {
            recs.add("S1: Add more diverse worker types (current H=" + String.format("%.2f", s1Entropy)
                    + " bits, target ≥ " + S1_MIN_VARIETY_BITS + " bits). "
                    + "Low variety violates Ashby's Law of Requisite Variety.");
        }
        if (s2 == SubsystemStatus.ABSENT) {
            recs.add("S2: No multi-worker plans detected. Enable Redis Streams coordination "
                    + "to allow concurrent task execution across worker types.");
        }
        if (s3 == SubsystemStatus.DEGRADED) {
            recs.add("S3: Mean task reward " + String.format("%.2f", s3MeanReward)
                    + " below threshold " + S3_MIN_MEAN_REWARD
                    + ". Review OrchestrationService retry policy and token budget.");
        }
        if (s4 == SubsystemStatus.DEGRADED) {
            recs.add("S4: Low council-driven variety (avg " + String.format("%.1f", avgTypesPerPlan)
                    + " worker types per plan). Increase CouncilService member diversity "
                    + "or enable submodular selection.");
        }
        if (recs.isEmpty()) {
            recs.add("All VSM subsystems are functional. Framework exhibits viable system properties.");
        }
        return recs;
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────

    /** Health status of a VSM subsystem. */
    public enum SubsystemStatus { FUNCTIONAL, DEGRADED, ABSENT }

    /**
     * VSM audit report.
     *
     * @param totalTasksAnalysed   total task outcomes used in the audit
     * @param distinctWorkerTypes  number of distinct S1 operational units (worker types)
     * @param s1Variety            Shannon entropy of worker-type distribution (bits)
     * @param s1Status             S1 operational health
     * @param s2CoordinationRatio  fraction of plans with ≥ 2 concurrent worker types
     * @param s2Status             S2 coordination health
     * @param s3MeanReward         mean task reward (S3 control efficacy proxy)
     * @param s3Status             S3 control health
     * @param s4AvgTypesPerPlan    average distinct worker types per plan (S4 intelligence proxy)
     * @param s4Status             S4 intelligence health
     * @param s5Status             S5 policy health (always FUNCTIONAL: human-in-the-loop)
     * @param recommendations      actionable recommendations for degraded subsystems
     */
    public record VSMAuditReport(
            int totalTasksAnalysed,
            int distinctWorkerTypes,
            double s1Variety,
            SubsystemStatus s1Status,
            double s2CoordinationRatio,
            SubsystemStatus s2Status,
            double s3MeanReward,
            SubsystemStatus s3Status,
            double s4AvgTypesPerPlan,
            SubsystemStatus s4Status,
            SubsystemStatus s5Status,
            List<String> recommendations
    ) {}
}
